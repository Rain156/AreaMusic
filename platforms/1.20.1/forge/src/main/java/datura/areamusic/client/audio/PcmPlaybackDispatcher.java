package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaybackState;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class PcmPlaybackDispatcher implements PcmPlaybackEngine {
    private static final int FRAME_SIZE = AudioStreamFactory.MIX_FORMAT.getFrameSize();

    private final PcmPlaybackEngine parallel;
    private final PcmPlaybackEngine playlist;
    private PlaybackState currentState = PlaybackState.stopped();
    private boolean closed;

    public PcmPlaybackDispatcher(MusicLibrary musicLibrary) {
        this(
                new PcmMixerEngine(
                        new AudioStreamFactory(),
                        Objects.requireNonNull(musicLibrary, "musicLibrary")
                ),
                new PlaylistPcmEngine(new AudioStreamFactory(), musicLibrary)
        );
    }

    PcmPlaybackDispatcher(PcmPlaybackEngine parallel, PcmPlaybackEngine playlist) {
        this.parallel = Objects.requireNonNull(parallel, "parallel");
        this.playlist = Objects.requireNonNull(playlist, "playlist");
    }

    @Override
    public void setMusicLibrary(MusicLibrary musicLibrary) {
        ensureOpen();
        MusicLibrary checkedLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
        parallel.setMusicLibrary(checkedLibrary);
        playlist.setMusicLibrary(checkedLibrary);
    }

    @Override
    public void apply(long revision, PlaybackState state) {
        ensureOpen();
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        PlaybackState checkedState = Objects.requireNonNull(state, "state");
        PlaybackState parallelState = PlaybackState.stopped();
        PlaybackState playlistState = PlaybackState.stopped();
        if (checkedState.playing()) {
            PlaybackMode mode = checkedState.mode().orElseThrow(
                    () -> new IllegalArgumentException("Playing state has no playback mode")
            );
            if (mode == PlaybackMode.PARALLEL) {
                parallelState = checkedState;
            } else if (mode == PlaybackMode.PLAYLIST_LOOP) {
                playlistState = checkedState;
            } else {
                throw new IllegalArgumentException("Unsupported playback mode: " + mode);
            }
        }

        parallel.apply(revision, parallelState);
        playlist.apply(revision, playlistState);
        currentState = checkedState;
    }

    @Override
    public byte[] renderFrames(int frameCount, float masterGain) {
        ensureOpen();
        if (frameCount < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (!Float.isFinite(masterGain) || masterGain < 0.0f || masterGain > 1.0f) {
            throw new IllegalArgumentException("Master gain must be finite and between 0 and 1");
        }
        int expectedBytes = Math.multiplyExact(frameCount, FRAME_SIZE);
        byte[] mixed = new byte[expectedBytes];

        boolean parallelWork = parallel.hasWork();
        boolean playlistWork = playlist.hasWork();
        if (parallelWork) {
            addRenderedFrames(mixed, parallel.renderFrames(frameCount, masterGain), "parallel");
        }
        if (playlistWork) {
            addRenderedFrames(mixed, playlist.renderFrames(frameCount, masterGain), "playlist");
        }
        return mixed;
    }

    @Override
    public List<AudioFailure> drainFailures() {
        ensureOpen();
        List<AudioFailure> failures = new ArrayList<>(parallel.drainFailures());
        failures.addAll(playlist.drainFailures());
        return List.copyOf(failures);
    }

    @Override
    public boolean hasWork() {
        ensureOpen();
        boolean parallelWork = parallel.hasWork();
        boolean playlistWork = playlist.hasWork();
        return parallelWork || playlistWork;
    }

    @Override
    public PlaybackState currentState() {
        ensureOpen();
        return currentState;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            parallel.close();
        } finally {
            playlist.close();
        }
    }

    private static void addRenderedFrames(byte[] mixed, byte[] rendered, String delegateName) {
        byte[] checked = Objects.requireNonNull(rendered, delegateName + " rendered PCM");
        if (checked.length % FRAME_SIZE != 0) {
            throw new IllegalStateException(
                    delegateName + " rendered PCM is not aligned to frame size " + FRAME_SIZE
            );
        }
        if (checked.length != mixed.length) {
            throw new IllegalStateException(
                    delegateName + " rendered PCM byte count mismatch: expected="
                            + mixed.length + ", actual=" + checked.length
            );
        }
        for (int offset = 0; offset < mixed.length; offset += 2) {
            int sample = PcmMath.readLittleEndian(mixed, offset)
                    + PcmMath.readLittleEndian(checked, offset);
            PcmMath.writeLittleEndian(mixed, offset, sample);
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("PCM playback dispatcher is closed");
        }
    }
}
