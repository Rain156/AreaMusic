package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
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
    private static final int MAX_LIVE_SESSIONS = 4;
    private static final int MAX_CONSECUTIVE_ZERO_READS = 64;
    private static final int OVERFLOW_FADE_MS = 20;

    private final AudioStreamFactory streamFactory;
    private final List<AreaSession> outgoingSessions = new ArrayList<>();
    private final List<AudioFailure> failures = new ArrayList<>();
    private MusicLibrary musicLibrary;
    private PlaybackState currentState = PlaybackState.stopped();
    private AreaSession currentSession;

    public PcmMixerEngine(AudioStreamFactory streamFactory, MusicLibrary musicLibrary) {
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
    }

    public void setMusicLibrary(MusicLibrary musicLibrary) {
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
    }

    public void apply(PlaybackState state) {
        apply(0L, state);
    }

    public void apply(long revision, PlaybackState state) {
        Objects.requireNonNull(state, "state");
        removeSilentOutgoingSessions();
        if (state.equals(currentState)) {
            return;
        }

        AreaSession previous = currentSession;
        currentSession = null;
        if (!state.playing()) {
            moveToOutgoing(previous);
            currentState = state;
            removeSilentOutgoingSessions();
            return;
        }

        AreaSession incoming = new AreaSession(revision, state);
        if (previous != null) {
            transferContinuingTracks(previous, incoming);
            moveToOutgoing(previous);
        }
        currentSession = incoming;
        currentState = state;
        makeRoomForCurrentSession();
        startDueTracks(incoming);
    }

    public byte[] renderFrames(int frameCount, float masterGain) {
        if (frameCount < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (!Float.isFinite(masterGain) || masterGain < 0.0f || masterGain > 1.0f) {
            throw new IllegalArgumentException("Master gain must be finite and between 0 and 1");
        }

        byte[] output = new byte[frameCount * FRAME_SIZE];
        int[] mixed = new int[frameCount * CHANNELS];
        int renderedFrames = 0;
        while (renderedFrames < frameCount) {
            removeSilentOutgoingSessions();
            makeRoomForCurrentSession();
            startDueTracks(currentSession);

            long untilStart = currentSession == null
                    ? Long.MAX_VALUE
                    : currentSession.timeline.framesUntilNextStart();
            int segmentFrames = (int) Math.min(
                    frameCount - renderedFrames,
                    Math.max(1L, untilStart)
            );
            mixSegment(mixed, renderedFrames, segmentFrames, masterGain);
            if (currentSession != null && !currentSession.outgoing) {
                currentSession.timeline.advancePending(segmentFrames);
            }
            renderedFrames += segmentFrames;
        }
        removeSilentOutgoingSessions();
        makeRoomForCurrentSession();
        startDueTracks(currentSession);

        for (int sample = 0; sample < mixed.length; sample++) {
            PcmMath.writeLittleEndian(output, sample * 2, mixed[sample]);
        }
        return output;
    }

    public List<AudioFailure> drainFailures() {
        List<AudioFailure> drained = List.copyOf(failures);
        failures.clear();
        return drained;
    }

    public boolean hasWork() {
        if (currentSession != null
                && (!currentSession.tracks.isEmpty()
                || currentSession.timeline.framesUntilNextStart() != Long.MAX_VALUE)) {
            return true;
        }
        for (AreaSession session : outgoingSessions) {
            if (!session.tracks.isEmpty()) {
                return true;
            }
        }
        return false;
    }

    public PlaybackState currentState() {
        return currentState;
    }

    @Override
    public void close() {
        if (currentSession != null) {
            currentSession.close();
            currentSession = null;
        }
        for (AreaSession session : outgoingSessions) {
            session.close();
        }
        outgoingSessions.clear();
        failures.clear();
        currentState = PlaybackState.stopped();
    }

    private void transferContinuingTracks(AreaSession previous, AreaSession incoming) {
        Iterator<RuntimeTrack> iterator = previous.tracks.iterator();
        while (iterator.hasNext()) {
            RuntimeTrack runtime = iterator.next();
            int index = runtime.trackIndex;
            if (index >= incoming.state.tracks().size()) {
                continue;
            }
            AreaTrackDefinition destination = incoming.state.tracks().get(index);
            if (destination.delayFrames(AudioStreamFactory.SAMPLE_RATE) != 0L
                    || !runtime.musicId().equals(destination.musicId())
                    || runtime.exhausted
                    || runtime.gain.target() == 0.0f) {
                continue;
            }

            long position = previous.timeline.positionInLoopFrames(index);
            iterator.remove();
            incoming.timeline.markStarted(index);
            incoming.timeline.recordFramesRead(index, position);
            runtime.attach(incoming, index, destination);
            runtime.gain.fadeTo(
                    destination.volume(), destination.fadeInMs(), AudioStreamFactory.SAMPLE_RATE
            );
            incoming.tracks.add(runtime);
        }
    }

    private void moveToOutgoing(AreaSession session) {
        if (session == null) {
            return;
        }
        session.outgoing = true;
        for (RuntimeTrack track : session.tracks) {
            AreaTrackDefinition definition = session.state.tracks().get(track.trackIndex);
            track.gain.fadeTo(0.0f, definition.fadeOutMs(), AudioStreamFactory.SAMPLE_RATE);
        }
        if (session.tracks.isEmpty()) {
            session.close();
        } else {
            outgoingSessions.add(session);
        }
    }

    private void makeRoomForCurrentSession() {
        if (currentSession == null || !currentSession.tracks.isEmpty()) {
            return;
        }
        if (liveOutgoingSessionCount() < MAX_LIVE_SESSIONS) {
            return;
        }
        expediteQuietestOutgoingSession();
    }

    private int liveOutgoingSessionCount() {
        int count = 0;
        for (AreaSession session : outgoingSessions) {
            if (!session.tracks.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private boolean currentSessionHasRoom() {
        return currentSession == null
                || !currentSession.tracks.isEmpty()
                || liveOutgoingSessionCount() < MAX_LIVE_SESSIONS;
    }

    private void expediteQuietestOutgoingSession() {
        for (AreaSession session : outgoingSessions) {
            if (session.expeditedRemoval) {
                return;
            }
        }

        AreaSession quietest = null;
        float quietestGain = Float.POSITIVE_INFINITY;
        for (AreaSession session : outgoingSessions) {
            float sessionGain = 0.0f;
            for (RuntimeTrack track : session.tracks) {
                sessionGain += track.gain.value();
            }
            if (quietest == null || sessionGain < quietestGain) {
                quietest = session;
                quietestGain = sessionGain;
            }
        }
        if (quietest == null) {
            return;
        }
        quietest.expeditedRemoval = true;
        for (RuntimeTrack track : quietest.tracks) {
            track.gain.fadeTo(0.0f, OVERFLOW_FADE_MS, AudioStreamFactory.SAMPLE_RATE);
        }
    }

    private void startDueTracks(AreaSession session) {
        if (session == null || session.outgoing || session != currentSession) {
            return;
        }
        if (!currentSessionHasRoom()) {
            return;
        }

        for (int trackIndex : session.timeline.dueTrackIndices()) {
            AreaTrackDefinition definition = session.state.tracks().get(trackIndex);
            Path path = musicLibrary.find(definition.musicId()).orElse(null);
            if (path == null) {
                session.timeline.markFailed(trackIndex);
                failures.add(new AudioPlaybackException(
                        AudioFailure.Kind.MISSING_FILE,
                        definition.musicId(),
                        "Local MusicID is missing: " + definition.musicId()
                ).failure());
                continue;
            }

            try {
                RuntimeTrack runtime = new RuntimeTrack(session, trackIndex, definition, path);
                session.timeline.markStarted(trackIndex);
                runtime.gain.fadeTo(
                        definition.volume(), definition.fadeInMs(), AudioStreamFactory.SAMPLE_RATE
                );
                session.tracks.add(runtime);
            } catch (Exception exception) {
                session.timeline.markFailed(trackIndex);
                failures.add(new AudioPlaybackException(
                        AudioFailure.Kind.DECODE,
                        definition.musicId(),
                        "Could not open " + definition.musicId(),
                        exception
                ).failure());
            }
        }
    }

    private void mixSegment(
            int[] mixed,
            int outputFrameOffset,
            int frameCount,
            float masterGain
    ) {
        for (AreaSession session : outgoingSessions) {
            mixSession(session, mixed, outputFrameOffset, frameCount, masterGain);
        }
        mixSession(currentSession, mixed, outputFrameOffset, frameCount, masterGain);
    }

    private void mixSession(
            AreaSession session,
            int[] mixed,
            int outputFrameOffset,
            int frameCount,
            float masterGain
    ) {
        if (session == null) {
            return;
        }
        Iterator<RuntimeTrack> iterator = session.tracks.iterator();
        while (iterator.hasNext()) {
            RuntimeTrack track = iterator.next();
            byte[] trackPcm = new byte[frameCount * FRAME_SIZE];
            int framesRead;
            try {
                framesRead = track.readFrames(trackPcm, frameCount);
            } catch (Exception exception) {
                if (!session.timeline.failed(track.trackIndex)
                        && !session.timeline.completed(track.trackIndex)) {
                    session.timeline.markFailed(track.trackIndex);
                }
                track.close();
                iterator.remove();
                failures.add(new AudioPlaybackException(
                        AudioFailure.Kind.DECODE,
                        track.musicId(),
                        "Could not decode " + track.musicId(),
                        exception
                ).failure());
                continue;
            }

            for (int frame = 0; frame < frameCount; frame++) {
                float gain = track.gain.value() * masterGain;
                if (frame < framesRead) {
                    int sourceOffset = frame * FRAME_SIZE;
                    int mixedSample = (outputFrameOffset + frame) * CHANNELS;
                    mixed[mixedSample] += PcmMath.scale(
                            PcmMath.readLittleEndian(trackPcm, sourceOffset), gain
                    );
                    mixed[mixedSample + 1] += PcmMath.scale(
                            PcmMath.readLittleEndian(trackPcm, sourceOffset + 2), gain
                    );
                }
                track.gain.advance(1);
            }

            if (track.shouldRemove()) {
                track.close();
                iterator.remove();
            }
        }
    }

    private void removeSilentOutgoingSessions() {
        Iterator<AreaSession> sessionIterator = outgoingSessions.iterator();
        while (sessionIterator.hasNext()) {
            AreaSession session = sessionIterator.next();
            Iterator<RuntimeTrack> trackIterator = session.tracks.iterator();
            while (trackIterator.hasNext()) {
                RuntimeTrack track = trackIterator.next();
                if (track.gain.isComplete() && track.gain.target() == 0.0f) {
                    track.close();
                    trackIterator.remove();
                }
            }
            if (session.tracks.isEmpty()) {
                session.close();
                sessionIterator.remove();
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

    private final class AreaSession implements AutoCloseable {
        private final long revision;
        private final PlaybackState state;
        private final AreaPlaybackTimeline timeline;
        private final List<RuntimeTrack> tracks = new ArrayList<>();
        private boolean outgoing;
        private boolean snapshotWhenSilent;
        private boolean expeditedRemoval;

        private AreaSession(long revision, PlaybackState state) {
            this.revision = revision;
            this.state = state;
            this.timeline = AreaPlaybackTimeline.fresh(
                    state.tracks(), AudioStreamFactory.SAMPLE_RATE
            );
        }

        @Override
        public void close() {
            for (RuntimeTrack track : tracks) {
                track.close();
            }
            tracks.clear();
        }
    }

    private final class RuntimeTrack {
        private AreaSession session;
        private int trackIndex;
        private AreaTrackDefinition definition;
        private final Path path;
        private final FadeEnvelope gain = new FadeEnvelope(0.0f);
        private AudioInputStream stream;
        private boolean exhausted;

        private RuntimeTrack(
                AreaSession session,
                int trackIndex,
                AreaTrackDefinition definition,
                Path path
        ) throws Exception {
            this.session = session;
            this.trackIndex = trackIndex;
            this.definition = definition;
            this.path = path;
            this.stream = streamFactory.open(path);
        }

        private void attach(
                AreaSession destinationSession,
                int destinationIndex,
                AreaTrackDefinition destinationDefinition
        ) {
            session = destinationSession;
            trackIndex = destinationIndex;
            definition = destinationDefinition;
        }

        private String musicId() {
            return definition.musicId();
        }

        private int readFrames(byte[] destination, int requestedFrames) throws Exception {
            int requestedBytes = requestedFrames * FRAME_SIZE;
            int totalBytes = 0;
            int consecutiveZeroReads = 0;
            boolean reopenedWithoutData = false;
            while (totalBytes < requestedBytes) {
                int read = stream.read(destination, totalBytes, requestedBytes - totalBytes);
                if (read > 0) {
                    if (read % FRAME_SIZE != 0) {
                        throw new IOException(
                                "Decoder for " + musicId() + " returned a partial PCM frame"
                        );
                    }
                    totalBytes += read;
                    session.timeline.recordFramesRead(trackIndex, read / FRAME_SIZE);
                    consecutiveZeroReads = 0;
                    reopenedWithoutData = false;
                    continue;
                }
                if (read == 0) {
                    consecutiveZeroReads++;
                    if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                        throw new IOException(
                                "Decoder for " + musicId() + " returned zero bytes "
                                        + consecutiveZeroReads + " consecutive times"
                        );
                    }
                    continue;
                }
                if (!definition.loop() || reopenedWithoutData) {
                    exhausted = true;
                    session.timeline.markCompleted(trackIndex);
                    break;
                }
                reopen();
                session.timeline.markLoopRestarted(trackIndex);
                consecutiveZeroReads = 0;
                reopenedWithoutData = true;
            }
            return totalBytes / FRAME_SIZE;
        }

        private boolean shouldRemove() {
            return exhausted || gain.isComplete() && gain.target() == 0.0f;
        }

        private void reopen() throws Exception {
            closeStream();
            stream = streamFactory.open(path);
        }

        private void close() {
            try {
                closeStream();
            } catch (IOException ignored) {
            }
        }

        private void closeStream() throws IOException {
            AudioInputStream openStream = stream;
            stream = null;
            if (openStream != null) {
                openStream.close();
            }
        }
    }
}
