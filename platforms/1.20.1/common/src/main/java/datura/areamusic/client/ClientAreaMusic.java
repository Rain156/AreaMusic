package datura.areamusic.client;

import com.mojang.logging.LogUtils;
import datura.areamusic.client.audio.AudioFailure;
import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.client.audio.PcmAudioMixer;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.DoubleSupplier;

/** Owns client playback; its public lifecycle methods are client-thread confined. */
public final class ClientAreaMusic implements ClientPacketHandler {
    private static final Logger LOGGER = LogUtils.getLogger();

    private final Path gameDirectory;
    private final DoubleSupplier independentVolume;
    private final ClientRuntime clientRuntime;
    private final Executor scanExecutor;
    private final LibraryScanner libraryScanner;
    private final ClientPlaybackSession playbackSession;
    private final Set<String> reportedErrors = ConcurrentHashMap.newKeySet();
    private final AtomicLong scanGeneration = new AtomicLong();
    private volatile boolean closed;

    public ClientAreaMusic(Path gameDirectory, DoubleSupplier independentVolume) {
        this(
                gameDirectory,
                independentVolume,
                new MinecraftClientRuntime(),
                ForkJoinPool.commonPool(),
                ClientAreaMusic::scan,
                PcmAudioMixer::new
        );
    }

    ClientAreaMusic(
            Path gameDirectory,
            DoubleSupplier independentVolume,
            ClientRuntime clientRuntime,
            Executor scanExecutor,
            LibraryScanner libraryScanner,
            AudioMixerFactory mixerFactory
    ) {
        this.gameDirectory = Objects.requireNonNull(gameDirectory, "gameDirectory")
                .toAbsolutePath()
                .normalize();
        this.independentVolume = Objects.requireNonNull(independentVolume, "independentVolume");
        this.clientRuntime = Objects.requireNonNull(clientRuntime, "clientRuntime");
        this.scanExecutor = Objects.requireNonNull(scanExecutor, "scanExecutor");
        this.libraryScanner = Objects.requireNonNull(libraryScanner, "libraryScanner");
        AudioMixerFactory checkedMixerFactory = Objects.requireNonNull(mixerFactory, "mixerFactory");
        playbackSession = new ClientPlaybackSession(
                MusicLibrary.empty(MusicDirectory.canonicalPath(this.gameDirectory)),
                library -> checkedMixerFactory.create(library, this::onAudioError)
        );
        reloadLocalLibrary();
    }

    @Override
    public void onPlayback(long revision, PlaybackState state) {
        if (!closed) {
            playbackSession.apply(revision, state);
        }
    }

    @Override
    public void onReload(long revision) {
        if (!closed && playbackSession.beginReload(revision)) {
            reloadLocalLibrary();
        }
    }

    public void tick() {
        if (closed) {
            return;
        }
        playbackSession.setMasterGain(AreaMusicVolume.effectiveGain(
                clientRuntime.masterVolume(),
                independentVolume.getAsDouble()
        ));
        playbackSession.setPaused(clientRuntime.paused());
    }

    public void connect() {
        if (closed) {
            return;
        }
        playbackSession.connect();
        reloadLocalLibrary();
    }

    public void disconnect() {
        if (closed) {
            return;
        }
        scanGeneration.incrementAndGet();
        playbackSession.disconnect();
    }

    public synchronized void shutdown() {
        if (closed) {
            return;
        }
        closed = true;
        scanGeneration.incrementAndGet();
        playbackSession.close();
    }

    private void reloadLocalLibrary() {
        if (closed) {
            return;
        }
        long generation = scanGeneration.incrementAndGet();
        CompletableFuture
                .supplyAsync(() -> libraryScanner.scan(gameDirectory), scanExecutor)
                .whenComplete((library, throwable) -> clientRuntime.execute(
                        () -> finishLocalReload(generation, library, throwable)
                ));
    }

    private void finishLocalReload(long generation, MusicLibrary library, Throwable throwable) {
        if (closed || generation != scanGeneration.get()) {
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

    private void onAudioError(AudioFailure failure) {
        if (closed || !reportedErrors.add(failure.deduplicationKey())) {
            return;
        }
        Throwable cause = rootCause(failure.cause());
        LOGGER.error(
                "AreaMusic client audio error ({}, MusicID '{}')",
                failure.kind(),
                failure.musicId(),
                cause
        );
        clientRuntime.execute(() -> clientRuntime.displayClientMessage(errorMessage(failure)));
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

    interface ClientRuntime {
        void execute(Runnable task);

        float masterVolume();

        boolean paused();

        void displayClientMessage(Component message);
    }

    @FunctionalInterface
    interface LibraryScanner {
        MusicLibrary scan(Path gameDirectory);
    }

    @FunctionalInterface
    interface AudioMixerFactory {
        ClientAudioMixer create(
                MusicLibrary musicLibrary,
                PcmAudioMixer.ErrorListener errorListener
        );
    }

    private static final class MinecraftClientRuntime implements ClientRuntime {
        @Override
        public void execute(Runnable task) {
            Minecraft.getInstance().execute(task);
        }

        @Override
        public float masterVolume() {
            return Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MASTER);
        }

        @Override
        public boolean paused() {
            return Minecraft.getInstance().isPaused();
        }

        @Override
        public void displayClientMessage(Component message) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(message, false);
            }
        }
    }

    private static final class LocalScanFailure extends RuntimeException {
        private LocalScanFailure(Throwable cause) {
            super(cause);
        }
    }
}
