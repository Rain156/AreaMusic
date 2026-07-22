package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public final class PcmMixerEngine implements AutoCloseable {
    private static final int CHANNELS = AudioStreamFactory.MIX_FORMAT.getChannels();
    private static final int FRAME_SIZE = AudioStreamFactory.MIX_FORMAT.getFrameSize();
    private static final int MAX_LIVE_SESSIONS = 4;
    private static final int MAX_CONSECUTIVE_ZERO_READS = 64;
    private static final int OVERFLOW_FADE_MS = 20;

    private final AudioStreamFactory streamFactory;
    private final AudioStreamPreparer streamPreparer;
    private final boolean ownsStreamPreparer;
    private final List<AreaSession> outgoingSessions = new ArrayList<>();
    private final List<AudioFailure> failures = new ArrayList<>();
    private final Map<ResumeKey, SessionSnapshot> resumeSnapshots = new HashMap<>();
    private MusicLibrary musicLibrary;
    private PlaybackState currentState = PlaybackState.stopped();
    private AreaSession currentSession;
    private long lastRevision = -1L;
    private boolean closed;

    public PcmMixerEngine(AudioStreamFactory streamFactory, MusicLibrary musicLibrary) {
        this(
                streamFactory,
                musicLibrary,
                new AudioStreamPreparer(Objects.requireNonNull(streamFactory, "streamFactory"), 2),
                true
        );
    }

    PcmMixerEngine(
            AudioStreamFactory streamFactory,
            MusicLibrary musicLibrary,
            AudioStreamPreparer streamPreparer
    ) {
        this(streamFactory, musicLibrary, streamPreparer, false);
    }

    private PcmMixerEngine(
            AudioStreamFactory streamFactory,
            MusicLibrary musicLibrary,
            AudioStreamPreparer streamPreparer,
            boolean ownsStreamPreparer
    ) {
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
        this.streamPreparer = Objects.requireNonNull(streamPreparer, "streamPreparer");
        this.ownsStreamPreparer = ownsStreamPreparer;
    }

    public void setMusicLibrary(MusicLibrary musicLibrary) {
        ensureOpen();
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
        resumeSnapshots.clear();
        for (AreaSession session : outgoingSessions) {
            session.snapshotInvalidated = true;
            session.snapshotWhenSilent = false;
            session.cancelPreparations();
        }
        if (currentSession != null) {
            currentSession.snapshotInvalidated = true;
        }
        if (currentSession != null && !currentSession.preparations.isEmpty()) {
            currentSession.close();
            currentSession = null;
            currentState = PlaybackState.stopped();
        }
    }

    public void apply(long revision, PlaybackState state) {
        ensureOpen();
        Objects.requireNonNull(state, "state");
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        if (revision != lastRevision) {
            invalidateResumeState();
            lastRevision = revision;
        }
        removeSilentOutgoingSessions();
        if (state.equals(currentState)
                && (currentSession == null || currentSession.revision == revision)) {
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

        AreaSession incoming = createIncomingSession(revision, state);
        if (previous != null) {
            transferContinuingTracks(previous, incoming);
            moveToOutgoing(previous);
        }
        currentSession = incoming;
        currentState = state;
        makeRoomForCurrentSession();
        scheduleRestoredTracks(incoming);
        pollCompletedPreparations(incoming);
        startDueTracks(incoming);
    }

    public byte[] renderFrames(int frameCount, float masterGain) {
        ensureOpen();
        if (frameCount < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (!Float.isFinite(masterGain) || masterGain < 0.0f || masterGain > 1.0f) {
            throw new IllegalArgumentException("Master gain must be finite and between 0 and 1");
        }

        int outputByteCount = Math.multiplyExact(frameCount, FRAME_SIZE);
        int mixedSampleCount = Math.multiplyExact(frameCount, CHANNELS);
        byte[] output = new byte[outputByteCount];
        int[] mixed = new int[mixedSampleCount];
        int renderedFrames = 0;
        while (renderedFrames < frameCount) {
            removeSilentOutgoingSessions();
            makeRoomForCurrentSession();
            pollCompletedPreparations(currentSession);
            startDueTracks(currentSession);

            long untilStart = currentSession == null
                    ? Long.MAX_VALUE
                    : currentSession.timeline.framesUntilNextStart();
            if (untilStart == 0L) {
                // A due track was already offered to start above. If the live-session cap
                // deferred it, the next useful boundary is an outgoing fade completion.
                untilStart = Long.MAX_VALUE;
            }
            long untilFadeEnd = framesUntilNextOutgoingFadeEnd();
            int segmentFrames = (int) Math.min(
                    frameCount - renderedFrames,
                    Math.max(1L, Math.min(untilStart, untilFadeEnd))
            );
            mixSegment(mixed, renderedFrames, segmentFrames, masterGain);
            if (currentSession != null && !currentSession.outgoing) {
                currentSession.timeline.advancePending(segmentFrames);
            }
            renderedFrames += segmentFrames;
        }
        removeSilentOutgoingSessions();
        makeRoomForCurrentSession();
        pollCompletedPreparations(currentSession);
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
                || !currentSession.preparations.isEmpty()
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
        if (closed) {
            return;
        }
        closed = true;
        resumeSnapshots.clear();
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
        lastRevision = -1L;
        if (ownsStreamPreparer) {
            streamPreparer.close();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("PCM mixer engine is closed");
        }
    }

    private void transferContinuingTracks(AreaSession previous, AreaSession incoming) {
        if (previous.revision != incoming.revision || previous.snapshotInvalidated) {
            return;
        }
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
                    || runtime.lifecycleGain.target() == 0.0f
                    || incoming.timeline.started(index)
                    || incoming.timeline.completed(index)
                    || incoming.timeline.failed(index)) {
                continue;
            }

            long position = previous.timeline.positionInLoopFrames(index);
            iterator.remove();
            incoming.timeline.markStarted(index);
            incoming.timeline.recordFramesRead(index, position);
            runtime.attach(incoming, index, destination);
            runtime.lifecycleGain.fadeTo(
                    1.0f, destination.fadeInMs(), AudioStreamFactory.SAMPLE_RATE
            );
            runtime.volumeGain.fadeTo(
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
        session.snapshotWhenSilent = session.state.resumeOnReenter()
                && !session.snapshotInvalidated;
        session.cancelPreparations();
        if (!session.snapshotWhenSilent) {
            resumeSnapshots.remove(session.resumeKey());
        }
        for (RuntimeTrack track : session.tracks) {
            AreaTrackDefinition definition = session.state.tracks().get(track.trackIndex);
            track.volumeGain.fadeTo(
                    track.volumeGain.value(), 0, AudioStreamFactory.SAMPLE_RATE
            );
            track.lifecycleGain.fadeTo(
                    0.0f, definition.fadeOutMs(), AudioStreamFactory.SAMPLE_RATE
            );
        }
        if (session.tracks.isEmpty()) {
            storeSnapshotAndClose(session);
        } else {
            outgoingSessions.add(session);
        }
    }

    private AreaSession createIncomingSession(long revision, PlaybackState state) {
        ResumeKey key = new ResumeKey(revision, state.areaId());
        SessionSnapshot snapshot = resumeSnapshots.remove(key);
        if (snapshot != null && snapshot.state().equals(state)) {
            return new AreaSession(
                    revision,
                    state,
                    AreaPlaybackTimeline.restore(
                            snapshot.timeline(), state.tracks(), AudioStreamFactory.SAMPLE_RATE
                    ),
                    true
            );
        }
        return new AreaSession(
                revision,
                state,
                AreaPlaybackTimeline.fresh(state.tracks(), AudioStreamFactory.SAMPLE_RATE),
                false
        );
    }

    private void invalidateResumeState() {
        resumeSnapshots.clear();
        if (currentSession != null) {
            currentSession.snapshotInvalidated = true;
            currentSession.cancelPreparations();
        }
        for (AreaSession session : outgoingSessions) {
            session.snapshotInvalidated = true;
            session.snapshotWhenSilent = false;
            session.cancelPreparations();
        }
    }

    private long framesUntilNextOutgoingFadeEnd() {
        long nearest = Long.MAX_VALUE;
        for (AreaSession session : outgoingSessions) {
            for (RuntimeTrack track : session.tracks) {
                if (track.lifecycleGain.target() != 0.0f) {
                    continue;
                }
                long remaining = track.lifecycleGain.framesUntilComplete();
                if (remaining > 0L) {
                    nearest = Math.min(nearest, remaining);
                }
            }
        }
        return nearest;
    }

    private void scheduleRestoredTracks(AreaSession session) {
        if (!session.restored || session.outgoing || session != currentSession) {
            return;
        }
        for (int trackIndex = 0; trackIndex < session.state.tracks().size(); trackIndex++) {
            if (!session.timeline.started(trackIndex)
                    || session.timeline.completed(trackIndex)
                    || session.timeline.failed(trackIndex)
                    || session.hasRuntimeTrack(trackIndex)
                    || session.preparations.containsKey(trackIndex)) {
                continue;
            }
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
                CompletableFuture<AudioInputStream> future = streamPreparer.prepare(
                        path, session.timeline.positionInLoopFrames(trackIndex)
                );
                session.preparations.put(
                        trackIndex,
                        new PendingPreparation(trackIndex, definition, path, future)
                );
            } catch (RuntimeException exception) {
                session.timeline.markFailed(trackIndex);
                failures.add(new AudioPlaybackException(
                        AudioFailure.Kind.DECODE,
                        definition.musicId(),
                        "Could not prepare " + definition.musicId(),
                        exception
                ).failure());
            }
        }
    }

    private void pollCompletedPreparations(AreaSession session) {
        if (session == null || session.outgoing || session != currentSession) {
            return;
        }
        Iterator<PendingPreparation> iterator = session.preparations.values().iterator();
        while (iterator.hasNext()) {
            PendingPreparation pending = iterator.next();
            CompletableFuture<AudioInputStream> future = pending.future();
            if (!future.isDone()) {
                continue;
            }
            if (!currentSessionHasRoom()) {
                return;
            }
            iterator.remove();

            AudioInputStream preparedStream;
            try {
                preparedStream = future.join();
            } catch (CancellationException exception) {
                failPreparation(session, pending, exception);
                continue;
            } catch (CompletionException exception) {
                Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                failPreparation(session, pending, cause);
                continue;
            }

            if (session.outgoing || session != currentSession) {
                closeStream(preparedStream);
                continue;
            }
            RuntimeTrack runtime = new RuntimeTrack(
                    session,
                    pending.trackIndex(),
                    pending.definition(),
                    pending.path(),
                    preparedStream
            );
            runtime.lifecycleGain.fadeTo(
                    1.0f,
                    pending.definition().fadeInMs(),
                    AudioStreamFactory.SAMPLE_RATE
            );
            session.tracks.add(runtime);
        }
    }

    private void failPreparation(
            AreaSession session,
            PendingPreparation pending,
            Throwable failure
    ) {
        int trackIndex = pending.trackIndex();
        if (session.timeline.completed(trackIndex) || session.timeline.failed(trackIndex)) {
            return;
        }
        session.timeline.markFailed(trackIndex);
        failures.add(new AudioPlaybackException(
                AudioFailure.Kind.DECODE,
                pending.definition().musicId(),
                "Could not prepare " + pending.definition().musicId(),
                failure
        ).failure());
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
                sessionGain += track.lifecycleGain.value() * track.volumeGain.value();
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
            track.lifecycleGain.fadeTo(
                    0.0f, OVERFLOW_FADE_MS, AudioStreamFactory.SAMPLE_RATE
            );
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
                runtime.lifecycleGain.fadeTo(
                        1.0f, definition.fadeInMs(), AudioStreamFactory.SAMPLE_RATE
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
            TrackReadResult readResult = track.readFrames(trackPcm, frameCount);
            int framesRead = readResult.framesRead();

            for (int frame = 0; frame < frameCount; frame++) {
                float gain = track.lifecycleGain.value() * track.volumeGain.value() * masterGain;
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
                track.lifecycleGain.advance(1);
                track.volumeGain.advance(1);
            }

            if (readResult.terminalFailure() != null) {
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
                        readResult.terminalFailure()
                ).failure());
                continue;
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
                if (track.lifecycleGain.isComplete()
                        && track.lifecycleGain.target() == 0.0f) {
                    track.close();
                    trackIterator.remove();
                }
            }
            if (session.tracks.isEmpty()) {
                storeSnapshotAndClose(session);
                sessionIterator.remove();
            }
        }
    }

    private void storeSnapshotAndClose(AreaSession session) {
        if (session.snapshotWhenSilent && session.state.resumeOnReenter()) {
            ResumeKey key = session.resumeKey();
            if (currentSession == null || !currentSession.resumeKey().equals(key)) {
                resumeSnapshots.put(
                        key,
                        new SessionSnapshot(session.state, session.timeline.snapshot())
                );
            }
        }
        session.close();
    }

    private static void closeStream(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
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
        private final Map<Integer, PendingPreparation> preparations = new LinkedHashMap<>();
        private final boolean restored;
        private boolean outgoing;
        private boolean snapshotWhenSilent;
        private boolean snapshotInvalidated;
        private boolean expeditedRemoval;

        private AreaSession(
                long revision,
                PlaybackState state,
                AreaPlaybackTimeline timeline,
                boolean restored
        ) {
            this.revision = revision;
            this.state = state;
            this.timeline = timeline;
            this.restored = restored;
        }

        private ResumeKey resumeKey() {
            return new ResumeKey(revision, state.areaId());
        }

        private boolean hasRuntimeTrack(int trackIndex) {
            for (RuntimeTrack track : tracks) {
                if (track.trackIndex == trackIndex) {
                    return true;
                }
            }
            return false;
        }

        private void cancelPreparations() {
            for (PendingPreparation pending : preparations.values()) {
                CompletableFuture<AudioInputStream> future = pending.future();
                if (future.cancel(true)) {
                    continue;
                }
                try {
                    closeStream(future.getNow(null));
                } catch (CancellationException | CompletionException ignored) {
                }
            }
            preparations.clear();
        }

        @Override
        public void close() {
            cancelPreparations();
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
        private final FadeEnvelope lifecycleGain = new FadeEnvelope(0.0f);
        private final FadeEnvelope volumeGain;
        private AudioInputStream stream;
        private boolean exhausted;

        private RuntimeTrack(
                AreaSession session,
                int trackIndex,
                AreaTrackDefinition definition,
                Path path
        ) throws Exception {
            this(session, trackIndex, definition, path, streamFactory.open(path));
        }

        private RuntimeTrack(
                AreaSession session,
                int trackIndex,
                AreaTrackDefinition definition,
                Path path,
                AudioInputStream stream
        ) {
            this.session = session;
            this.trackIndex = trackIndex;
            this.definition = definition;
            this.path = path;
            this.volumeGain = new FadeEnvelope(definition.volume());
            this.stream = Objects.requireNonNull(stream, "stream");
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

        private TrackReadResult readFrames(byte[] destination, int requestedFrames) {
            int requestedBytes = requestedFrames * FRAME_SIZE;
            int totalBytes = 0;
            int consecutiveZeroReads = 0;
            boolean reopenedWithoutData = false;
            try {
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
            } catch (Exception exception) {
                return new TrackReadResult(totalBytes / FRAME_SIZE, exception);
            }
            return new TrackReadResult(totalBytes / FRAME_SIZE, null);
        }

        private boolean shouldRemove() {
            return exhausted
                    || session.outgoing
                    && lifecycleGain.isComplete()
                    && lifecycleGain.target() == 0.0f;
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

    private record TrackReadResult(int framesRead, Exception terminalFailure) {
    }

    private record PendingPreparation(
            int trackIndex,
            AreaTrackDefinition definition,
            Path path,
            CompletableFuture<AudioInputStream> future
    ) {
    }

    private record ResumeKey(long revision, String areaId) {
    }

    private record SessionSnapshot(
            PlaybackState state,
            AreaPlaybackTimeline.Snapshot timeline
    ) {
    }
}
