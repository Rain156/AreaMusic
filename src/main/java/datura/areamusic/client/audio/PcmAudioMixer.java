package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;
import java.util.Objects;

public final class PcmAudioMixer implements ClientAudioMixer {
    private static final int BLOCK_FRAMES = 1024;
    private static final long INITIAL_DEVICE_RETRY_MS = 250L;
    private static final long MAX_DEVICE_RETRY_MS = 5000L;

    private final Object signal = new Object();
    private final MusicLibrary initialLibrary;
    private final OutputFactory outputFactory;
    private final ErrorListener errorListener;

    private volatile boolean running;
    private volatile boolean paused;
    private volatile float masterGain = 1.0f;
    private volatile AudioOutput liveOutput;
    private Thread audioThread;
    private MusicLibrary pendingLibrary;
    private PlaybackState pendingState;
    private boolean libraryPending;
    private boolean statePending;

    public PcmAudioMixer(MusicLibrary initialLibrary, ErrorListener errorListener) {
        this(initialLibrary, JavaSoundOutput::open, errorListener);
    }

    PcmAudioMixer(MusicLibrary initialLibrary, OutputFactory outputFactory, ErrorListener errorListener) {
        this.initialLibrary = Objects.requireNonNull(initialLibrary, "initialLibrary");
        this.outputFactory = Objects.requireNonNull(outputFactory, "outputFactory");
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

    public void apply(PlaybackState state) {
        synchronized (signal) {
            pendingState = Objects.requireNonNull(state, "state");
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
        AudioOutput output = null;
        boolean outputStarted = false;
        long deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
        try (PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), initialLibrary)) {
            while (running) {
                PendingUpdate update = drainPendingUpdate();
                if (update.musicLibrary() != null) {
                    engine.setMusicLibrary(update.musicLibrary());
                }
                if (update.playbackState() != null) {
                    try {
                        engine.apply(update.playbackState());
                    } catch (PcmMixerEngine.AudioPlaybackException exception) {
                        errorListener.onError(exception.failure());
                    }
                }

                if (paused) {
                    if (output != null && outputStarted) {
                        output.stop();
                        outputStarted = false;
                    }
                    waitForSignal(50L);
                    continue;
                }
                if (!engine.hasTracks()) {
                    if (output != null) {
                        if (!outputStarted) {
                            try {
                                output.start();
                                outputStarted = true;
                            } catch (Exception exception) {
                                report(AudioFailure.Kind.DEVICE, "", exception);
                                closeOutput(output);
                                output = null;
                                deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
                                continue;
                            }
                        }
                        drainAndCloseOutput(output);
                        output = null;
                        outputStarted = false;
                        deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
                    }
                    waitForSignal(50L);
                    continue;
                }

                if (output == null && !engine.currentState().playing()) {
                    engine.close();
                    continue;
                }

                if (output == null) {
                    try {
                        output = outputFactory.open();
                        liveOutput = output;
                    } catch (Exception exception) {
                        report(AudioFailure.Kind.DEVICE, "", exception);
                        waitForSignal(deviceRetryMs);
                        deviceRetryMs = nextRetryDelay(deviceRetryMs);
                        continue;
                    }
                }

                if (!outputStarted) {
                    try {
                        output.start();
                        outputStarted = true;
                    } catch (Exception exception) {
                        report(AudioFailure.Kind.DEVICE, "", exception);
                        closeOutput(output);
                        output = null;
                        waitForSignal(deviceRetryMs);
                        deviceRetryMs = nextRetryDelay(deviceRetryMs);
                        continue;
                    }
                }

                byte[] rendered;
                try {
                    rendered = engine.renderFrames(BLOCK_FRAMES, masterGain);
                } catch (PcmMixerEngine.AudioPlaybackException exception) {
                    errorListener.onError(exception.failure());
                    continue;
                } catch (Exception exception) {
                    report(AudioFailure.Kind.DECODE, "", exception);
                    continue;
                }

                try {
                    output.write(rendered);
                    deviceRetryMs = INITIAL_DEVICE_RETRY_MS;
                } catch (InterruptedException exception) {
                    if (running) {
                        report(AudioFailure.Kind.THREAD, "", exception);
                    }
                    Thread.currentThread().interrupt();
                } catch (Exception exception) {
                    report(AudioFailure.Kind.DEVICE, "", exception);
                    closeOutput(output);
                    output = null;
                    outputStarted = false;
                    waitForSignal(deviceRetryMs);
                    deviceRetryMs = nextRetryDelay(deviceRetryMs);
                }
            }
        } catch (LinkageError | RuntimeException error) {
            report(AudioFailure.Kind.THREAD, "", error);
        } finally {
            if (output != null) {
                closeOutput(output);
            }
            liveOutput = null;
            running = false;
        }
    }

    private void closeOutput(AudioOutput output) {
        try {
            output.close();
        } catch (Exception exception) {
            report(AudioFailure.Kind.DEVICE, "", exception);
        } finally {
            if (liveOutput == output) {
                liveOutput = null;
            }
        }
    }

    private void drainAndCloseOutput(AudioOutput output) {
        try {
            output.drain();
        } catch (Exception exception) {
            report(AudioFailure.Kind.DEVICE, "", exception);
        } finally {
            closeOutput(output);
        }
    }

    private static long nextRetryDelay(long currentDelayMs) {
        return Math.min(MAX_DEVICE_RETRY_MS, currentDelayMs * 2L);
    }

    private void report(AudioFailure.Kind kind, String musicId, Throwable error) {
        errorListener.onError(new AudioFailure(kind, musicId, error));
    }

    private PendingUpdate drainPendingUpdate() {
        synchronized (signal) {
            MusicLibrary library = libraryPending ? pendingLibrary : null;
            PlaybackState state = statePending ? pendingState : null;
            pendingLibrary = null;
            pendingState = null;
            libraryPending = false;
            statePending = false;
            return new PendingUpdate(library, state);
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

    interface AudioOutput extends AutoCloseable {
        void start();

        void stop();

        void write(byte[] pcm) throws InterruptedException;

        void drain();

        @Override
        void close();
    }

    private record PendingUpdate(MusicLibrary musicLibrary, PlaybackState playbackState) {
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
        public void write(byte[] pcm) {
            int offset = 0;
            while (offset < pcm.length && line.isOpen()) {
                int written = line.write(pcm, offset, pcm.length - offset);
                if (written <= 0) {
                    break;
                }
                offset += written;
            }
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
