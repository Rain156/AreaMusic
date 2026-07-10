package datura.areamusic.client;

import com.mojang.logging.LogUtils;
import datura.areamusic.AreaMusic;
import datura.areamusic.client.audio.PcmAudioMixer;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public final class ClientAreaMusic implements AreaMusicNetwork.ClientHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static ClientAreaMusic instance;

    private final Path musicRoot;
    private final PcmAudioMixer mixer;
    private final Set<String> reportedErrors = ConcurrentHashMap.newKeySet();
    private final AtomicLong scanGeneration = new AtomicLong();
    private volatile PlaybackState desiredState = PlaybackState.stopped();
    private volatile long latestRevision = -1L;

    private ClientAreaMusic(Path musicRoot, MusicLibrary initialLibrary) {
        this.musicRoot = musicRoot;
        mixer = new PcmAudioMixer(initialLibrary, this::onAudioError);
    }

    public static synchronized void initialize() {
        if (instance != null) {
            return;
        }
        Path root = FMLPaths.GAMEDIR.get().resolve("AreaMusic").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (Exception exception) {
            LOGGER.error("Could not create the client AreaMusic directory during startup", exception);
        }

        ClientAreaMusic created = new ClientAreaMusic(root, MusicLibrary.empty(root));
        instance = created;
        AreaMusicNetwork.setClientHandler(created);
        created.mixer.start();
        created.reloadLocalLibrary();
    }

    @Override
    public void onPlayback(long revision, PlaybackState state) {
        if (revision < latestRevision) {
            return;
        }
        latestRevision = revision;
        desiredState = state;
        mixer.apply(state);
    }

    @Override
    public void onReload(long revision) {
        if (revision < latestRevision) {
            return;
        }
        latestRevision = revision;
        reloadLocalLibrary();
    }

    private void reloadLocalLibrary() {
        long generation = scanGeneration.incrementAndGet();
        CompletableFuture
                .supplyAsync(() -> scan(musicRoot))
                .whenComplete((library, throwable) -> Minecraft.getInstance().execute(
                        () -> finishLocalReload(generation, library, throwable)
                ));
    }

    private void finishLocalReload(long generation, MusicLibrary library, Throwable throwable) {
        if (generation != scanGeneration.get() || instance != this) {
            return;
        }
        if (throwable != null) {
            onAudioError("scan", rootCause(throwable));
            return;
        }
        reportedErrors.clear();
        mixer.updateMusicLibrary(library);
        mixer.apply(desiredState);
        LOGGER.info("Loaded {} local AreaMusic tracks", library.ids().size());
    }

    private static MusicLibrary scan(Path root) {
        try {
            return MusicLibrary.scan(root);
        } catch (Exception exception) {
            throw new LocalScanFailure(exception);
        }
    }

    private void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        float master = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
        float music = minecraft.options.getSoundSourceVolume(SoundSource.MUSIC);
        mixer.setMasterGain(master * music);
        mixer.setPaused(minecraft.isPaused());
    }

    private void onConnected() {
        reloadLocalLibrary();
    }

    private void onDisconnected() {
        scanGeneration.incrementAndGet();
        latestRevision = -1L;
        desiredState = PlaybackState.stopped();
        mixer.apply(desiredState);
    }

    private synchronized void shutdown() {
        scanGeneration.incrementAndGet();
        AreaMusicNetwork.clearClientHandler();
        mixer.close();
        if (instance == this) {
            instance = null;
        }
    }

    private void onAudioError(String code, Throwable error) {
        Throwable cause = rootCause(error);
        LOGGER.error("AreaMusic client audio error ({})", code, cause);
        String detail = message(cause);
        Minecraft.getInstance().execute(() -> {
            String errorKey = code + ':' + detail;
            if (!reportedErrors.add(errorKey)) {
                return;
            }
            if (Minecraft.getInstance().player != null) {
                Minecraft.getInstance().player.displayClientMessage(
                        Component.translatable("message.areamusic.audio_error", detail),
                        false
                );
            }
        });
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
