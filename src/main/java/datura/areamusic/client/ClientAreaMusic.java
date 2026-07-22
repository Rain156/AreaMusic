package datura.areamusic.client;

import com.mojang.logging.LogUtils;
import datura.areamusic.AreaMusic;
import datura.areamusic.client.audio.AudioFailure;
import datura.areamusic.client.audio.PcmAudioMixer;
import datura.areamusic.config.AreaMusicClientConfig;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.network.AreaMusicNetwork;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.GameShuttingDownEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientAreaMusic implements AreaMusicNetwork.ClientHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientAreaMusic instance;

    private final Path gameDirectory;
    private final ClientPlaybackSession playbackSession;
    private final Set<String> reportedErrors = ConcurrentHashMap.newKeySet();
    private final AtomicLong scanGeneration = new AtomicLong();

    private ClientAreaMusic(Path gameDirectory, MusicLibrary initialLibrary) {
        this.gameDirectory = gameDirectory;
        playbackSession = new ClientPlaybackSession(
                initialLibrary,
                library -> new PcmAudioMixer(library, this::onAudioError)
        );
    }

    public static synchronized void initialize() {
        if (instance != null) {
            return;
        }
        Path gameDirectory = FMLPaths.GAMEDIR.get().toAbsolutePath().normalize();
        ClientAreaMusic created = new ClientAreaMusic(
                gameDirectory,
                MusicLibrary.empty(MusicDirectory.canonicalPath(gameDirectory))
        );
        instance = created;
        AreaMusicNetwork.setClientHandler(created);
        created.reloadLocalLibrary();
    }

    @Override
    public void onPlayback(long revision, PlaybackState state) {
        playbackSession.apply(revision, state);
    }

    @Override
    public void onReload(long revision) {
        if (playbackSession.beginReload(revision)) {
            reloadLocalLibrary();
        }
    }

    private void reloadLocalLibrary() {
        long generation = scanGeneration.incrementAndGet();
        CompletableFuture
                .supplyAsync(() -> scan(gameDirectory))
                .whenComplete((library, throwable) -> Minecraft.getInstance().execute(
                        () -> finishLocalReload(generation, library, throwable)
                ));
    }

    private void finishLocalReload(long generation, MusicLibrary library, Throwable throwable) {
        if (generation != scanGeneration.get() || instance != this) {
            return;
        }
        if (throwable != null) {
            onAudioError(new AudioFailure(AudioFailure.Kind.SCAN, "", rootCause(throwable)));
            playbackSession.failReload();
            return;
        }
        reportedErrors.clear();
        playbackSession.finishReload(library);
        LOGGER.info("Loaded {} local AreaMusic tracks", library.ids().size());
    }

    static MusicLibrary scan(Path gameDirectory) {
        try {
            Path root = MusicDirectory.prepare(gameDirectory);
            return MusicLibrary.scan(root);
        } catch (Exception exception) {
            throw new LocalScanFailure(exception);
        }
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        float master = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        playbackSession.setMasterGain(AreaMusicVolume.effectiveGain(
                master,
                AreaMusicClientConfig.INSTANCE.volume()
        ));
        playbackSession.setPaused(minecraft.isPaused());
    }

    private void onConnected() {
        playbackSession.connect();
        reloadLocalLibrary();
    }

    private void onDisconnected() {
        scanGeneration.incrementAndGet();
        playbackSession.disconnect();
    }

    private synchronized void shutdown() {
        scanGeneration.incrementAndGet();
        AreaMusicNetwork.clearClientHandler();
        playbackSession.close();
        if (instance == this) {
            instance = null;
        }
    }

    private void onAudioError(AudioFailure failure) {
        if (!reportedErrors.add(failure.deduplicationKey())) {
            return;
        }
        Throwable cause = rootCause(failure.cause());
        LOGGER.error("AreaMusic client audio error ({}, MusicID '{}')", failure.kind(), failure.musicId(), cause);
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        errorMessage(failure),
                        false
                );
            }
        });
    }

    private static Component errorMessage(AudioFailure failure) {
        ClientErrorMessage message = ClientErrorMessage.from(failure);
        return Component.translatable(message.translationKey(), message.arguments().toArray());
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Mod.EventBusSubscriber(modid = AreaMusic.MOD_ID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.MOD)
    public static final class ModEvents {
        private ModEvents() {
        }

        @SubscribeEvent
        public static void onClientSetup(FMLClientSetupEvent event) {
            event.enqueueWork(ClientAreaMusic::initialize);
        }
    }

    @Mod.EventBusSubscriber(modid = AreaMusic.MOD_ID, value = Dist.CLIENT)
    public static final class ForgeEvents {
        private ForgeEvents() {
        }

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase == TickEvent.Phase.END && instance != null) {
                instance.tick();
            }
        }

        @SubscribeEvent
        public static void onLoggingIn(ClientPlayerNetworkEvent.LoggingIn event) {
            if (instance != null) {
                instance.onConnected();
            }
        }

        @SubscribeEvent
        public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
            if (instance != null) {
                instance.onDisconnected();
            }
        }

        @SubscribeEvent
        public static void onGameShuttingDown(GameShuttingDownEvent event) {
            if (instance != null) {
                instance.shutdown();
            }
        }
    }

    private static final class LocalScanFailure extends RuntimeException {
        private LocalScanFailure(Throwable cause) {
            super(cause);
        }
    }
}
