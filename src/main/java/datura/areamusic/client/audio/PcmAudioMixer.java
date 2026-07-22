package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

public final class PcmAudioMixer implements ClientAudioMixer {
    private static final int BLOCK_FRAMES = 1024;
    private static final int FRAME_SIZE = AudioStreamFactory.MIX_FORMAT.getFrameSize();
    private static final int BLOCK_BYTES = Math.multiplyExact(BLOCK_FRAMES, FRAME_SIZE);
    private static final int MAX_QUEUED_BLOCKS = 8;
    private static final long INITIAL_DEVICE_RETRY_MS = 250L;
    private static final long MAX_DEVICE_RETRY_MS = 5000L;

    private final Object signal = new Object();
    private final MusicLibrary initialLibrary;
    private final OutputFactory outputFactory;
    private final EngineFactory engineFactory;
    private final ErrorListener errorListener;

    private volatile boolean running;
    private volatile boolean paused;
    private volatile float masterGain = 1.0f;
    private volatile AudioOutput liveOutput;
    private Thread audioThread;
    private MusicLibrary pendingLibrary;
    private long pendingRevision = -1L;
    private PlaybackState pendingState;
    private boolean libraryPending;
    private boolean statePending;

    public PcmAudioMixer(MusicLibrary initialLibrary, ErrorListener errorListener) {
        this(initialLibrary, JavaSoundOutput::open, PcmAudioMixer::createEngine, errorListener);
    }

    PcmAudioMixer(MusicLibrary initialLibrary, OutputFactory outputFactory, ErrorListener errorListener) {
        this(initialLibrary, outputFactory, PcmAudioMixer::createEngine, errorListener);
    }

    PcmAudioMixer(
            MusicLibrary initialLibrary,
            OutputFactory outputFactory,
            EngineFactory engineFactory,
            ErrorListener errorListener
    ) {
        this.initialLibrary = Objects.requireNonNull(initialLibrary, "initialLibrary");
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
        this.engineFactory = Objects.requireNonNull(engineFactory, "engineFactory");
        this.errorListener = Objects.requireNonNull(errorListener, "errorListener");
    }

    public synchronized void start() {
        if (running) {
            return;
        }
        running = true;
        audioThread = new Thread(this::runAudioLoop, "AreaMusic audio mixer");
        audioThread.setDaemon(true);
        audioThread.start();
    }

    public void apply(long revision, PlaybackState state) {
        if (revision < 0L) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        PlaybackState checkedState = Objects.requireNonNull(state, "state");
        synchronized (signal) {
            pendingRevision = revision;
            pendingState = checkedState;
            statePending = true;
            signal.notifyAll();
        }
    }

    public void updateMusicLibrary(MusicLibrary musicLibrary) {
        synchronized (signal) {
            pendingLibrary = Objects.requireNonNull(musicLibrary, "musicLibrary");
            libraryPending = true;
            signal.notifyAll();
        }
    }

    public void setMasterGain(float gain) {
        if (!Float.isFinite(gain)) {
            throw new IllegalArgumentException("Master gain must be finite");
        }
        masterGain = Math.max(0.0f, Math.min(1.0f, gain));
    }

    public void setPaused(boolean paused) {
        this.paused = paused;
        synchronized (signal) {
            signal.notifyAll();
        }
    }

    @Override
    public synchronized void close() {
        if (!running && audioThread == null) {
            return;
        }
        running = false;
        synchronized (signal) {
            signal.notifyAll();
        }

        AudioOutput output = liveOutput;
        if (output != null) {
            output.close();
        }
        Thread thread = audioThread;
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
        audioThread = null;
        liveOutput = null;
    }

    private void runAudioLoop() {
        OutputState output = null;
        PcmQueue queue = new PcmQueue();
        long deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
        try (AudioEngine engine = engineFactory.create(initialLibrary)) {
            while (running) {
                PendingUpdate update = drainPendingUpdate();
                if (update.musicLibrary() != null) {
                    engine.setMusicLibrary(update.musicLibrary());
                }
                if (update.playbackState() != null) {
                    try {
                        engine.apply(update.revision(), update.playbackState());
                    } finally {
                        reportEngineFailures(engine);
                    }
                }

                if (paused) {
                    if (output != null && output.started) {
                        try {
                            output.output.stop();
                            output.started = false;
                            if (!running) {
                                break;
                            }
                            refreshConfirmedBytes(output, queue);
                        } catch (Exception exception) {
                            if (!running) {
                                break;
                            }
                            report(AudioFailure.Kind.DEVICE, "", exception);
                            closeOutputAfterFailure(output, queue);
                            output = null;
                        }
                    }
                    waitForSignal(50L);
                    continue;
                }
                if (!engine.hasWork() && queue.isEmpty()) {
                    if (output != null) {
                        try {
                            if (running) {
                                startOutput(output, queue);
                            }
                            if (running) {
                                refreshConfirmedBytes(output, queue);
                            }
                            if (running) {
                                output.output.drain();
                            }
                            if (running) {
                                queue.confirmThrough(output.writePositionBytes);
                            }
                        } catch (Exception exception) {
                            if (running) {
                                report(AudioFailure.Kind.DEVICE, "", exception);
                            }
                        } finally {
                            closeOutput(output.output);
                        }
                        output = null;
                        deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
                    }
                    if (!running) {
                        break;
                    }
                    waitForSignal(50L);
                    continue;
                }

                if (output == null) {
                    try {
                        AudioOutput openedOutput = outputFactory.open();
                        output = new OutputState(openedOutput);
                        liveOutput = openedOutput;
                    } catch (Exception exception) {
                        if (!running) {
                            break;
                        }
                        report(AudioFailure.Kind.DEVICE, "", exception);
                        waitForSignal(deviceRetryMs);
                        deviceRetryMs = nextRetryDelay(deviceRetryMs);
                        continue;
                    }
                }
                if (!running) {
                    break;
                }

                if (!output.started) {
                    try {
                        startOutput(output, queue);
                    } catch (Exception exception) {
                        if (!running) {
                            break;
                        }
                        report(AudioFailure.Kind.DEVICE, "", exception);
                        closeOutputAfterFailure(output, queue);
                        output = null;
                        waitForSignal(deviceRetryMs);
                        deviceRetryMs = nextRetryDelay(deviceRetryMs);
                        continue;
                    }
                }
                if (!running) {
                    break;
                }

                WriteSlice slice;
                try {
                    refreshConfirmedBytes(output, queue);
                    if (!running) {
                        break;
                    }
                    slice = queue.sliceAt(output.writePositionBytes);
                } catch (Exception exception) {
                    if (!running) {
                        break;
                    }
                    report(AudioFailure.Kind.DEVICE, "", exception);
                    closeOutputAfterFailure(output, queue);
                    output = null;
                    waitForSignal(deviceRetryMs);
                    deviceRetryMs = nextRetryDelay(deviceRetryMs);
                    continue;
                }

                if (slice == null && engine.hasWork() && !queue.hasCapacity()) {
                    waitForSignal(10L);
                    continue;
                }
                if (slice == null && engine.hasWork()) {
                    try {
                        queue.append(engine.renderFrames(BLOCK_FRAMES, masterGain));
                    } finally {
                        reportEngineFailures(engine);
                    }
                    if (!running) {
                        break;
                    }
                    slice = queue.sliceAt(output.writePositionBytes);
                }

                if (!running) {
                    break;
                }
                try {
                    if (slice == null) {
                        output.output.drain();
                        if (!running) {
                            break;
                        }
                        queue.confirmThrough(output.writePositionBytes);
                        closeOutput(output.output);
                        output = null;
                        deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
                        continue;
                    }

                    int written = output.output.write(slice.pcm(), slice.offset(), slice.length());
                    if (!running) {
                        break;
                    }
                    int remaining = slice.length();
                    if (written < 0 || written > remaining) {
                        throw new IllegalStateException(
                                "Audio output returned invalid byte count " + written
                                        + " for remaining length " + remaining
                        );
                    }
                    if (written % FRAME_SIZE != 0) {
                        throw new IllegalStateException(
                                "Audio output returned non-frame-aligned byte count " + written
                                        + " for frame size " + FRAME_SIZE
                        );
                    }
                    output.writePositionBytes = Math.addExact(output.writePositionBytes, written);
                    refreshConfirmedBytes(output, queue);
                    if (written == 0) {
                        waitForSignal(10L);
                        continue;
                    }
                    deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
                } catch (InterruptedException exception) {
                    if (running) {
                        report(AudioFailure.Kind.THREAD, "", exception);
                    }
                    Thread.currentThread().interrupt();
                } catch (Exception exception) {
                    if (!running) {
                        break;
                    }
                    report(AudioFailure.Kind.DEVICE, "", exception);
                    closeOutputAfterFailure(output, queue);
                    output = null;
                    waitForSignal(deviceRetryMs);
                    deviceRetryMs = nextRetryDelay(deviceRetryMs);
                }
            }
        } catch (LinkageError | RuntimeException error) {
            report(AudioFailure.Kind.THREAD, "", error);
        } finally {
            if (output != null) {
                closeOutput(output.output);
            }
            liveOutput = null;
            running = false;
        }
    }

    private static void startOutput(OutputState output, PcmQueue queue) {
        output.output.start();
        output.started = true;
        if (output.positionInitialized) {
            return;
        }

        long playedFrames = output.output.playedFrames();
        if (playedFrames < 0L) {
            throw new IllegalStateException("Audio output returned negative frame position " + playedFrames);
        }
        output.sequenceBaseBytes = queue.confirmedBytes;
        output.writePositionBytes = queue.confirmedBytes;
        output.playedBaselineFrames = playedFrames;
        output.lastPlayedRawFrames = playedFrames;
        output.positionInitialized = true;
    }

    private static void refreshConfirmedBytes(OutputState output, PcmQueue queue) {
        if (!output.positionInitialized) {
            return;
        }

        long playedFrames = output.output.playedFrames();
        if (playedFrames < output.lastPlayedRawFrames) {
            throw new IllegalStateException(
                    "Audio output frame position moved backwards from "
                            + output.lastPlayedRawFrames + " to " + playedFrames
            );
        }
        long elapsedFrames = Math.subtractExact(playedFrames, output.playedBaselineFrames);
        long elapsedBytes = Math.multiplyExact(
                elapsedFrames,
                (long) FRAME_SIZE
        );
        long confirmedThrough = Math.addExact(output.sequenceBaseBytes, elapsedBytes);
        if (confirmedThrough > output.writePositionBytes) {
            throw new IllegalStateException(
                    "Audio output played through byte " + confirmedThrough
                            + " beyond accepted byte " + output.writePositionBytes
            );
        }
        queue.confirmThrough(confirmedThrough);
        output.lastPlayedRawFrames = playedFrames;
    }

    private void closeOutputAfterFailure(OutputState output, PcmQueue queue) {
        try {
            output.output.stop();
            output.started = false;
        } catch (Exception ignored) {
        }
        try {
            refreshConfirmedBytes(output, queue);
        } catch (Exception ignored) {
        }
        closeOutput(output.output);
    }

    private void closeOutput(AudioOutput output) {
        try {
            output.close();
        } catch (Exception exception) {
            if (running) {
                report(AudioFailure.Kind.DEVICE, "", exception);
            }
        } finally {
            if (liveOutput == output) {
                liveOutput = null;
            }
        }
    }

    private static long nextRetryDelay(long currentDelayMs) {
        return Math.min(MAX_DEVICE_RETRY_MS, currentDelayMs * 2L);
    }

    private void report(AudioFailure.Kind kind, String musicId, Throwable error) {
        errorListener.onError(new AudioFailure(kind, musicId, error));
    }

    private void reportEngineFailures(AudioEngine engine) {
        for (AudioFailure failure : engine.drainFailures()) {
            errorListener.onError(failure);
        }
    }

    private PendingUpdate drainPendingUpdate() {
        synchronized (signal) {
            MusicLibrary library = libraryPending ? pendingLibrary : null;
            long revision = statePending ? pendingRevision : -1L;
            PlaybackState state = statePending ? pendingState : null;
            pendingLibrary = null;
            pendingRevision = -1L;
            pendingState = null;
            libraryPending = false;
            statePending = false;
            return new PendingUpdate(library, revision, state);
        }
    }

    private void waitForSignal(long timeoutMs) {
        synchronized (signal) {
            if (!running || libraryPending || statePending) {
                return;
            }
            try {
                signal.wait(timeoutMs);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @FunctionalInterface
    public interface ErrorListener {
        void onError(AudioFailure failure);
    }

    @FunctionalInterface
    interface OutputFactory {
        AudioOutput open() throws Exception;
    }

    @FunctionalInterface
    interface EngineFactory {
        AudioEngine create(MusicLibrary musicLibrary);
    }

    interface AudioEngine extends AutoCloseable {
        void setMusicLibrary(MusicLibrary musicLibrary);

        void apply(long revision, PlaybackState state);

        byte[] renderFrames(int frameCount, float masterGain);

        java.util.List<AudioFailure> drainFailures();

        boolean hasWork();

        @Override
        void close();
    }

    interface AudioOutput extends AutoCloseable {
        void start();

        void stop();

        int write(byte[] pcm, int offset, int length) throws InterruptedException;

        default long playedFrames() {
            return 0L;
        }

        void drain();

        @Override
        void close();
    }

    private record PendingUpdate(
            MusicLibrary musicLibrary,
            long revision,
            PlaybackState playbackState
    ) {
    }

    private record PcmBlock(long startBytes, byte[] pcm) {
        private long endBytes() {
            return Math.addExact(startBytes, pcm.length);
        }
    }

    private record WriteSlice(byte[] pcm, int offset, int length) {
    }

    private static final class PcmQueue {
        private final Deque<PcmBlock> blocks = new ArrayDeque<>();
        private long confirmedBytes;
        private long renderedBytes;

        private boolean isEmpty() {
            return blocks.isEmpty();
        }

        private boolean hasCapacity() {
            return blocks.size() < MAX_QUEUED_BLOCKS;
        }

        private void append(byte[] pcm) {
            Objects.requireNonNull(pcm, "pcm");
            if (pcm.length != BLOCK_BYTES) {
                throw new IllegalStateException(
                        "Rendered PCM byte count mismatch: expected=" + BLOCK_BYTES
                                + ", actual=" + pcm.length
                );
            }
            if (!hasCapacity()) {
                throw new IllegalStateException("PCM confirmation queue is full");
            }
            if (pcm.length % FRAME_SIZE != 0) {
                throw new IllegalStateException(
                        "Rendered PCM byte count " + pcm.length
                                + " is not aligned to frame size " + FRAME_SIZE
                );
            }
            blocks.addLast(new PcmBlock(renderedBytes, pcm));
            renderedBytes = Math.addExact(renderedBytes, pcm.length);
        }

        private WriteSlice sliceAt(long positionBytes) {
            if (positionBytes == renderedBytes) {
                return null;
            }
            for (PcmBlock block : blocks) {
                if (positionBytes < block.startBytes() || positionBytes >= block.endBytes()) {
                    continue;
                }
                int offset = Math.toIntExact(positionBytes - block.startBytes());
                return new WriteSlice(block.pcm(), offset, block.pcm().length - offset);
            }
            throw new IllegalStateException("No queued PCM at byte " + positionBytes);
        }

        private void confirmThrough(long positionBytes) {
            if (positionBytes < confirmedBytes || positionBytes > renderedBytes) {
                throw new IllegalStateException(
                        "Invalid confirmed PCM position " + positionBytes
                                + " for range " + confirmedBytes + ".." + renderedBytes
                );
            }
            confirmedBytes = positionBytes;
            while (!blocks.isEmpty() && blocks.getFirst().endBytes() <= confirmedBytes) {
                blocks.removeFirst();
            }
        }
    }

    private static final class OutputState {
        private final AudioOutput output;
        private boolean started;
        private boolean positionInitialized;
        private long sequenceBaseBytes;
        private long writePositionBytes;
        private long playedBaselineFrames;
        private long lastPlayedRawFrames;

        private OutputState(AudioOutput output) {
            this.output = Objects.requireNonNull(output, "output");
        }
    }

    private static AudioEngine createEngine(MusicLibrary musicLibrary) {
        return new PcmAudioEngine(new PcmMixerEngine(new AudioStreamFactory(), musicLibrary));
    }

    private record PcmAudioEngine(PcmMixerEngine delegate) implements AudioEngine {
        private PcmAudioEngine {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
            delegate.setMusicLibrary(musicLibrary);
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            delegate.apply(revision, state);
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            return delegate.renderFrames(frameCount, masterGain);
        }

        @Override
        public java.util.List<AudioFailure> drainFailures() {
            return delegate.drainFailures();
        }

        @Override
        public boolean hasWork() {
            return delegate.hasWork();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private static final class JavaSoundOutput implements AudioOutput {
        private final SourceDataLine line;

        private JavaSoundOutput(SourceDataLine line) {
            this.line = line;
        }

        private static AudioOutput open() throws Exception {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, AudioStreamFactory.MIX_FORMAT);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            line.open(
                    AudioStreamFactory.MIX_FORMAT,
                    BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize() * 8
            );
            return new JavaSoundOutput(line);
        }

        @Override
        public void start() {
            line.start();
        }

        @Override
        public void stop() {
            line.stop();
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            return line.write(pcm, offset, length);
        }

        @Override
        public long playedFrames() {
            return line.getLongFramePosition();
        }

        @Override
        public void drain() {
            if (line.isOpen()) {
                line.drain();
            }
        }

        @Override
        public void close() {
            if (line.isOpen()) {
                line.stop();
                line.flush();
                line.close();
            }
        }
    }
}
