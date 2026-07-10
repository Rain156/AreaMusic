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

    private final AudioStreamFactory streamFactory;
    private final List<Track> tracks = new ArrayList<>();
    private MusicLibrary musicLibrary;
    private PlaybackState currentState = PlaybackState.stopped();

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
            int fadeOutMs = currentState.playing() ? currentState.fadeOutMs() : 0;
            fadeAllToSilence(fadeOutMs);
            currentState = state;
            return;
        }

        Track continuing = currentState.playing() && currentState.musicId().equals(state.musicId())
                ? findContinuingTrack(state.musicId())
                : null;
        if (continuing != null) {
            continuing.loop = state.loop();
            continuing.gain.fadeTo(state.volume(), state.fadeInMs(), AudioStreamFactory.SAMPLE_RATE);
            currentState = state;
            return;
        }

        int oldFadeOutMs = currentState.playing() ? currentState.fadeOutMs() : 0;
        fadeAllToSilence(oldFadeOutMs);
        currentState = state;

        Path path = musicLibrary.find(state.musicId())
                .orElseThrow(() -> new AudioPlaybackException("Local MusicID is missing: " + state.musicId()));
        Track incoming;
        try {
            incoming = new Track(state.musicId(), path, state.loop());
        } catch (Exception exception) {
            throw new AudioPlaybackException("Could not open " + state.musicId(), exception);
        }
        incoming.gain.fadeTo(state.volume(), state.fadeInMs(), AudioStreamFactory.SAMPLE_RATE);
        makeRoomForIncomingTrack();
        tracks.add(incoming);
    }

    public byte[] renderFrames(int frameCount, float masterGain) throws AudioPlaybackException {
        if (frameCount < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (!Float.isFinite(masterGain) || masterGain < 0.0f || masterGain > 1.0f) {
            throw new IllegalArgumentException("Master gain must be finite and between 0 and 1");
        }

        byte[] output = new byte[frameCount * FRAME_SIZE];
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
                throw new AudioPlaybackException("Could not decode " + track.musicId, exception);
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
                track.close();
                iterator.remove();
            }
        }

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
            track.gain.fadeTo(0.0f, durationMs, AudioStreamFactory.SAMPLE_RATE);
        }
    }

    private void makeRoomForIncomingTrack() {
        while (tracks.size() >= MAX_TRACKS) {
            Track removed = tracks.remove(0);
            removed.close();
        }
    }

    public static final class AudioPlaybackException extends Exception {
        public AudioPlaybackException(String message) {
            super(message);
        }

        public AudioPlaybackException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final class Track {
        private final String musicId;
        private final Path path;
        private final FadeEnvelope gain = new FadeEnvelope(0.0f);
        private AudioInputStream stream;
        private boolean loop;
        private boolean exhausted;

        private Track(String musicId, Path path, boolean loop) throws Exception {
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
