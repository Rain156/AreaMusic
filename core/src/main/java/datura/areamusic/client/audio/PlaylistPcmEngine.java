package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import datura.areamusic.playback.PlaylistLoopPlayback;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PlaylistPcmEngine implements PcmPlaybackEngine {
    private static final int CHANNELS = AudioStreamFactory.MIX_FORMAT.getChannels();
    private static final int FRAME_SIZE = AudioStreamFactory.MIX_FORMAT.getFrameSize();
    private static final int MAX_CONSECUTIVE_ZERO_READS = 64;
    private static final int MAX_LIVE_SESSIONS = 4;
    private static final int OVERFLOW_FADE_MS = 20;

    private final AudioStreamPreparation streamPreparer;
    private final boolean ownsStreamPreparer;
    private final List<AudioFailure> failures = new ArrayList<>();
    private final List<Session> outgoingSessions = new ArrayList<>();
    private final Map<ResumeKey, SessionSnapshot> resumeSnapshots = new HashMap<>();
    private MusicLibrary musicLibrary;
    private PlaybackState currentState = PlaybackState.stopped();
    private Session currentSession;
    private long lastRevision = -1L;
    private boolean closed;

    public PlaylistPcmEngine(AudioStreamFactory streamFactory, MusicLibrary musicLibrary) {
        this(
                requireLibrary(musicLibrary),
                new AudioStreamPreparer(Objects.requireNonNull(streamFactory, "streamFactory"), 2),
                true
        );
    }

    PlaylistPcmEngine(
            AudioStreamFactory streamFactory,
            MusicLibrary musicLibrary,
            AudioStreamPreparation streamPreparer
    ) {
        this(streamFactory, musicLibrary, streamPreparer, false);
    }

    PlaylistPcmEngine(
            AudioStreamFactory streamFactory,
            MusicLibrary musicLibrary,
            AudioStreamPreparation streamPreparer,
            boolean ownsStreamPreparer
    ) {
        this(
                requireLibrary(musicLibrary),
                requirePreparation(streamFactory, streamPreparer),
                ownsStreamPreparer
        );
    }

    private PlaylistPcmEngine(
            MusicLibrary musicLibrary,
            AudioStreamPreparation streamPreparer,
            boolean ownsStreamPreparer
    ) {
        this.musicLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
        this.streamPreparer = Objects.requireNonNull(streamPreparer, "streamPreparer");
        this.ownsStreamPreparer = ownsStreamPreparer;
    }

    public void setMusicLibrary(MusicLibrary musicLibrary) {
        ensureOpen();
        MusicLibrary replacement = Objects.requireNonNull(musicLibrary, "musicLibrary");
        resumeSnapshots.clear();
        closeCurrentSession();
        for (Session session : outgoingSessions) {
            closeSession(session);
        }
        outgoingSessions.clear();
        this.musicLibrary = replacement;
    }

    public void apply(long revision, PlaybackState state) {
        ensureOpen();
        Objects.requireNonNull(state, "state");
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        PlaylistLoopPlayback definition = null;
        if (state.playing()) {
            definition = state.definition()
                    .filter(PlaylistLoopPlayback.class::isInstance)
                    .map(PlaylistLoopPlayback.class::cast)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "PlaylistPcmEngine accepts only PLAYLIST_LOOP playback"
                    ));
        }
        if (revision != lastRevision) {
            invalidateResumeState();
        }
        if (!state.playing()) {
            if (revision == lastRevision && state.equals(currentState)) {
                return;
            }
            lastRevision = revision;
            currentState = state;
            Session previous = currentSession;
            currentSession = null;
            moveToOutgoing(previous);
            return;
        }
        invalidateConflictingResumeState(revision, state);
        if (revision == lastRevision && state.equals(currentState) && currentSession != null) {
            return;
        }
        lastRevision = revision;
        currentState = state;
        Session previous = currentSession;
        currentSession = null;
        Session incoming = reclaimOutgoingSession(revision, state);
        moveToOutgoing(previous);
        if (incoming == null) {
            incoming = createIncomingSession(revision, state, definition);
            currentSession = incoming;
            startOrGateCurrent(incoming);
        } else {
            currentSession = incoming;
            if (incoming.current == null) {
                startOrGateCurrent(incoming);
            } else {
                scheduleNext(incoming);
            }
        }
    }

    public byte[] renderFrames(int frameCount, float masterGain) {
        ensureOpen();
        if (frameCount < 0) {
            throw new IllegalArgumentException("Frame count must not be negative");
        }
        if (!Float.isFinite(masterGain) || masterGain < 0.0f || masterGain > 1.0f) {
            throw new IllegalArgumentException("Master gain must be finite and between 0 and 1");
        }
        int byteCount = Math.multiplyExact(frameCount, FRAME_SIZE);
        int[] mixed = new int[Math.multiplyExact(frameCount, CHANNELS)];
        removeSilentOutgoingSessions();
        activateGatedCurrentIfCapacity();
        for (Session outgoing : outgoingSessions) {
            renderOutgoing(outgoing, mixed, frameCount, masterGain);
        }
        removeSilentOutgoingSessions();
        int outputFrame = 0;
        while (outputFrame < frameCount) {
            pollCurrent();
            Session session = currentSession;
            if (session == null || session.current == null) {
                break;
            }

            ReadResult result = session.current.readFrames(frameCount - outputFrame);
            session.timeline.recordFramesRead(result.framesRead());
            mixSessionFrames(
                    session,
                    mixed,
                    outputFrame,
                    result.pcm(),
                    result.framesRead(),
                    masterGain
            );
            outputFrame += result.framesRead();
            if (result.terminalFailure() != null) {
                int failedIndex = session.timeline.currentIndex();
                String musicId = session.definition.playlist().get(failedIndex);
                reportFailure(
                        session,
                        failedIndex,
                        AudioFailure.Kind.DECODE,
                        musicId,
                        "Could not decode " + musicId,
                        result.terminalFailure()
                );
                closeStream(session.current.stream);
                session.current = null;
                if (!advanceAndPromote(session)) {
                    break;
                }
                continue;
            }
            if (!result.endOfStream()) {
                break;
            }

            closeStream(session.current.stream);
            session.current = null;
            if (!advanceAndPromote(session)) {
                break;
            }
        }
        activateGatedCurrentIfCapacity();
        pollCurrent();

        byte[] output = new byte[byteCount];
        for (int sample = 0; sample < mixed.length; sample++) {
            PcmMath.writeLittleEndian(output, sample * 2, mixed[sample]);
        }
        return output;
    }

    public List<AudioFailure> drainFailures() {
        ensureOpen();
        List<AudioFailure> drained = List.copyOf(failures);
        failures.clear();
        return drained;
    }

    public boolean hasWork() {
        ensureOpen();
        Session session = currentSession;
        return session != null
                && (session.gatedForCapacity
                || session.current != null
                || session.currentPreparation != null
                || session.nextPreparation != null
                || !session.timeline.allFailed())
                || outgoingSessions.stream().anyMatch(outgoing -> outgoing.current != null);
    }

    public PlaybackState currentState() {
        ensureOpen();
        return currentState;
    }

    int liveSessionCount() {
        ensureOpen();
        return resourceOwningSessionCount();
    }

    int retainedSessionCount() {
        ensureOpen();
        return resourceOwningSessionCount();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        resumeSnapshots.clear();
        closeCurrentSession();
        for (Session session : outgoingSessions) {
            closeSession(session);
        }
        outgoingSessions.clear();
        failures.clear();
        currentState = PlaybackState.stopped();
        lastRevision = -1L;
        if (ownsStreamPreparer) {
            streamPreparer.close();
        }
    }

    private void pollCurrent() {
        Session session = currentSession;
        if (session == null) {
            return;
        }
        if (session.gatedForCapacity) {
            return;
        }
        if (session.current != null) {
            pollNextPreparation(session);
            return;
        }
        for (int attempts = 0; attempts < session.timeline.entryCount(); attempts++) {
            if (session.currentPreparation == null) {
                scheduleCurrent(session);
            }
            PendingPreparation pending = session.currentPreparation;
            if (pending == null || !pending.isDone()) {
                return;
            }
            session.currentPreparation = null;
            try {
                session.current = new RuntimeStream(
                        pending.claim(),
                        session.timeline.framePosition() > 0L
                );
                scheduleNext(session);
                pollNextPreparation(session);
                return;
            } catch (CancellationException | CompletionException exception) {
                Throwable cause = exception instanceof CompletionException
                        && exception.getCause() != null
                        ? exception.getCause()
                        : exception;
                String musicId = session.definition.playlist().get(pending.index);
                reportFailure(
                        session,
                        pending.index,
                        AudioFailure.Kind.DECODE,
                        musicId,
                        "Could not prepare " + musicId,
                        cause
                );
                if (session.timeline.advanceToNextPlayable().isEmpty()) {
                    return;
                }
            }
        }
    }

    private void pollNextPreparation(Session session) {
        for (int attempts = 0; attempts < session.timeline.entryCount(); attempts++) {
            if (session.nextPreparation == null) {
                scheduleNext(session);
            }
            PendingPreparation pending = session.nextPreparation;
            if (pending == null || !pending.isDone()) {
                return;
            }
            try {
                pending.peek();
                return;
            } catch (CancellationException | CompletionException exception) {
                session.nextPreparation = null;
                Throwable cause = exception instanceof CompletionException
                        && exception.getCause() != null
                        ? exception.getCause()
                        : exception;
                String musicId = session.definition.playlist().get(pending.index);
                reportFailure(
                        session,
                        pending.index,
                        AudioFailure.Kind.DECODE,
                        musicId,
                        "Could not prepare " + musicId,
                        cause
                );
            }
        }
    }

    private void scheduleCurrent(Session session) {
        for (int attempts = 0; attempts < session.timeline.entryCount(); attempts++) {
            int index = session.timeline.currentIndex();
            if (session.timeline.failed(index)) {
                if (session.timeline.advanceToNextPlayable().isEmpty()) {
                    return;
                }
                continue;
            }
            String musicId = session.definition.playlist().get(index);
            Path path = musicLibrary.find(musicId).orElse(null);
            if (path == null) {
                reportFailure(
                        session,
                        index,
                        AudioFailure.Kind.MISSING_FILE,
                        musicId,
                        "Local MusicID is missing: " + musicId,
                        null
                );
                if (session.timeline.advanceToNextPlayable().isEmpty()) {
                    return;
                }
                continue;
            }
            try {
                session.currentPreparation = new PendingPreparation(
                        index, streamPreparer.prepare(path, session.timeline.framePosition())
                );
                return;
            } catch (RuntimeException exception) {
                reportFailure(
                        session,
                        index,
                        AudioFailure.Kind.DECODE,
                        musicId,
                        "Could not prepare " + musicId,
                        exception
                );
                if (session.timeline.advanceToNextPlayable().isEmpty()) {
                    return;
                }
            }
        }
    }

    private void renderOutgoing(
            Session session,
            int[] mixed,
            int frameCount,
            float masterGain
    ) {
        int sessionFrameCount = frameCount;
        if (session.lifecycleGain.target() == 0.0f) {
            sessionFrameCount = (int) Math.min(
                    frameCount,
                    session.lifecycleGain.framesUntilComplete()
            );
        }
        int mixedFrames = 0;
        RuntimeStream current = session.current;
        if (current != null && sessionFrameCount > 0) {
            ReadResult result = current.readFrames(sessionFrameCount);
            session.timeline.recordFramesRead(result.framesRead());
            mixSessionFrames(
                    session,
                    mixed,
                    0,
                    result.pcm(),
                    result.framesRead(),
                    masterGain
            );
            mixedFrames = result.framesRead();
            if (result.terminalFailure() != null) {
                int failedIndex = session.timeline.currentIndex();
                String musicId = session.definition.playlist().get(failedIndex);
                reportFailure(
                        session,
                        failedIndex,
                        AudioFailure.Kind.DECODE,
                        musicId,
                        "Could not decode " + musicId,
                        result.terminalFailure()
                );
                closeStream(current.stream);
                session.current = null;
                session.timeline.advanceToNextPlayable();
            } else if (result.endOfStream()) {
                closeStream(current.stream);
                session.current = null;
                session.timeline.advanceToNextPlayable();
            }
        }
        if (mixedFrames < sessionFrameCount) {
            session.lifecycleGain.advance(sessionFrameCount - mixedFrames);
        }
    }

    private void moveToOutgoing(Session session) {
        if (session == null) {
            return;
        }
        cancel(session.currentPreparation);
        session.currentPreparation = null;
        cancel(session.nextPreparation);
        session.nextPreparation = null;
        session.snapshotWhenSilent = session.state.resumeOnReenter()
                && !session.snapshotInvalidated;
        session.lifecycleGain.fadeTo(
                0.0f,
                session.definition.fadeOutMs(),
                AudioStreamFactory.SAMPLE_RATE
        );
        if (session.current == null
                || session.lifecycleGain.isComplete()
                && session.lifecycleGain.target() == 0.0f) {
            storeSnapshotAndClose(session);
            return;
        }
        outgoingSessions.add(session);
    }

    private Session reclaimOutgoingSession(long revision, PlaybackState state) {
        if (!state.resumeOnReenter()) {
            return null;
        }
        for (int index = outgoingSessions.size() - 1; index >= 0; index--) {
            Session session = outgoingSessions.get(index);
            if (session.revision != revision
                    || session.snapshotInvalidated
                    || !session.state.equals(state)) {
                continue;
            }
            outgoingSessions.remove(index);
            resumeSnapshots.remove(session.resumeKey());
            session.snapshotWhenSilent = false;
            session.expeditedRemoval = false;
            session.lifecycleGain.fadeTo(
                    1.0f,
                    session.definition.fadeInMs(),
                    AudioStreamFactory.SAMPLE_RATE
            );
            return session;
        }
        return null;
    }

    private void startOrGateCurrent(Session session) {
        if (resourceOwningSessionCount() >= MAX_LIVE_SESSIONS) {
            session.gatedForCapacity = true;
            ensureCapacityReleaseScheduled();
            return;
        }
        session.gatedForCapacity = false;
        scheduleCurrent(session);
    }

    private void activateGatedCurrentIfCapacity() {
        Session session = currentSession;
        if (session == null || !session.gatedForCapacity) {
            return;
        }
        if (resourceOwningSessionCount() >= MAX_LIVE_SESSIONS) {
            ensureCapacityReleaseScheduled();
            return;
        }
        session.gatedForCapacity = false;
        scheduleCurrent(session);
    }

    private void ensureCapacityReleaseScheduled() {
        for (Session session : outgoingSessions) {
            if (session.expeditedRemoval && session.current != null) {
                return;
            }
        }
        expediteQuietestOutgoingSession();
    }

    private int resourceOwningSessionCount() {
        int count = currentSession != null && currentSession.ownsResources() ? 1 : 0;
        for (Session session : outgoingSessions) {
            if (session.ownsResources()) {
                count++;
            }
        }
        return count;
    }

    private boolean expediteQuietestOutgoingSession() {
        Session quietest = null;
        float quietestGain = Float.POSITIVE_INFINITY;
        for (Session session : outgoingSessions) {
            if (session.expeditedRemoval || session.current == null) {
                continue;
            }
            float gain = session.lifecycleGain.value() * session.definition.volume();
            if (quietest == null || gain < quietestGain) {
                quietest = session;
                quietestGain = gain;
            }
        }
        if (quietest == null) {
            return false;
        }
        quietest.expeditedRemoval = true;
        quietest.lifecycleGain.fadeTo(
                0.0f,
                OVERFLOW_FADE_MS,
                AudioStreamFactory.SAMPLE_RATE
        );
        return true;
    }

    private void removeSilentOutgoingSessions() {
        var iterator = outgoingSessions.iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next();
            if (session.current == null
                    || session.lifecycleGain.isComplete()
                    && session.lifecycleGain.target() == 0.0f) {
                storeSnapshotAndClose(session);
                iterator.remove();
            }
        }
    }

    private Session createIncomingSession(
            long revision,
            PlaybackState state,
            PlaylistLoopPlayback definition
    ) {
        ResumeKey key = new ResumeKey(revision, state.areaId());
        SessionSnapshot snapshot = state.resumeOnReenter()
                ? resumeSnapshots.remove(key)
                : null;
        PlaylistPlaybackTimeline timeline = snapshot != null
                && snapshot.state().equals(state)
                ? PlaylistPlaybackTimeline.restore(
                        snapshot.timeline(), definition.playlist().size()
                )
                : PlaylistPlaybackTimeline.fresh(definition.playlist().size());
        return new Session(revision, state, definition, timeline);
    }

    private void invalidateResumeState() {
        resumeSnapshots.clear();
        if (currentSession != null) {
            currentSession.snapshotInvalidated = true;
            currentSession.snapshotWhenSilent = false;
        }
        for (Session session : outgoingSessions) {
            session.snapshotInvalidated = true;
            session.snapshotWhenSilent = false;
        }
    }

    private void invalidateConflictingResumeState(long revision, PlaybackState state) {
        ResumeKey key = new ResumeKey(revision, state.areaId());
        SessionSnapshot snapshot = resumeSnapshots.get(key);
        if (snapshot != null && !snapshot.state().equals(state)) {
            resumeSnapshots.remove(key);
        }
        if (currentSession != null
                && currentSession.resumeKey().equals(key)
                && !currentSession.state.equals(state)) {
            currentSession.snapshotInvalidated = true;
            currentSession.snapshotWhenSilent = false;
        }
        for (Session session : outgoingSessions) {
            if (session.resumeKey().equals(key) && !session.state.equals(state)) {
                session.snapshotInvalidated = true;
                session.snapshotWhenSilent = false;
            }
        }
    }

    private void storeSnapshotAndClose(Session session) {
        if (session.snapshotWhenSilent
                && !session.snapshotInvalidated
                && session.state.resumeOnReenter()) {
            ResumeKey key = session.resumeKey();
            if (currentSession == null || !currentSession.resumeKey().equals(key)) {
                resumeSnapshots.put(
                        key,
                        new SessionSnapshot(session.state, session.timeline.snapshot())
                );
            }
        }
        closeSession(session);
    }

    private boolean advanceAndPromote(Session session) {
        if (session.timeline.advanceToNextPlayable().isEmpty()) {
            cancel(session.nextPreparation);
            session.nextPreparation = null;
            return false;
        }
        if (session.nextPreparation != null
                && session.nextPreparation.index == session.timeline.currentIndex()) {
            session.currentPreparation = session.nextPreparation;
            session.nextPreparation = null;
        } else {
            cancel(session.nextPreparation);
            session.nextPreparation = null;
            scheduleCurrent(session);
        }
        return true;
    }

    private void scheduleNext(Session session) {
        for (int attempts = 0; attempts < session.timeline.entryCount(); attempts++) {
            int index = session.timeline.nextPlayableIndex().orElse(-1);
            if (index < 0) {
                return;
            }
            String musicId = session.definition.playlist().get(index);
            Path path = musicLibrary.find(musicId).orElse(null);
            if (path == null) {
                reportFailure(
                        session,
                        index,
                        AudioFailure.Kind.MISSING_FILE,
                        musicId,
                        "Local MusicID is missing: " + musicId,
                        null
                );
                continue;
            }
            try {
                session.nextPreparation = new PendingPreparation(
                        index, streamPreparer.prepare(path, 0L)
                );
                return;
            } catch (RuntimeException exception) {
                reportFailure(
                        session,
                        index,
                        AudioFailure.Kind.DECODE,
                        musicId,
                        "Could not prepare " + musicId,
                        exception
                );
            }
        }
    }

    private void reportFailure(
            Session session,
            int index,
            AudioFailure.Kind kind,
            String musicId,
            String message,
            Throwable cause
    ) {
        if (!session.timeline.markFailed(index)) {
            return;
        }
        Throwable failure = cause == null
                ? new IOException(message)
                : new IOException(message, cause);
        failures.add(new AudioFailure(kind, musicId, failure));
    }

    private void closeCurrentSession() {
        Session session = currentSession;
        currentSession = null;
        closeSession(session);
    }

    private void closeSession(Session session) {
        if (session == null) {
            return;
        }
        cancel(session.currentPreparation);
        cancel(session.nextPreparation);
        if (session.current != null) {
            closeStream(session.current.stream);
        }
    }

    private static void cancel(PendingPreparation pending) {
        if (pending != null) {
            pending.abandon();
        }
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

    private static AudioStreamPreparation requirePreparation(
            AudioStreamFactory streamFactory,
            AudioStreamPreparation streamPreparer
    ) {
        Objects.requireNonNull(streamFactory, "streamFactory");
        return Objects.requireNonNull(streamPreparer, "streamPreparer");
    }

    private static MusicLibrary requireLibrary(MusicLibrary musicLibrary) {
        return Objects.requireNonNull(musicLibrary, "musicLibrary");
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("Playlist PCM engine is closed");
        }
    }

    private static void mixSessionFrames(
            Session session,
            int[] mixed,
            int outputFrameOffset,
            byte[] pcm,
            int frameCount,
            float masterGain
    ) {
        for (int frame = 0; frame < frameCount; frame++) {
            float gain = session.lifecycleGain.value()
                    * session.definition.volume()
                    * masterGain;
            int sourceOffset = frame * FRAME_SIZE;
            int mixedSample = (outputFrameOffset + frame) * CHANNELS;
            mixed[mixedSample] += PcmMath.scale(
                    PcmMath.readLittleEndian(pcm, sourceOffset), gain
            );
            mixed[mixedSample + 1] += PcmMath.scale(
                    PcmMath.readLittleEndian(pcm, sourceOffset + 2), gain
            );
            session.lifecycleGain.advance(1);
        }
    }

    private static final class Session {
        private final long revision;
        private final PlaybackState state;
        private final PlaylistLoopPlayback definition;
        private final PlaylistPlaybackTimeline timeline;
        private final FadeEnvelope lifecycleGain = new FadeEnvelope(0.0f);
        private PendingPreparation currentPreparation;
        private PendingPreparation nextPreparation;
        private RuntimeStream current;
        private boolean snapshotWhenSilent;
        private boolean snapshotInvalidated;
        private boolean expeditedRemoval;
        private boolean gatedForCapacity;

        private Session(
                long revision,
                PlaybackState state,
                PlaylistLoopPlayback definition,
                PlaylistPlaybackTimeline timeline
        ) {
            this.revision = revision;
            this.state = state;
            this.definition = definition;
            this.timeline = timeline;
            lifecycleGain.fadeTo(
                    1.0f,
                    definition.fadeInMs(),
                    AudioStreamFactory.SAMPLE_RATE
            );
        }

        private ResumeKey resumeKey() {
            return new ResumeKey(revision, state.areaId());
        }

        private boolean ownsResources() {
            return current != null
                    || currentPreparation != null
                    || nextPreparation != null;
        }
    }

    private static final class PendingPreparation {
        private final int index;
        private final CompletableFuture<AudioInputStream> future;
        private final AtomicBoolean abandoned = new AtomicBoolean();
        private final AtomicBoolean streamClaimed = new AtomicBoolean();

        private PendingPreparation(
                int index,
                CompletableFuture<AudioInputStream> future
        ) {
            this.index = index;
            this.future = Objects.requireNonNull(future, "preparation future");
            future.whenComplete((stream, failure) -> {
                if (stream != null && abandoned.get()) {
                    closeIfUnclaimed(stream);
                }
            });
        }

        private boolean isDone() {
            return future.isDone();
        }

        private AudioInputStream claim() {
            AudioInputStream stream = completedStream();
            if (!streamClaimed.compareAndSet(false, true)) {
                throw new CancellationException("Prepared stream is no longer available");
            }
            return stream;
        }

        private AudioInputStream peek() {
            return completedStream();
        }

        private AudioInputStream completedStream() {
            AudioInputStream stream = future.join();
            if (stream == null) {
                throw new CompletionException(
                        new IOException("Preparation completed without an audio stream")
                );
            }
            return stream;
        }

        private void abandon() {
            abandoned.set(true);
            future.cancel(true);
            if (!future.isDone()) {
                return;
            }
            try {
                AudioInputStream stream = future.join();
                if (stream != null) {
                    closeIfUnclaimed(stream);
                }
            } catch (CancellationException | CompletionException ignored) {
            }
        }

        private void closeIfUnclaimed(AudioInputStream stream) {
            if (streamClaimed.compareAndSet(false, true)) {
                closeStream(stream);
            }
        }
    }

    private static final class RuntimeStream {
        private final AudioInputStream stream;
        private boolean hasDecodedFrames;

        private RuntimeStream(AudioInputStream stream, boolean hasDecodedFrames) {
            this.stream = Objects.requireNonNull(stream, "prepared stream");
            this.hasDecodedFrames = hasDecodedFrames;
        }

        private ReadResult readFrames(int requestedFrames) {
            byte[] pcm = new byte[Math.multiplyExact(requestedFrames, FRAME_SIZE)];
            int totalBytes = 0;
            boolean endOfStream = false;
            int consecutiveZeroReads = 0;
            Exception terminalFailure = null;
            try {
                while (totalBytes < pcm.length) {
                    int requestedBytes = pcm.length - totalBytes;
                    int read = stream.read(pcm, totalBytes, requestedBytes);
                    if (read < 0) {
                        if (!hasDecodedFrames && totalBytes == 0) {
                            terminalFailure = new IOException("Decoded stream is empty");
                        } else {
                            endOfStream = true;
                        }
                        break;
                    }
                    if (read == 0) {
                        consecutiveZeroReads++;
                        if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                            throw new IOException(
                                    "Decoder returned zero bytes " + consecutiveZeroReads
                                            + " consecutive times"
                            );
                        }
                        continue;
                    }
                    if (read > requestedBytes) {
                        throw new IOException("Decoder returned invalid read count " + read);
                    }
                    if (read % FRAME_SIZE != 0) {
                        throw new IOException("Decoder returned a partial PCM frame");
                    }
                    totalBytes += read;
                    hasDecodedFrames = true;
                    consecutiveZeroReads = 0;
                }
            } catch (Exception exception) {
                terminalFailure = exception;
            }
            return new ReadResult(
                    pcm,
                    totalBytes / FRAME_SIZE,
                    endOfStream,
                    terminalFailure
            );
        }
    }

    private record ReadResult(
            byte[] pcm,
            int framesRead,
            boolean endOfStream,
            Exception terminalFailure
    ) {
    }

    private record ResumeKey(long revision, String areaId) {
    }

    private record SessionSnapshot(
            PlaybackState state,
            PlaylistPlaybackTimeline.Snapshot timeline
    ) {
    }
}
