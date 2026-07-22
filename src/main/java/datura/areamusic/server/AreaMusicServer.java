package datura.areamusic.server;

import com.mojang.logging.LogUtils;
import datura.areamusic.AreaMusic;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.area.AreaStorage;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.network.AreaMusicNetwork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Mod.EventBusSubscriber(modid = AreaMusic.MOD_ID)
public final class AreaMusicServer {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<UUID, PlayerAreaTracker> PLAYER_TRACKERS = new HashMap<>();
    private static final Set<String> CREATE_IN_FLIGHT = new HashSet<>();

    private static volatile MinecraftServer server;
    private static volatile MusicLibrary musicLibrary = MusicLibrary.empty(
            MusicDirectory.canonicalPath(Path.of("."))
    );
    private static volatile List<AreaDefinition> areas = List.of();
    private static volatile long revision;
    private static Path gameDirectory;
    private static AreaStorage storage;
    private static boolean reloadInFlight;

    private AreaMusicServer() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        AreaMusicCommands.register(event.getDispatcher());
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        start(event.getServer());
    }

    @SubscribeEvent
    public static void onServerStopping(ServerStoppingEvent event) {
        if (server == event.getServer()) {
            stop();
        }
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.player instanceof ServerPlayer player) {
            syncPlayer(player);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            forceSync(player, true);
        }
    }

    @SubscribeEvent
    public static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            forceSync(player, false);
        }
    }

    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            forceSync(player, false);
        }
    }

    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        PLAYER_TRACKERS.remove(event.getEntity().getUUID());
    }

    public static Set<String> musicIds() {
        return musicLibrary.ids();
    }

    public static int requestReload(CommandSourceStack source) {
        MinecraftServer currentServer = server;
        AreaStorage currentStorage = storage;
        Path currentGameDirectory = gameDirectory;
        if (currentServer == null || currentStorage == null || currentGameDirectory == null) {
            if (source != null) {
                source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            }
            return 0;
        }
        if (reloadInFlight || !CREATE_IN_FLIGHT.isEmpty()) {
            if (source != null) {
                source.sendFailure(Component.translatable("commands.areamusic.busy"));
            }
            return 0;
        }

        reloadInFlight = true;
        if (source != null) {
            source.sendSuccess(() -> Component.translatable("commands.areamusic.reload.started"), false);
        }
        CompletableFuture
                .supplyAsync(() -> loadCandidate(currentGameDirectory, currentStorage))
                .whenComplete((candidate, throwable) -> currentServer.execute(
                        () -> finishReload(currentServer, source, candidate, throwable)
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
        MinecraftServer currentServer = server;
        AreaStorage currentStorage = storage;
        if (currentServer == null || currentStorage == null) {
            source.sendFailure(Component.translatable("commands.areamusic.not_ready"));
            return 0;
        }
        if (reloadInFlight || CREATE_IN_FLIGHT.contains(areaId)) {
            source.sendFailure(Component.translatable("commands.areamusic.busy"));
            return 0;
        }
        if (!Level.isInSpawnableBounds(pos1) || !Level.isInSpawnableBounds(pos2)
                || !source.getLevel().isInWorldBounds(pos1) || !source.getLevel().isInWorldBounds(pos2)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.out_of_bounds"));
            return 0;
        }
        if (!musicLibrary.contains(musicId)) {
            source.sendFailure(Component.translatable("commands.areamusic.create.unknown_music", musicId));
            return 0;
        }
        if (areas.stream().anyMatch(area -> area.id().equals(areaId))) {
            source.sendFailure(Component.translatable("commands.areamusic.create.exists", areaId));
            return 0;
        }

        AreaDefinition area;
        try {
            area = AreaDefinition.create(
                    areaId,
                    source.getLevel().dimension().location(),
                    pos1,
                    pos2,
                    List.of(new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000)),
                    false,
                    0
            );
        } catch (IllegalArgumentException exception) {
            source.sendFailure(Component.translatable("commands.areamusic.create.invalid", exception.getMessage()));
            return 0;
        }

        CREATE_IN_FLIGHT.add(areaId);
        source.sendSuccess(() -> Component.translatable("commands.areamusic.create.started", areaId), false);
        CompletableFuture
                .runAsync(() -> writeArea(currentStorage, area))
                .whenComplete((ignored, throwable) -> currentServer.execute(
                        () -> finishCreate(currentServer, source, area, throwable)
                ));
        return 1;
    }

    private static void start(MinecraftServer startedServer) {
        server = startedServer;
        gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        Path worldRoot = startedServer.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path fileName = worldRoot.getFileName();
        String worldDirectoryName = fileName == null ? "world" : fileName.toString();
        String saveId = AreaStorage.saveId(worldDirectoryName);
        storage = new AreaStorage(AreaStorage.directory(FMLPaths.CONFIGDIR.get(), saveId), new AreaJsonCodec());
        musicLibrary = MusicLibrary.empty(MusicDirectory.canonicalPath(gameDirectory));
        areas = List.of();
        revision = 0L;
        PLAYER_TRACKERS.clear();
        CREATE_IN_FLIGHT.clear();
        reloadInFlight = false;
        requestReload(null);
    }

    private static void stop() {
        server = null;
        gameDirectory = null;
        storage = null;
        musicLibrary = MusicLibrary.empty(MusicDirectory.canonicalPath(FMLPaths.GAMEDIR.get()));
        areas = List.of();
        revision = 0L;
        PLAYER_TRACKERS.clear();
        CREATE_IN_FLIGHT.clear();
        reloadInFlight = false;
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
            MinecraftServer expectedServer,
            CommandSourceStack source,
            ReloadCandidate candidate,
            Throwable throwable
    ) {
        if (server != expectedServer) {
            return;
        }
        reloadInFlight = false;
        if (throwable != null) {
            Throwable cause = rootCause(throwable);
            LOGGER.error("Failed to reload AreaMusic", cause);
            if (source != null) {
                source.sendFailure(Component.translatable("commands.areamusic.reload.failed", message(cause)));
            }
            return;
        }

        musicLibrary = candidate.musicLibrary();
        areas = candidate.areas();
        revision++;
        PLAYER_TRACKERS.clear();
        for (ServerPlayer player : expectedServer.getPlayerList().getPlayers()) {
            AreaMusicNetwork.sendReload(player, revision);
            syncPlayer(player);
        }
        LOGGER.info("Loaded {} AreaMusic tracks and {} areas", musicLibrary.ids().size(), areas.size());
        if (source != null) {
            source.sendSuccess(
                    () -> Component.translatable(
                            "commands.areamusic.reload.success", musicLibrary.ids().size(), areas.size()
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
            MinecraftServer expectedServer,
            CommandSourceStack source,
            AreaDefinition area,
            Throwable throwable
    ) {
        CREATE_IN_FLIGHT.remove(area.id());
        if (server != expectedServer) {
            return;
        }
        if (throwable != null) {
            Throwable cause = rootCause(throwable);
            LOGGER.error("Failed to create AreaMusic area {}", area.id(), cause);
            source.sendFailure(Component.translatable("commands.areamusic.create.failed", area.id(), message(cause)));
            return;
        }

        List<AreaDefinition> updated = new ArrayList<>(areas);
        updated.add(area);
        updated.sort((left, right) -> left.id().compareTo(right.id()));
        areas = List.copyOf(updated);
        revision++;
        PLAYER_TRACKERS.clear();
        for (ServerPlayer player : expectedServer.getPlayerList().getPlayers()) {
            syncPlayer(player);
        }
        source.sendSuccess(() -> Component.translatable("commands.areamusic.create.success", area.id()), true);
    }

    private static void forceSync(ServerPlayer player, boolean reloadClientLibrary) {
        if (server == null || player.server != server) {
            return;
        }
        PLAYER_TRACKERS.remove(player.getUUID());
        if (reloadClientLibrary) {
            AreaMusicNetwork.sendReload(player, revision);
        }
        syncPlayer(player);
    }

    private static void syncPlayer(ServerPlayer player) {
        if (server == null || player.server != server) {
            return;
        }
        PlayerAreaTracker tracker = PLAYER_TRACKERS.computeIfAbsent(
                player.getUUID(), ignored -> new PlayerAreaTracker()
        );
        tracker.update(
                player.level().dimension().location(),
                player.blockPosition(),
                revision,
                areas
        ).ifPresent(state -> AreaMusicNetwork.sendPlayback(player, revision, state));
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
        return message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message;
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
