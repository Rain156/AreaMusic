package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;

public final class PcmMixerEngine implements AutoCloseable {
    private static final int CHANNELS = AudioStreamFactory.MIX_FORMAT.getChannels();
    private static final int FRAME_SIZE = AudioStreamFactory.MIX_FORMAT.getFrameSize();
    private static final int MAX_TRACKS = 4;
    private static final int OVERFLOW_FADE_MS = 20;

    private final AudioStreamFactory streamFactory;
    private final List<Track> tracks = new ArrayList<>();
    private MusicLibrary musicLibrary;
    private PlaybackState currentState = PlaybackState.stopped();
    private CompletedTrack completedTrack;
    private PlaybackState pendingStart;

    public PcmMixerEngine(AudioStreamFactory streamFactory, MusicLibrary musicLibrary) {
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
    }

    public void setMusicLibrary(MusicLibrary musicLibrary) {
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
    }

    public void apply(PlaybackState state) throws AudioPlaybackException {
        Objects.requireNonNull(state, "state");
        if (!state.playing()) {
            completedTrack = null;
            pendingStart = null;
            int fadeOutMs = currentState.playing() ? currentState.fadeOutMs() : 0;
            fadeAllToSilence(fadeOutMs);
            currentState = state;
            return;
        }
        if (completedTrack != null && completedTrack.matches(state) && !state.loop()) {
            pendingStart = null;
            currentState = state;
            return;
        }
        completedTrack = null;

        Track continuing = currentState.playing() && currentState.musicId().equals(state.musicId())
                ? findContinuingTrack(state.musicId())
                : null;
        if (continuing != null) {
            pendingStart = null;
            continuing.areaId = state.areaId();
            continuing.loop = state.loop();
            continuing.gain.fadeTo(state.volume(), state.fadeInMs(), AudioStreamFactory.SAMPLE_RATE);
            currentState = state;
            return;
        }

        int oldFadeOutMs = currentState.playing() ? currentState.fadeOutMs() : 0;
        fadeAllToSilence(oldFadeOutMs);
        currentState = state;
        pendingStart = state;
        startPendingIfRoom();
    }

    public byte[] renderFrames(int frameCount, float masterGain) throws AudioPlaybackException {
        if (frameCount < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (!Float.isFinite(masterGain) || masterGain < 0.0f || masterGain > 1.0f) {
            throw new IllegalArgumentException("Master gain must be finite and between 0 and 1");
        }

        byte[] output = new byte[frameCount * FRAME_SIZE];
        startPendingIfRoom();
        if (frameCount == 0 || tracks.isEmpty()) {
            return output;
        }

        int[] mixed = new int[frameCount * CHANNELS];
        Iterator<Track> iterator = tracks.iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next();
            byte[] trackPcm = new byte[output.length];
            int framesRead;
            try {
                framesRead = track.readFrames(trackPcm, frameCount);
            } catch (Exception exception) {
                track.close();
                iterator.remove();
                throw new AudioPlaybackException(
                        AudioFailure.Kind.DECODE,
                        track.musicId,
                        "Could not decode " + track.musicId,
                        exception
                );
            }

            for (int frame = 0; frame < frameCount; frame++) {
                float gain = track.gain.value() * masterGain;
                if (frame < framesRead) {
                    int frameOffset = frame * FRAME_SIZE;
                    int sampleIndex = frame * CHANNELS;
                    mixed[sampleIndex] += PcmMath.scale(PcmMath.readLittleEndian(trackPcm, frameOffset), gain);
                    mixed[sampleIndex + 1] += PcmMath.scale(
                            PcmMath.readLittleEndian(trackPcm, frameOffset + 2), gain
                    );
                }
                track.gain.advance(1);
            }

            if (track.shouldRemove()) {
                if (track.exhausted && !track.loop
                        && currentState.playing()
                        && track.areaId.equals(currentState.areaId())
                        && track.musicId.equals(currentState.musicId())) {
                    completedTrack = new CompletedTrack(track.areaId, track.musicId);
                }
                track.close();
                iterator.remove();
            }
        }
        startPendingIfRoom();

        for (int sample = 0; sample < mixed.length; sample++) {
            PcmMath.writeLittleEndian(output, sample * 2, mixed[sample]);
        }
        return output;
    }

    public boolean hasTracks() {
        return !tracks.isEmpty();
    }

    public PlaybackState currentState() {
        return currentState;
    }

    @Override
    public void close() {
        for (Track track : tracks) {
            track.close();
        }
        tracks.clear();
        currentState = PlaybackState.stopped();
        completedTrack = null;
        pendingStart = null;
    }

    private Track findContinuingTrack(String musicId) {
        for (int index = tracks.size() - 1; index >= 0; index--) {
            Track track = tracks.get(index);
            if (track.musicId.equals(musicId) && !track.exhausted && track.gain.target() > 0.0f) {
                return track;
            }
        }
        return null;
    }

    private void fadeAllToSilence(int durationMs) {
        for (Track track : tracks) {
            if (!track.expeditedRemoval) {
                track.gain.fadeTo(0.0f, durationMs, AudioStreamFactory.SAMPLE_RATE);
            }
        }
    }

    private void startPendingIfRoom() throws AudioPlaybackException {
        removeSilentTracks();
        if (pendingStart == null) {
            return;
        }
        if (tracks.size() >= MAX_TRACKS) {
            expediteQuietestOutgoingTrack();
            return;
        }

        PlaybackState state = pendingStart;
        Path path = musicLibrary.find(state.musicId()).orElse(null);
        if (path == null) {
            pendingStart = null;
            throw new AudioPlaybackException(
                    AudioFailure.Kind.MISSING_FILE,
                    state.musicId(),
                    "Local MusicID is missing: " + state.musicId()
            );
        }
        try {
            Track incoming = new Track(state.areaId(), state.musicId(), path, state.loop());
            incoming.gain.fadeTo(state.volume(), state.fadeInMs(), AudioStreamFactory.SAMPLE_RATE);
            tracks.add(incoming);
            pendingStart = null;
        } catch (Exception exception) {
            pendingStart = null;
            throw new AudioPlaybackException(
                    AudioFailure.Kind.DECODE,
                    state.musicId(),
                    "Could not open " + state.musicId(),
                    exception
            );
        }
    }

    private void expediteQuietestOutgoingTrack() {
        for (Track track : tracks) {
            if (track.expeditedRemoval) {
                return;
            }
        }

        Track quietest = null;
        for (Track track : tracks) {
            if (quietest == null || track.gain.value() < quietest.gain.value()) {
                quietest = track;
            }
        }
        if (quietest != null) {
            quietest.expeditedRemoval = true;
            quietest.gain.fadeTo(0.0f, OVERFLOW_FADE_MS, AudioStreamFactory.SAMPLE_RATE);
        }
    }

    private void removeSilentTracks() {
        Iterator<Track> iterator = tracks.iterator();
        while (iterator.hasNext()) {
            Track track = iterator.next();
            if (track.gain.isComplete() && track.gain.target() == 0.0f) {
                track.close();
                iterator.remove();
            }
        }
    }

    public static final class AudioPlaybackException extends Exception {
        private final AudioFailure.Kind kind;
        private final String musicId;

        public AudioPlaybackException(AudioFailure.Kind kind, String musicId, String message) {
            super(message);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.musicId = Objects.requireNonNull(musicId, "musicId");
        }

        public AudioPlaybackException(
                AudioFailure.Kind kind,
                String musicId,
                String message,
                Throwable cause
        ) {
            super(message, cause);
            this.kind = Objects.requireNonNull(kind, "kind");
            this.musicId = Objects.requireNonNull(musicId, "musicId");
        }

        public AudioFailure failure() {
            return new AudioFailure(kind, musicId, this);
        }
    }

    private record CompletedTrack(String areaId, String musicId) {
        private boolean matches(PlaybackState state) {
            return areaId.equals(state.areaId()) && musicId.equals(state.musicId());
        }
    }

    private final class Track {
        private String areaId;
        private final String musicId;
        private final Path path;
        private final FadeEnvelope gain = new FadeEnvelope(0.0f);
        private AudioInputStream stream;
        private boolean loop;
        private boolean exhausted;
        private boolean expeditedRemoval;

        private Track(String areaId, String musicId, Path path, boolean loop) throws Exception {
            this.areaId = areaId;
            this.musicId = musicId;
            this.path = path;
            this.loop = loop;
            this.stream = streamFactory.open(path);
        }

        private int readFrames(byte[] destination, int requestedFrames) throws Exception {
            int requestedBytes = requestedFrames * FRAME_SIZE;
            int totalBytes = 0;
            boolean reopenedWithoutData = false;
            while (totalBytes < requestedBytes) {
                int read = stream.read(destination, totalBytes, requestedBytes - totalBytes);
                if (read > 0) {
                    totalBytes += read;
                    reopenedWithoutData = false;
                    continue;
                }
                if (read == 0) {
                    break;
                }
                if (!loop || reopenedWithoutData) {
                    exhausted = true;
                    break;
                }
                reopen();
                reopenedWithoutData = true;
            }
            return totalBytes / FRAME_SIZE;
        }

        private boolean shouldRemove() {
            return exhausted || gain.isComplete() && gain.target() == 0.0f;
        }

        private void reopen() throws Exception {
            stream.close();
            stream = streamFactory.open(path);
        }

        private void close() {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
