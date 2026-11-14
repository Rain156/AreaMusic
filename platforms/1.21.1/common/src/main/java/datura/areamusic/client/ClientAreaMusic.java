package datura.areamusic.client;

import com.mojang.logging.LogUtils;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.client.audio.AudioFailure;
import datura.areamusic.client.audio.AudioStreamFactory;
import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.client.audio.PcmAudioMixer;
import datura.areamusic.client.audio.TrackDurations;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.music.MusicDirectory;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.net.ClientSync;
import datura.areamusic.net.SyncCodec;
import datura.areamusic.net.SyncMessage;
import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;

/** Owns client playback; its public lifecycle methods are client-thread confined. */
public final class ClientAreaMusic implements ClientPacketHandler {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int PREVIEW_FADE_IN_MS = 120;
    private static final int PREVIEW_FADE_OUT_MS = 250;

    private final Path gameDirectory;
    private final DoubleSupplier independentVolume;
    private final ClientRuntime clientRuntime;
    private final Executor scanExecutor;
    private final LibraryScanner libraryScanner;
    private final ClientPlaybackSession playbackSession;
    private final Set<String> reportedErrors = ConcurrentHashMap.newKeySet();
    private final AtomicLong scanGeneration = new AtomicLong();
    private final ClientSync sync;
    /** Made on first use: it brings in the audio decoders, which not every caller needs. */
    private TrackDurations durations;
    private volatile Consumer<byte[]> syncSender = data -> { };
    private volatile boolean closed;
    private PlaybackState currentState = PlaybackState.stopped();
    private MusicLibrary activeLibrary;
    private MusicLibrary deferredLibrary;
    private int ticks;

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
        activeLibrary = MusicLibrary.empty(MusicDirectory.canonicalPath(this.gameDirectory));
        playbackSession = new ClientPlaybackSession(
                activeLibrary,
                library -> checkedMixerFactory.create(library, this::onAudioError)
        );
        sync = new ClientSync(
                new ClientSync.Host() {
                    @Override
                    public void send(SyncMessage.Serverbound message) {
                        sendSync(message);
                    }

                    @Override
                    public void libraryChanged(MusicLibrary library) {
                        onLibraryChanged(library);
                    }
                },
                MusicDirectory.canonicalPath(this.gameDirectory),
                this.gameDirectory.resolve("areamusic-cache"),
                scanExecutor,
                clientRuntime::execute
        );
        reloadLocalLibrary();
    }

    /** The editor and music distribution session for the current connection. */
    public ClientSync sync() {
        return sync;
    }

    // ---- for the editor ----

    /** How long a track is, or empty while that is still being worked out or it is not here. */
    public OptionalInt trackDurationMs(String musicId) {
        if (durations == null) {
            durations = new TrackDurations(new AudioStreamFactory(), scanExecutor);
        }
        return newestLibrary().find(musicId).map(durations::millis).orElse(OptionalInt.empty());
    }

    /** Whether every track of the area is on this computer, so that it can be auditioned. */
    public boolean canPreview(AreaDefinition area) {
        if (closed || !sync.serverPresent()) {
            return false;
        }
        MusicLibrary library = newestLibrary();
        return area.musicIds().stream().allMatch(library::contains);
    }

    /**
     * Plays the area the way walking into it sounds, wherever the player is, until
     * {@link #stopPreview()}. Fades are shortened so that it answers at once.
     */
    public void startPreview(AreaDefinition area) {
        if (!canPreview(area)) {
            return;
        }
        MusicLibrary newer = deferredLibrary;
        if (newer != null) {
            // A download that finished since the music last changed; the audition may need it.
            deferredLibrary = null;
            activeLibrary = newer;
            playbackSession.updateLibrary(newer);
        }
        AreaDefinition prompt = AreaEdits.withFadeOut(
                AreaEdits.withFadeIn(area, PREVIEW_FADE_IN_MS), PREVIEW_FADE_OUT_MS);
        playbackSession.setPreview(PlaybackState.preview(prompt.playback(), area.startOffsetMs()));
    }

    public void stopPreview() {
        playbackSession.setPreview(null);
    }

    public boolean previewing() {
        return playbackSession.previewing();
    }

    private MusicLibrary newestLibrary() {
        MusicLibrary newer = deferredLibrary;
        return newer != null ? newer : activeLibrary;
    }

    /** Installs the loader's way of sending serverbound sync bytes. */
    public void setSyncSender(Consumer<byte[]> sender) {
        syncSender = Objects.requireNonNull(sender, "sender");
    }

    @Override
    public void onPlayback(long revision, PlaybackState state) {
        if (closed) {
            return;
        }
        currentState = state;
        sync.prioritize(state.musicIds());
        MusicLibrary newer = deferredLibrary;
        if (newer != null) {
            deferredLibrary = null;
            activeLibrary = newer;
            playbackSession.apply(revision, state, newer);
        } else {
            playbackSession.apply(revision, state);
        }
    }

    @Override
    public void onSync(byte[] data) {
        if (closed) {
            return;
        }
        try {
            sync.onMessage(SyncCodec.decodeClientbound(data));
        } catch (IllegalArgumentException exception) {
            LOGGER.warn("Ignoring a malformed AreaMusic message from the server", exception);
        }
    }

    private void sendSync(SyncMessage.Serverbound message) {
        byte[] data = SyncCodec.encode(message);
        if (data.length > SyncCodec.MAX_SERVERBOUND_BYTES) {
            LOGGER.warn("AreaMusic message {} is too large to send", message.getClass().getSimpleName());
            return;
        }
        syncSender.accept(data);
    }

    /**
     * A download finished or the server pinned different files. Music that is playing is only
     * restarted when one of its own tracks changed; otherwise the new library waits for the next
     * playback state.
     */
    private void onLibraryChanged(MusicLibrary library) {
        if (closed) {
            return;
        }
        boolean affectsCurrent = false;
        for (String musicId : currentState.musicIds()) {
            if (!activeLibrary.find(musicId).equals(library.find(musicId))) {
                affectsCurrent = true;
                break;
            }
        }
        if (affectsCurrent) {
            deferredLibrary = null;
            activeLibrary = library;
            reportedErrors.clear();
            playbackSession.updateLibrary(library);
        } else {
            deferredLibrary = library;
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
        if (currentState.playing() || playbackSession.previewing()) {
            // The game's own background music would play on top of the area's.
            clientRuntime.silenceGameMusic();
        }
        sync.tick();
        if (++ticks % 10 == 0 && currentState.playing()) {
            sync.downloadPercent(currentState.musicIds()).ifPresent(percent ->
                    clientRuntime.displayOverlayMessage(
                            Component.translatable("message.areamusic.downloading", percent)));
        }
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
        sync.disconnect();
        currentState = PlaybackState.stopped();
        deferredLibrary = null;
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
        MusicLibrary playable = sync.setLocalLibrary(library);
        activeLibrary = playable;
        deferredLibrary = null;
        playbackSession.finishReload(playable);
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
        if (closed) {
            return;
        }
        clientRuntime.execute(() -> {
            // A track that is still downloading is not an error; it starts when it arrives.
            if (failure.kind() == AudioFailure.Kind.MISSING_FILE && sync.awaiting(failure.musicId())) {
                return;
            }
            if (!reportedErrors.add(failure.deduplicationKey())) {
                return;
            }
            LOGGER.error(
                    "AreaMusic client audio error ({}, MusicID '{}')",
                    failure.kind(),
                    failure.musicId(),
                    rootCause(failure.cause())
            );
            clientRuntime.displayClientMessage(errorMessage(failure));
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

    interface ClientRuntime {
        void execute(Runnable task);

        float masterVolume();

        boolean paused();

        void displayClientMessage(Component message);

        /** Shows a short line above the hotbar. */
        default void displayOverlayMessage(Component message) {
        }

        /**
         * Stops the game's own background music if it is playing. Called every tick while area
         * music plays; the game picks its music up again by itself once that stops.
         */
        default void silenceGameMusic() {
        }
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
        public void silenceGameMusic() {
            // Only what plays on the Music slider: jukeboxes and note blocks are left alone.
            Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
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

        @Override
        public void displayOverlayMessage(Component message) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null) {
                minecraft.player.displayClientMessage(message, true);
            }
        }
    }

    private static final class LocalScanFailure extends RuntimeException {
        private LocalScanFailure(Throwable cause) {
            super(cause);
        }
    }
}
