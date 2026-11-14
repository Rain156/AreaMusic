package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.logging.LogUtils;
import datura.areamusic.area.AreaBox;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaStorage;
import datura.areamusic.area.WorldMoment;
import datura.areamusic.area.LegacyAreaMigration;
import datura.areamusic.edit.AreaEditService;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.EditResult;
import datura.areamusic.edit.RejectReason;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.net.ServerConfig;
import datura.areamusic.net.ServerSync;
import datura.areamusic.net.SyncCodec;
import datura.areamusic.net.SyncMessage;
import datura.areamusic.playback.PlaybackState;
import datura.areamusic.sync.IncomingTransfer;
import datura.areamusic.sync.MusicFingerprinter;
import datura.areamusic.sync.MusicManifest;
import datura.areamusic.sync.UploadFinisher;
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
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

        @Override
        public void sendSync(ServerPlayer player, byte[] data) {
        }
    };
    private static final int LIBRARY_REFRESH_INTERVAL_TICKS = 100;
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
    private static final MusicFingerprinter FINGERPRINTER = new MusicFingerprinter();
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
        ServerConfig config = ServerConfig.load(
                normalizedConfigDirectory.resolve(ServerConfig.FILE_NAME));
        config.warnings().forEach(warning -> LOGGER.warn("AreaMusic server config: {}", warning));
        serverState = ServerState.started(
                lifecycle,
                normalizedGameDirectory,
                startedStorage,
                legacyStorageDirectory(normalizedConfigDirectory, worldRoot),
                worldRoot.resolve("areamusic").resolve("uploads"),
                config
        );
        requestReload(null);
    }

    public static synchronized void onServerStopping(MinecraftServer stoppingServer) {
        if (!SERVER_LIFECYCLE.stop(stoppingServer)) {
            return;
        }
        ServerState stopped = serverState;
        serverState = ServerState.inactive();
        stopped.sync.close();
        stopped.finishPersistence();
    }

    public static void onPlayerEndTick(ServerPlayer player) {
        syncPlayer(player);
        ServerState state = serverState;
        if (state.belongsTo(player)) {
            state.tickOncePerServerTick();
        }
    }

    public static void onPlayerLogin(ServerPlayer player) {
        ServerState state = serverState;
        if (state.belongsTo(player)) {
            // Some loaders report the login before the player is in the server's own list.
            state.online.put(player.getUUID(), player);
            state.sync.onJoin(player.getUUID());
        }
        forceSync(player, true);
    }

    public static void onPlayerLogout(ServerPlayer player) {
        ServerState state = serverState;
        if (state.belongsTo(player)) {
            SERVER_LIFECYCLE.removeTracker(state.lifecycle, player.getUUID());
            state.sync.onLeave(player.getUUID());
            state.online.remove(player.getUUID());
        }
    }

    /** Handles the bytes of one serverbound {@code SyncMessage}. Call on the server thread. */
    public static void onSyncMessage(ServerPlayer player, byte[] data) {
        ServerState state = serverState;
        if (!state.belongsTo(player)) {
            return;
        }
        SyncMessage.Serverbound message;
        try {
            message = SyncCodec.decodeServerbound(data);
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignoring malformed AreaMusic message from {}", player.getGameProfile().getName());
            return;
        }
        state.sync.onMessage(player.getUUID(), message);
    }

    public static void onPlayerRespawn(ServerPlayer player) {
        ServerState state = serverState;
        if (state.belongsTo(player) && state.online.containsKey(player.getUUID())) {
            // Respawning replaces the player object.
            state.online.put(player.getUUID(), player);
        }
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
        source.sendSuccess(() -> CommandReplies.created(area), true);
        return 1;
    }

    public static int deleteArea(CommandSourceStack source, String areaId) {
        ServerState state = editableState(source);
        if (state == null) {
            return 0;
        }
        String label = state.edits.find(areaId).map(AreaDefinition::label).orElse(areaId);
        EditResult result = applyEdit(state, new EditOp.Remove(areaId));
        if (!result.applied()) {
            reportRejection(source, result);
            return 0;
        }
        source.sendSuccess(() -> CommandReplies.deleted(label), true);
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
        } catch (AreaMusicCommands.InvalidValue exception) {
            source.sendFailure(Component.translatable(exception.translationKey()));
            return 0;
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable(
                    "commands.areamusic.edit.invalid",
                    exception.getMessage()
            ));
            return 0;
        }
        boolean boundsChanged = !updated.boxes().equals(current.boxes());
        if (boundsChanged && !inBounds(state, updated)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.out_of_bounds"));
            return 0;
        }

        EditResult result = applyEdit(state, new EditOp.Put(updated));
        if (!result.applied()) {
            reportRejection(source, result);
            return 0;
        }
        source.sendSuccess(() -> CommandReplies.updated(current, updated), result.changed());
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
        for (Component line : CommandReplies.list(areas, state.musicLibrary)) {
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
        List<Component> lines = CommandReplies.info(area, state.musicLibrary);
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
        if (area.wholeDimension()) {
            source.sendFailure(Component.translatable("commands.areamusic.tp.whole_dimension"));
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
        int centerX = Math.floorDiv(min.x() + max.x(), 2);
        int centerZ = Math.floorDiv(min.z() + max.z(), 2);
        // Stand on the ground inside the area when there is some, so the music starts at once
        // and nobody is dropped from the top of a tall area.
        int surface = level.getChunk(centerX >> 4, centerZ >> 4)
                .getHeight(Heightmap.Types.MOTION_BLOCKING, centerX & 15, centerZ & 15) + 1;
        int feet = Math.max(min.y(), Math.min(max.y(), surface));
        player.teleportTo(
                level,
                centerX + 0.5,
                feet,
                centerZ + 0.5,
                player.getYRot(),
                player.getXRot()
        );
        source.sendSuccess(() -> CommandReplies.teleported(area), false);
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
        // Trackers are kept: each one re-resolves for the new revision and only sends a player
        // a new playback state if theirs actually changed, so unrelated edits never restart music.
        NetworkSender sender = SERVER_LIFECYCLE.senderFor(state.lifecycle);
        for (ServerPlayer player : state.lifecycle.server().getPlayerList().getPlayers()) {
            syncPlayerWithSender(state, player, sender);
        }
        state.sync.areaChanged(op, result.revision());
        return result;
    }

    /** An edit from an open editor: the same checks a command gets, then {@link #applyEdit}. */
    private static EditResult applyEditorEdit(ServerState state, EditOp op) {
        if (SERVER_LIFECYCLE.reloadInFlight()) {
            return EditResult.rejected(RejectReason.BUSY, op.areaId(), state.edits.revision());
        }
        if (op instanceof EditOp.Put put) {
            AreaDefinition current = state.edits.find(put.area().id()).orElse(null);
            boolean boundsChanged = current == null
                    || !current.boxes().equals(put.area().boxes())
                    || !current.dimension().equals(put.area().dimension());
            if (boundsChanged && !inBounds(state, put.area())) {
                return EditResult.rejected(
                        RejectReason.OUT_OF_BOUNDS, put.area().id(), state.edits.revision());
            }
        }
        return applyEdit(state, op);
    }

    private static void reportRejection(CommandSourceStack source, EditResult result) {
        String key = switch (result.reason()) {
            case UNKNOWN_AREA -> "commands.areamusic.unknown_area";
            case UNKNOWN_MUSIC -> "commands.areamusic.create.unknown_music";
            case TOO_MANY_AREAS -> "commands.areamusic.too_many_areas";
            case OUT_OF_BOUNDS -> "commands.areamusic.create.out_of_bounds";
            case NOT_ALLOWED, BUSY -> "commands.areamusic.busy";
        };
        source.sendFailure(Component.translatable(key, result.detail()));
    }

    private static boolean inBounds(ServerState state, AreaDefinition area) {
        ServerLevel level = levelOf(state.lifecycle.server(), area.dimension());
        // An area that covers its dimension has no boxes, so nothing of it can be out of bounds.
        for (AreaBox box : area.boxes()) {
            for (AreaPosition corner : List.of(box.pos1(), box.pos2())) {
                BlockPos pos = new BlockPos(corner.x(), corner.y(), corner.z());
                if (!Level.isInSpawnableBounds(pos) || level != null && !level.isInWorldBounds(pos)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
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
            return new ReloadCandidate(
                    candidateLibrary, loaded.areas(), skipped, FINGERPRINTER.fingerprint(candidateLibrary));
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
        state.sync.reloaded(candidate.manifest());
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
                currentAreas,
                WorldMoment.at(
                        player.level().getDayTime(), player.level().isRaining(), player.level().isThundering())
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
        private final Path uploadDirectory;
        private final ServerConfig config;
        private final ServerSync<UUID> sync;
        private final Map<UUID, ServerPlayer> online = new HashMap<>();
        private volatile MusicLibrary musicLibrary;
        private int lastTick = -1;
        private boolean libraryRefreshInFlight;

        private ServerState(
                ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle,
                Path gameDirectory,
                AreaStorage storage,
                Path legacyDirectory,
                Path uploadDirectory,
                ServerConfig config,
                MusicLibrary musicLibrary
        ) {
            this.lifecycle = lifecycle;
            this.gameDirectory = gameDirectory;
            this.storage = storage;
            this.legacyDirectory = legacyDirectory;
            this.uploadDirectory = uploadDirectory;
            this.config = config;
            sync = new ServerSync<>(new SyncHost(this), config.sync());
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
                    null,
                    ServerConfig.DEFAULT,
                    MusicLibrary.empty(MusicDirectory.canonicalPath(Path.of(".")))
            );
        }

        private static ServerState started(
                ServerLifecycleCoordinator.Lifecycle<MinecraftServer> lifecycle,
                Path gameDirectory,
                AreaStorage storage,
                Path legacyDirectory,
                Path uploadDirectory,
                ServerConfig config
        ) {
            return new ServerState(
                    Objects.requireNonNull(lifecycle, "lifecycle"),
                    Objects.requireNonNull(gameDirectory, "gameDirectory"),
                    Objects.requireNonNull(storage, "storage"),
                    Objects.requireNonNull(legacyDirectory, "legacyDirectory"),
                    Objects.requireNonNull(uploadDirectory, "uploadDirectory"),
                    Objects.requireNonNull(config, "config"),
                    MusicLibrary.empty(MusicDirectory.canonicalPath(gameDirectory))
            );
        }

        /** Runs the per-tick work once, however many players report their tick. */
        private void tickOncePerServerTick() {
            int tick = lifecycle.server().getTickCount();
            if (tick == lastTick) {
                return;
            }
            lastTick = tick;
            sync.tick();
            if (tick % LIBRARY_REFRESH_INTERVAL_TICKS != 0) {
                return;
            }
            sync.refreshPermissions();
            if (libraryRefreshInFlight || SERVER_LIFECYCLE.reloadInFlight()) {
                return;
            }
            // Picks up music copied into the folder by hand, so nobody has to run a reload.
            libraryRefreshInFlight = true;
            persistence.execute(() -> {
                LibraryCandidate candidate = null;
                try {
                    candidate = scanLibrary(gameDirectory);
                } catch (Exception exception) {
                    LOGGER.debug("AreaMusic library refresh failed", exception);
                }
                LibraryCandidate finalCandidate = candidate;
                lifecycle.server().execute(() -> {
                    libraryRefreshInFlight = false;
                    if (finalCandidate != null
                            && SERVER_LIFECYCLE.owns(lifecycle)
                            && !SERVER_LIFECYCLE.reloadInFlight()) {
                        applyLibrary(this, finalCandidate);
                    }
                });
            });
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

    record ReloadCandidate(
            MusicLibrary musicLibrary, List<AreaDefinition> areas, List<String> skipped, MusicManifest manifest
    ) {
        ReloadCandidate {
            areas = List.copyOf(areas);
            skipped = List.copyOf(skipped);
            Objects.requireNonNull(manifest, "manifest");
        }
    }

    private record LibraryCandidate(MusicLibrary musicLibrary, MusicManifest manifest) {
    }

    /** Connects the loader-neutral sync session to this server's players, areas and library. */
    private static final class SyncHost implements ServerSync.Host<UUID> {
        private final ServerState state;

        private SyncHost(ServerState state) {
            this.state = state;
        }

        @Override
        public void send(UUID playerId, SyncMessage.Clientbound message) {
            ServerPlayer player = state.online.get(playerId);
            if (player != null) {
                SERVER_LIFECYCLE.senderFor(state.lifecycle).sendSync(player, SyncCodec.encode(message));
            }
        }

        @Override
        public boolean canEdit(UUID playerId) {
            MinecraftServer server = state.lifecycle.server();
            ServerPlayer player = state.online.get(playerId);
            return player != null
                    && (player.hasPermissions(state.config.editPermissionLevel())
                    || server.isSingleplayerOwner(player.getGameProfile()));
        }

        @Override
        public EditResult applyEdit(UUID playerId, EditOp op) {
            return applyEditorEdit(state, op);
        }

        @Override
        public List<AreaDefinition> areas() {
            return state.edits.areas();
        }

        @Override
        public long revision() {
            return state.edits.revision();
        }

        @Override
        public MusicLibrary library() {
            return state.musicLibrary;
        }

        @Override
        public Path newUploadTemporaryPath(String extension) {
            return state.uploadDirectory.resolve("." + UUID.randomUUID() + "." + extension);
        }

        @Override
        public void finishUpload(
                UUID playerId, int uploadId, IncomingTransfer transfer, String musicIdHint
        ) {
            MusicLibrary current = state.musicLibrary;
            state.persistence.execute(() -> {
                String musicId = null;
                String rejection = null;
                LibraryCandidate candidate = null;
                try {
                    HashSet<String> taken = new HashSet<>();
                    current.ids().forEach(id -> taken.add(id.toLowerCase(Locale.ROOT)));
                    musicId = UploadFinisher.install(transfer, musicIdHint, current.root(), taken::contains);
                    candidate = scanLibrary(state.gameDirectory);
                    LOGGER.info("Stored uploaded AreaMusic track {}", musicId);
                } catch (IllegalArgumentException exception) {
                    rejection = exception.getMessage();
                } catch (Exception exception) {
                    LOGGER.error("Failed to store an uploaded AreaMusic track", exception);
                    rejection = "server_error";
                }
                String finalMusicId = musicId;
                String finalRejection = rejection;
                LibraryCandidate finalCandidate = candidate;
                state.lifecycle.server().execute(() -> {
                    if (!SERVER_LIFECYCLE.owns(state.lifecycle)) {
                        return;
                    }
                    if (finalCandidate != null) {
                        applyLibrary(state, finalCandidate);
                    }
                    state.sync.uploadFinished(playerId, uploadId, finalMusicId, finalRejection);
                });
            });
        }
    }

    private static LibraryCandidate scanLibrary(Path gameDirectory) throws Exception {
        MusicLibrary library = MusicLibrary.scan(MusicDirectory.prepare(gameDirectory));
        return new LibraryCandidate(library, FINGERPRINTER.fingerprint(library));
    }

    /** Swaps in a rescanned music library without touching areas. */
    private static void applyLibrary(ServerState state, LibraryCandidate candidate) {
        if (candidate.manifest().equals(state.sync.manifest())) {
            return;
        }
        state.musicLibrary = candidate.musicLibrary();
        state.sync.reloaded(candidate.manifest());
        LOGGER.info("AreaMusic library now has {} tracks", candidate.musicLibrary().ids().size());
    }

    private static final class ReloadFailure extends RuntimeException {
        private ReloadFailure(Throwable cause) {
            super(cause);
        }
    }
}
