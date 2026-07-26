package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaStorage;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class AreaMusicServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final NetworkSender NO_OP_NETWORK_SENDER = new NetworkSender() {
        @Override
        public void sendReload(ServerPlayer player, long revision) {
        }

        @Override
        public void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
        }
    };
    private static final AreaMusicCommands.Operations COMMAND_OPERATIONS = new AreaMusicCommands.Operations() {
        @Override
        public Iterable<String> musicIds() {
            return AreaMusicServer.musicIds();
        }

        @Override
        public int createArea(
                CommandSourceStack source,
                String areaId,
                BlockPos pos1,
                BlockPos pos2,
                String musicId
        ) {
            return AreaMusicServer.createArea(source, areaId, pos1, pos2, musicId);
        }

        @Override
        public int requestReload(CommandSourceStack source) {
            return AreaMusicServer.requestReload(source);
        }
    };
    private static final ServerLifecycleCoordinator<
            MinecraftServer,
            NetworkSender,
            UUID,
            PlayerAreaTracker> SERVER_LIFECYCLE =
            new ServerLifecycleCoordinator<>(NO_OP_NETWORK_SENDER);
    private static volatile ServerState serverState = ServerState.inactive();

    private AreaMusicServer() {
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        AreaMusicCommands.register(dispatcher, COMMAND_OPERATIONS);
    }

    public static synchronized void onServerStarted(
            MinecraftServer startedServer,
            Path gameDirectory,
            Path configDirectory,
            NetworkSender sender
    ) {
        MinecraftServer checkedServer = Objects.requireNonNull(startedServer, "startedServer");
        Path normalizedGameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath()
                .normalize();
        Path normalizedConfigDirectory = Objects.requireNonNull(configDirectory, "configDirectory")
                .toAbsolutePath()
                .normalize();
        NetworkSender checkedSender = Objects.requireNonNull(sender, "sender");
        Path worldRoot = checkedServer.getWorldPath(LevelResource.ROOT)
                .toAbsolutePath()
                .normalize();
        AreaStorage startedStorage = new AreaStorage(
                storageDirectory(normalizedConfigDirectory, worldRoot),
                new AreaJsonCodec()
        );

        ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle =
                SERVER_LIFECYCLE.start(checkedServer, checkedSender);
        serverState = ServerState.started(
                lifecycle,
                normalizedGameDirectory,
                startedStorage
        );
        requestReload(null);
    }

    public static synchronized void onServerStopping(MinecraftServer stoppingServer) {
        if (!SERVER_LIFECYCLE.stop(stoppingServer)) {
            return;
        }
        serverState = ServerState.inactive();
    }

    public static void onPlayerEndTick(ServerPlayer player) {
        syncPlayer(player);
    }

    public static void onPlayerLogin(ServerPlayer player) {
        forceSync(player, true);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        ServerState state = serverState;
        if (state.belongsTo(player)) {
            SERVER_LIFECYCLE.removeTracker(state.lifecycle, player.getUUID());
        }
    }

    public static void onPlayerRespawn(ServerPlayer player) {
        forceSync(player, false);
    }

    public static void onPlayerChangedDimension(ServerPlayer player) {
        forceSync(player, false);
    }

    public static Set<String> musicIds() {
        return serverState.musicLibrary.ids();
    }

    public static int requestReload(CommandSourceStack source) {
        ServerState state = serverState;
        if (!state.ready()) {
            if (source != null) {
                source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            }
            return 0;
        }
        ServerLifecycleCoordinator.ReloadOperation<MinecraftServer> operation =
                SERVER_LIFECYCLE.beginReload(state.lifecycle);
        if (operation == null) {
            if (source != null) {
                source.sendFailure(Component.translatable("commands.areamusic.busy"));
            }
            return 0;
        }

        if (source != null) {
            source.sendSuccess(() -> Component.translatable("commands.areamusic.reload.started"), false);
        }
        CompletableFuture
                .supplyAsync(() -> loadCandidate(state.gameDirectory, state.storage))
                .whenComplete((candidate, throwable) -> operation.server().execute(
                        () -> finishReload(state, operation, source, candidate, throwable)
                ));
        return 1;
    }

    public static int createArea(
            CommandSourceStack source,
            String areaId,
            BlockPos pos1,
            BlockPos pos2,
            String musicId
    ) {
        ServerState state = serverState;
        if (!state.ready()) {
            source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            return 0;
        }
        if (SERVER_LIFECYCLE.createBusy(state.lifecycle, areaId)) {
            source.sendFailure(Component.translatable("commands.areamusic.busy"));
            return 0;
        }
        if (!Level.isInSpawnableBounds(pos1) || !Level.isInSpawnableBounds(pos2)
                || !source.getLevel().isInWorldBounds(pos1)
                || !source.getLevel().isInWorldBounds(pos2)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.out_of_bounds"));
            return 0;
        }
        if (!state.musicLibrary.contains(musicId)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.unknown_music", musicId));
            return 0;
        }
        if (state.areas.stream().anyMatch(area -> area.id().equals(areaId))) {
            source.sendFailure(Component.translatable("commands.areamusic.create.exists", areaId));
            return 0;
        }

        AreaDefinition area;
        try {
            area = AreaDefinition.create(
                    areaId,
                    source.getLevel().dimension().location().toString(),
                    new AreaPosition(pos1.getX(), pos1.getY(), pos1.getZ()),
                    new AreaPosition(pos2.getX(), pos2.getY(), pos2.getZ()),
                    List.of(new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000)),
                    false,
                    0
            );
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable(
                    "commands.areamusic.create.invalid",
                    exception.getMessage()
            ));
            return 0;
        }

        ServerLifecycleCoordinator.CreateOperation<MinecraftServer> operation =
                SERVER_LIFECYCLE.beginCreate(state.lifecycle, areaId);
        if (operation == null) {
            source.sendFailure(Component.translatable("commands.areamusic.busy"));
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("commands.areamusic.create.started", areaId),
                false
        );
        CompletableFuture
                .runAsync(() -> writeArea(state.storage, area))
                .whenComplete((ignored, throwable) -> operation.server().execute(
                        () -> finishCreate(state, operation, source, area, throwable)
                ));
        return 1;
    }

    static Path storageDirectory(Path configDirectory, Path worldRoot) {
        Path normalizedConfig = Objects.requireNonNull(configDirectory, "configDirectory")
                .toAbsolutePath()
                .normalize();
        Path normalizedWorld = Objects.requireNonNull(worldRoot, "worldRoot")
                .toAbsolutePath()
                .normalize();
        Path fileName = normalizedWorld.getFileName();
        String worldDirectoryName = fileName == null ? "world" : fileName.toString();
        return AreaStorage.directory(normalizedConfig, AreaStorage.saveId(worldDirectoryName));
    }

    static ReloadCandidate loadCandidate(Path gameDirectory, AreaStorage areaStorage) {
        try {
            Path root = MusicDirectory.prepare(gameDirectory);
            MusicLibrary candidateLibrary = MusicLibrary.scan(root);
            List<AreaDefinition> candidateAreas = areaStorage.load(candidateLibrary);
            return new ReloadCandidate(candidateLibrary, candidateAreas);
        } catch (Exception exception) {
            throw new ReloadFailure(exception);
        }
    }

    private static void finishReload(
            ServerState state,
            ServerLifecycleCoordinator.ReloadOperation<MinecraftServer> operation,
            CommandSourceStack source,
            ReloadCandidate candidate,
            Throwable throwable
    ) {
        if (!state.owns(operation.lifecycle())) {
            return;
        }
        ServerLifecycleCoordinator.Completion<MinecraftServer, NetworkSender> completion =
                SERVER_LIFECYCLE.finishReload(operation);
        if (completion == null) {
            return;
        }
        if (throwable != null) {
            Throwable cause = rootCause(throwable);
            LOGGER.error("Failed to reload AreaMusic", cause);
            if (source != null) {
                source.sendFailure(Component.translatable(
                        "commands.areamusic.reload.failed",
                        message(cause)
                ));
            }
            return;
        }

        state.musicLibrary = candidate.musicLibrary();
        state.areas = candidate.areas();
        state.revision++;
        SERVER_LIFECYCLE.clearTrackers(completion.lifecycle());
        for (ServerPlayer player : completion.server().getPlayerList().getPlayers()) {
            completion.sender().sendReload(player, state.revision);
            syncPlayerWithSender(state, player, completion.sender());
        }
        LOGGER.info(
                "Loaded {} AreaMusic tracks and {} areas",
                state.musicLibrary.ids().size(),
                state.areas.size()
        );
        if (source != null) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "commands.areamusic.reload.success",
                            state.musicLibrary.ids().size(),
                            state.areas.size()
                    ),
                    true
            );
        }
    }

    private static void writeArea(AreaStorage areaStorage, AreaDefinition area) {
        try {
            areaStorage.create(area);
        } catch (Exception exception) {
            throw new ReloadFailure(exception);
        }
    }

    private static void finishCreate(
            ServerState state,
            ServerLifecycleCoordinator.CreateOperation<MinecraftServer> operation,
            CommandSourceStack source,
            AreaDefinition area,
            Throwable throwable
    ) {
        if (!state.owns(operation.lifecycle())) {
            return;
        }
        ServerLifecycleCoordinator.Completion<MinecraftServer, NetworkSender> completion =
                SERVER_LIFECYCLE.finishCreate(operation);
        if (completion == null) {
            return;
        }
        if (throwable != null) {
            Throwable cause = rootCause(throwable);
            LOGGER.error("Failed to create AreaMusic area {}", area.id(), cause);
            source.sendFailure(Component.translatable(
                    "commands.areamusic.create.failed",
                    area.id(),
                    message(cause)
            ));
            return;
        }

        List<AreaDefinition> updated = new ArrayList<>(state.areas);
        updated.add(area);
        updated.sort((left, right) -> left.id().compareTo(right.id()));
        state.areas = List.copyOf(updated);
        state.revision++;
        SERVER_LIFECYCLE.clearTrackers(completion.lifecycle());
        for (ServerPlayer player : completion.server().getPlayerList().getPlayers()) {
            syncPlayerWithSender(state, player, completion.sender());
        }
        source.sendSuccess(
                () -> Component.translatable("commands.areamusic.create.success", area.id()),
                true
        );
    }

    private static void forceSync(ServerPlayer player, boolean reloadClientLibrary) {
        ServerState state = serverState;
        if (!state.belongsTo(player) || !SERVER_LIFECYCLE.owns(state.lifecycle)) {
            return;
        }
        SERVER_LIFECYCLE.removeTracker(state.lifecycle, player.getUUID());
        NetworkSender sender = SERVER_LIFECYCLE.senderFor(state.lifecycle);
        if (reloadClientLibrary) {
            sender.sendReload(player, state.revision);
        }
        syncPlayerWithSender(state, player, sender);
    }

    private static void syncPlayer(ServerPlayer player) {
        ServerState state = serverState;
        syncPlayerWithSender(state, player, SERVER_LIFECYCLE.senderFor(state.lifecycle));
    }

    private static void syncPlayerWithSender(
            ServerState state,
            ServerPlayer player,
            NetworkSender sender
    ) {
        if (!state.belongsTo(player)) {
            return;
        }
        PlayerAreaTracker tracker = SERVER_LIFECYCLE.tracker(
                state.lifecycle,
                player.getUUID(),
                PlayerAreaTracker::new
        );
        if (tracker == null) {
            return;
        }
        long currentRevision = state.revision;
        List<AreaDefinition> currentAreas = state.areas;
        tracker.update(
                player.level().dimension().location(),
                player.blockPosition(),
                currentRevision,
                currentAreas
        ).ifPresent(playback -> sender.sendPlayback(player, currentRevision, playback));
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isBlank()
                ? throwable.getClass().getSimpleName()
                : message;
    }

    private static final class ServerState {
        private final ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle;
        private final Path gameDirectory;
        private final AreaStorage storage;
        private volatile MusicLibrary musicLibrary;
        private volatile List<AreaDefinition> areas;
        private volatile long revision;

        private ServerState(
                ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle,
                Path gameDirectory,
                AreaStorage storage,
                MusicLibrary musicLibrary
        ) {
            this.lifecycle = lifecycle;
            this.gameDirectory = gameDirectory;
            this.storage = storage;
            this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
            areas = List.of();
        }

        private static ServerState inactive() {
            return new ServerState(
                    null,
                    null,
                    null,
                    MusicLibrary.empty(MusicDirectory.canonicalPath(Path.of(".")))
            );
        }

        private static ServerState started(
                ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle,
                Path gameDirectory,
                AreaStorage storage
        ) {
            return new ServerState(
                    Objects.requireNonNull(lifecycle, "lifecycle"),
                    Objects.requireNonNull(gameDirectory, "gameDirectory"),
                    Objects.requireNonNull(storage, "storage"),
                    MusicLibrary.empty(MusicDirectory.canonicalPath(gameDirectory))
            );
        }

        private boolean ready() {
            return lifecycle != null;
        }

        private boolean owns(ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle) {
            return this.lifecycle == lifecycle;
        }

        private boolean belongsTo(ServerPlayer player) {
            return ready() && lifecycle.server() == player.server;
        }
    }

    record ReloadCandidate(MusicLibrary musicLibrary, List<AreaDefinition> areas) {
        ReloadCandidate {
            areas = List.copyOf(areas);
        }
    }

    private static final class ReloadFailure extends RuntimeException {
        private ReloadFailure(Throwable cause) {
            super(cause);
        }
    }
}
