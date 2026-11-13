package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaStorage;
import datura.areamusic.area.LegacyAreaMigration;
import datura.areamusic.edit.AreaEditService;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.EditResult;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;

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
        public Iterable<String> areaIds() {
            return AreaMusicServer.areaIds();
        }

        @Override
        public int deleteArea(CommandSourceStack source, String areaId) {
            return AreaMusicServer.deleteArea(source, areaId);
        }

        @Override
        public int listAreas(CommandSourceStack source) {
            return AreaMusicServer.listAreas(source);
        }

        @Override
        public int showArea(CommandSourceStack source, String areaId) {
            return AreaMusicServer.showArea(source, areaId);
        }

        @Override
        public int teleportToArea(CommandSourceStack source, String areaId) {
            return AreaMusicServer.teleportToArea(source, areaId);
        }

        @Override
        public int editArea(
                CommandSourceStack source, String areaId, UnaryOperator<AreaDefinition> change
        ) {
            return AreaMusicServer.editArea(source, areaId, change);
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
        AreaStorage startedStorage = new AreaStorage(storageDirectory(worldRoot), new AreaJsonCodec());

        ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle =
                SERVER_LIFECYCLE.start(checkedServer, checkedSender);
        serverState = ServerState.started(
                lifecycle,
                normalizedGameDirectory,
                startedStorage,
                legacyStorageDirectory(normalizedConfigDirectory, worldRoot)
        );
        requestReload(null);
    }

    public static synchronized void onServerStopping(MinecraftServer stoppingServer) {
        if (!SERVER_LIFECYCLE.stop(stoppingServer)) {
            return;
        }
        ServerState stopped = serverState;
        serverState = ServerState.inactive();
        stopped.finishPersistence();
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

    public static List<String> areaIds() {
        return serverState.edits.areas().stream().map(AreaDefinition::id).toList();
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
                .supplyAsync(
                        () -> loadCandidate(state.gameDirectory, state.storage, state.legacyDirectory),
                        state.persistence
                )
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
        if (SERVER_LIFECYCLE.reloadInFlight()) {
            source.sendFailure(Component.translatable("commands.areamusic.busy"));
            return 0;
        }
        if (!Level.isInSpawnableBounds(pos1) || !Level.isInSpawnableBounds(pos2)
                || !source.getLevel().isInWorldBounds(pos1)
                || !source.getLevel().isInWorldBounds(pos2)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.out_of_bounds"));
            return 0;
        }
        if (state.edits.contains(areaId)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.exists", areaId));
            return 0;
        }

        AreaDefinition area;
        try {
            area = AreaEdits.single(
                    areaId,
                    source.getLevel().dimension().location().toString(),
                    new AreaPosition(pos1.getX(), pos1.getY(), pos1.getZ()),
                    new AreaPosition(pos2.getX(), pos2.getY(), pos2.getZ()),
                    musicId
            );
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable(
                    "commands.areamusic.create.invalid",
                    exception.getMessage()
            ));
            return 0;
        }

        EditResult result = applyEdit(state, new EditOp.Put(area));
        if (!result.applied()) {
            reportRejection(source, result);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("commands.areamusic.create.success", area.id()),
                true
        );
        return 1;
    }

    public static int deleteArea(CommandSourceStack source, String areaId) {
        ServerState state = editableState(source);
        if (state == null) {
            return 0;
        }
        EditResult result = applyEdit(state, new EditOp.Remove(areaId));
        if (!result.applied()) {
            reportRejection(source, result);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("commands.areamusic.delete.success", areaId),
                true
        );
        return 1;
    }

    public static int editArea(
            CommandSourceStack source,
            String areaId,
            UnaryOperator<AreaDefinition> change
    ) {
        ServerState state = editableState(source);
        if (state == null) {
            return 0;
        }
        AreaDefinition current = state.edits.find(areaId).orElse(null);
        if (current == null) {
            source.sendFailure(Component.translatable("commands.areamusic.unknown_area", areaId));
            return 0;
        }
        AreaDefinition updated;
        try {
            updated = change.apply(current);
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable(
                    "commands.areamusic.edit.invalid",
                    exception.getMessage()
            ));
            return 0;
        }
        boolean boundsChanged = !updated.pos1().equals(current.pos1())
                || !updated.pos2().equals(current.pos2());
        if (boundsChanged && !inBounds(state, updated)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.out_of_bounds"));
            return 0;
        }

        EditResult result = applyEdit(state, new EditOp.Put(updated));
        if (!result.applied()) {
            reportRejection(source, result);
            return 0;
        }
        String key = result.changed()
                ? "commands.areamusic.edit.success"
                : "commands.areamusic.edit.unchanged";
        source.sendSuccess(() -> Component.translatable(key, updated.label()), result.changed());
        return 1;
    }

    public static int listAreas(CommandSourceStack source) {
        ServerState state = serverState;
        if (!state.ready()) {
            source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            return 0;
        }
        List<AreaDefinition> areas = state.edits.areas();
        if (areas.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("commands.areamusic.list.empty"), false);
            return 0;
        }
        source.sendSuccess(
                () -> Component.translatable("commands.areamusic.list.header", areas.size()),
                false
        );
        MusicLibrary library = state.musicLibrary;
        for (AreaDefinition area : areas) {
            MutableComponent line = Component.translatable(
                    "commands.areamusic.list.entry",
                    area.label(), area.id(), area.dimension(), size(area), area.musicIds().size()
            );
            if (!area.enabled()) {
                line.append(Component.translatable("commands.areamusic.list.disabled"));
            }
            if (!area.musicIds().stream().allMatch(library::contains)) {
                line.append(Component.translatable("commands.areamusic.list.missing_music"));
            }
            source.sendSuccess(() -> line, false);
        }
        return areas.size();
    }

    public static int showArea(CommandSourceStack source, String areaId) {
        ServerState state = serverState;
        if (!state.ready()) {
            source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            return 0;
        }
        AreaDefinition area = state.edits.find(areaId).orElse(null);
        if (area == null) {
            source.sendFailure(Component.translatable("commands.areamusic.unknown_area", areaId));
            return 0;
        }
        AreaPosition pos1 = area.pos1();
        AreaPosition pos2 = area.pos2();
        List<Component> lines = List.of(
                Component.translatable(
                        "commands.areamusic.info.header", area.label(), area.id(), area.dimension()),
                Component.translatable(
                        "commands.areamusic.info.bounds",
                        pos1.x(), pos1.y(), pos1.z(), pos2.x(), pos2.y(), pos2.z(), size(area)),
                Component.translatable(
                        "commands.areamusic.info.playback",
                        Component.translatable("areamusic.mode." + AreaEdits.modeOf(area).id()),
                        Math.round(AreaEdits.volumeOf(area) * 100.0f),
                        AreaEdits.fadeInOf(area),
                        AreaEdits.fadeOutOf(area)),
                Component.translatable(
                        "commands.areamusic.info.music", String.join(", ", area.musicIds())),
                Component.translatable(
                        "commands.areamusic.info.flags",
                        area.priority(), area.resumeOnReenter(), area.enabled())
        );
        for (Component line : lines) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    public static int teleportToArea(CommandSourceStack source, String areaId) {
        ServerState state = serverState;
        if (!state.ready()) {
            source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            return 0;
        }
        AreaDefinition area = state.edits.find(areaId).orElse(null);
        if (area == null) {
            source.sendFailure(Component.translatable("commands.areamusic.unknown_area", areaId));
            return 0;
        }
        ServerPlayer player = source.getPlayer();
        if (player == null) {
            source.sendFailure(Component.translatable("commands.areamusic.tp.player_only"));
            return 0;
        }
        ServerLevel level = levelOf(state.lifecycle.server(), area.dimension());
        if (level == null) {
            source.sendFailure(Component.translatable(
                    "commands.areamusic.tp.unknown_dimension", area.dimension()));
            return 0;
        }
        AreaPosition min = area.min();
        AreaPosition max = area.max();
        player.teleportTo(
                level,
                (min.x() + max.x() + 1) / 2.0,
                max.y() + 1.0,
                (min.z() + max.z() + 1) / 2.0,
                player.getYRot(),
                player.getXRot()
        );
        source.sendSuccess(
                () -> Component.translatable("commands.areamusic.tp.success", area.label()),
                false
        );
        return 1;
    }

    private static ServerState editableState(CommandSourceStack source) {
        ServerState state = serverState;
        if (!state.ready()) {
            source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            return null;
        }
        if (SERVER_LIFECYCLE.reloadInFlight()) {
            source.sendFailure(Component.translatable("commands.areamusic.busy"));
            return null;
        }
        return state;
    }

    /** Applies one edit; when it changed something, queues the disk write and resyncs players. */
    private static EditResult applyEdit(ServerState state, EditOp op) {
        EditResult result = state.edits.apply(op);
        if (!result.applied() || !result.changed()) {
            return result;
        }
        AreaStorage storage = state.storage;
        state.persistence.execute(() -> {
            try {
                if (op instanceof EditOp.Put put) {
                    storage.save(put.area());
                } else {
                    storage.delete(op.areaId());
                }
            } catch (Exception exception) {
                LOGGER.error("Failed to persist AreaMusic area {}", op.areaId(), exception);
            }
        });
        SERVER_LIFECYCLE.clearTrackers(state.lifecycle);
        NetworkSender sender = SERVER_LIFECYCLE.senderFor(state.lifecycle);
        for (ServerPlayer player : state.lifecycle.server().getPlayerList().getPlayers()) {
            syncPlayerWithSender(state, player, sender);
        }
        return result;
    }

    private static void reportRejection(CommandSourceStack source, EditResult result) {
        String key = switch (result.reason()) {
            case UNKNOWN_AREA -> "commands.areamusic.unknown_area";
            case UNKNOWN_MUSIC -> "commands.areamusic.create.unknown_music";
            case TOO_MANY_AREAS -> "commands.areamusic.too_many_areas";
        };
        source.sendFailure(Component.translatable(key, result.detail()));
    }

    private static boolean inBounds(ServerState state, AreaDefinition area) {
        ServerLevel level = levelOf(state.lifecycle.server(), area.dimension());
        for (AreaPosition corner : List.of(area.pos1(), area.pos2())) {
            BlockPos pos = new BlockPos(corner.x(), corner.y(), corner.z());
            if (!Level.isInSpawnableBounds(pos) || level != null && !level.isInWorldBounds(pos)) {
                return false;
            }
        }
        return true;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static String size(AreaDefinition area) {
        AreaPosition min = area.min();
        AreaPosition max = area.max();
        return (max.x() - min.x() + 1L) + "\u00d7" + (max.y() - min.y() + 1L)
                + "\u00d7" + (max.z() - min.z() + 1L);
    }

    static Path storageDirectory(Path worldRoot) {
        return AreaStorage.worldDirectory(Objects.requireNonNull(worldRoot, "worldRoot"));
    }

    /** Where area files lived before v2; read once by the migration and never written again. */
    static Path legacyStorageDirectory(Path configDirectory, Path worldRoot) {
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
        return loadCandidate(gameDirectory, areaStorage, null);
    }

    static ReloadCandidate loadCandidate(
            Path gameDirectory, AreaStorage areaStorage, Path legacyDirectory
    ) {
        try {
            Path root = MusicDirectory.prepare(gameDirectory);
            MusicLibrary candidateLibrary = MusicLibrary.scan(root);
            List<String> skipped = new ArrayList<>();
            if (legacyDirectory != null) {
                LegacyAreaMigration.Result migration =
                        LegacyAreaMigration.migrateIfNeeded(legacyDirectory, areaStorage);
                if (migration.ran()) {
                    LOGGER.info(
                            "Migrated {} AreaMusic area file(s) from {} to {}",
                            migration.migrated(), legacyDirectory, areaStorage.directory()
                    );
                    skipped.addAll(migration.errors());
                }
            }
            AreaStorage.LoadResult loaded = areaStorage.loadLenient();
            skipped.addAll(loaded.errors());
            return new ReloadCandidate(candidateLibrary, loaded.areas(), skipped);
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
        state.edits.replaceAll(candidate.areas());
        long revision = state.edits.revision();
        SERVER_LIFECYCLE.clearTrackers(completion.lifecycle());
        for (ServerPlayer player : completion.server().getPlayerList().getPlayers()) {
            completion.sender().sendReload(player, revision);
            syncPlayerWithSender(state, player, completion.sender());
        }
        int trackCount = state.musicLibrary.ids().size();
        int areaCount = candidate.areas().size();
        LOGGER.info("Loaded {} AreaMusic tracks and {} areas", trackCount, areaCount);
        for (String skipped : candidate.skipped()) {
            LOGGER.warn("Skipped AreaMusic area file {}", skipped);
        }
        if (source != null) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "commands.areamusic.reload.success", trackCount, areaCount),
                    true
            );
            for (String skipped : candidate.skipped()) {
                source.sendFailure(Component.translatable("commands.areamusic.reload.skipped", skipped));
            }
        }
    }

    private static void forceSync(ServerPlayer player, boolean reloadClientLibrary) {
        ServerState state = serverState;
        if (!state.belongsTo(player) || !SERVER_LIFECYCLE.owns(state.lifecycle)) {
            return;
        }
        SERVER_LIFECYCLE.removeTracker(state.lifecycle, player.getUUID());
        NetworkSender sender = SERVER_LIFECYCLE.senderFor(state.lifecycle);
        if (reloadClientLibrary) {
            sender.sendReload(player, state.edits.revision());
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
        long currentRevision = state.edits.revision();
        List<AreaDefinition> currentAreas = state.edits.areas();
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
        private final Path legacyDirectory;
        private final ExecutorService persistence;
        private final AreaEditService edits;
        private volatile MusicLibrary musicLibrary;

        private ServerState(
                ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle,
                Path gameDirectory,
                AreaStorage storage,
                Path legacyDirectory,
                MusicLibrary musicLibrary
        ) {
            this.lifecycle = lifecycle;
            this.gameDirectory = gameDirectory;
            this.storage = storage;
            this.legacyDirectory = legacyDirectory;
            this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
            // One thread, so disk writes and reload reads happen in the order they were requested.
            persistence = lifecycle == null ? null : Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "AreaMusic storage");
                thread.setDaemon(true);
                return thread;
            });
            edits = new AreaEditService(List.of(), 0L, musicId -> this.musicLibrary.contains(musicId));
        }

        private static ServerState inactive() {
            return new ServerState(
                    null,
                    null,
                    null,
                    null,
                    MusicLibrary.empty(MusicDirectory.canonicalPath(Path.of(".")))
            );
        }

        private static ServerState started(
                ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle,
                Path gameDirectory,
                AreaStorage storage,
                Path legacyDirectory
        ) {
            return new ServerState(
                    Objects.requireNonNull(lifecycle, "lifecycle"),
                    Objects.requireNonNull(gameDirectory, "gameDirectory"),
                    Objects.requireNonNull(storage, "storage"),
                    Objects.requireNonNull(legacyDirectory, "legacyDirectory"),
                    MusicLibrary.empty(MusicDirectory.canonicalPath(gameDirectory))
            );
        }

        /** Lets queued writes reach the disk before the server finishes stopping. */
        private void finishPersistence() {
            if (persistence == null) {
                return;
            }
            persistence.shutdown();
            try {
                if (!persistence.awaitTermination(10, TimeUnit.SECONDS)) {
                    LOGGER.warn("Timed out waiting for AreaMusic area files to be written");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
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

    record ReloadCandidate(MusicLibrary musicLibrary, List<AreaDefinition> areas, List<String> skipped) {
        ReloadCandidate {
            areas = List.copyOf(areas);
            skipped = List.copyOf(skipped);
        }
    }

    private static final class ReloadFailure extends RuntimeException {
        private ReloadFailure(Throwable cause) {
            super(cause);
        }
    }
}
