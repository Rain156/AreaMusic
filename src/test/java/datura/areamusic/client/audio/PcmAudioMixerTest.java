package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioMixerTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersSubmittedPlaybackStateOnTheOwnedAudioThread() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("thread.wav"), (short) 1200, 4096);
        FakeOutput output = new FakeOutput();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> output,
                failure -> errors.add(failure.cause())
        );

        mixer.start();
        mixer.apply(playing("area", "thread.wav", 1.0f, true, 0, 0));

        assertTrue(output.firstWrite.await(2, TimeUnit.SECONDS));
        assertEquals(1200, PcmMath.readLittleEndian(output.firstBlock.get(), 0));
        assertTrue(errors.isEmpty());
        mixer.close();
    }

    @Test
    void startFailureClosesBadOutputAndRetriesWithANewDevice() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("thread.wav"), (short) 1200, 4096);
        FakeOutput workingOutput = new FakeOutput();
        AtomicBoolean failedOutputClosed = new AtomicBoolean();
        AtomicInteger openAttempts = new AtomicInteger();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0
                        ? new StartFailingOutput(failedOutputClosed)
                        : workingOutput,
                failure -> errors.add(failure.cause())
        );

        mixer.start();
        mixer.apply(playing("area", "thread.wav", 1.0f, true, 0, 0));

        assertTrue(workingOutput.firstWrite.await(3, TimeUnit.SECONDS));
        assertTrue(failedOutputClosed.get());
        assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("start failed")));
        mixer.close();
    }

    @Test
    void startFailureDoesNotConsumeShortNonLoopTrackBeforeRetry() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("short.wav"), (short) 2345, 1);
        FakeOutput workingOutput = new FakeOutput();
        AtomicBoolean failedOutputClosed = new AtomicBoolean();
        AtomicInteger openAttempts = new AtomicInteger();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0
                        ? new StartFailingOutput(failedOutputClosed)
                        : workingOutput,
                failure -> {
                }
        );

        mixer.start();
        mixer.apply(playing("area", "short.wav", 1.0f, false, 0, 0));

        assertTrue(workingOutput.firstWrite.await(2, TimeUnit.SECONDS));
        assertEquals(2345, PcmMath.readLittleEndian(workingOutput.firstBlock.get(), 0));
        assertTrue(failedOutputClosed.get());
        mixer.close();
    }

    @Test
    void writeFailureClosesBadOutputAndRetriesWithANewDevice() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("thread.wav"), (short) 1200, 4096);
        FakeOutput workingOutput = new FakeOutput();
        AtomicBoolean failedOutputClosed = new AtomicBoolean();
        AtomicInteger openAttempts = new AtomicInteger();
        AtomicReference<Throwable> error = new AtomicReference<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0
                        ? new WriteFailingOutput(failedOutputClosed)
                        : workingOutput,
                failure -> error.compareAndSet(null, failure.cause())
        );

        mixer.start();
        mixer.apply(playing("area", "thread.wav", 1.0f, true, 0, 0));

        boolean retried = workingOutput.firstWrite.await(1, TimeUnit.SECONDS);
        mixer.close();
        assertTrue(retried);
        assertTrue(failedOutputClosed.get());
        assertTrue(error.get().getMessage().contains("write failed"));
    }

    @Test
    void closesOutputWhenPlaybackBecomesIdle() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("once.wav"), (short) 1200, 1);
        NonBlockingOutput output = new NonBlockingOutput();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> output,
                failure -> {
                }
        );

        mixer.start();
        mixer.apply(playing("area", "once.wav", 1.0f, false, 0, 0));

        assertTrue(output.firstWrite.await(1, TimeUnit.SECONDS));
        boolean closedWhileIdle = output.closed.await(1, TimeUnit.SECONDS);
        mixer.close();
        assertTrue(closedWhileIdle);
        assertTrue(output.closedAfterDrain.get());
    }

    @Test
    void resumeRestartsStoppedOutputBeforeDrainingACompletedTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("once.wav"), (short) 1200, 1);
        PauseAtEndOutput output = new PauseAtEndOutput();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> output,
                failure -> {
                }
        );

        mixer.start();
        mixer.apply(playing("area", "once.wav", 1.0f, false, 0, 0));
        assertTrue(output.firstWrite.await(1, TimeUnit.SECONDS));

        mixer.setPaused(true);
        output.releaseWrite.countDown();
        assertTrue(output.stopped.await(1, TimeUnit.SECONDS));

        mixer.setPaused(false);
        boolean closedAfterResume = output.closed.await(1, TimeUnit.SECONDS);
        mixer.close();

        assertTrue(closedAfterResume);
        assertEquals(2, output.startCount.get());
    }

    @Test
    void missingMusicReportsAStructuredFailure() throws Exception {
        MusicLibrary library = MusicLibrary.empty(tempDir.resolve("music"));
        AtomicReference<AudioFailure> failure = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        PcmAudioMixer mixer = new PcmAudioMixer(
                library,
                NonBlockingOutput::new,
                error -> {
                    failure.set(error);
                    reported.countDown();
                }
        );

        mixer.start();
        mixer.apply(playing("area", "missing.mp3", 1.0f, true, 0, 0));

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(AudioFailure.Kind.MISSING_FILE, failure.get().kind());
        assertEquals("missing.mp3", failure.get().musicId());
        mixer.close();
    }

    @Test
    void stoppedStateReleasesTracksWithoutRetryingAnUnavailableDevice() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("loop.wav"), (short) 1200, 4096);
        CountDownLatch firstOpenEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOpen = new CountDownLatch(1);
        CountDownLatch secondOpenEntered = new CountDownLatch(1);
        AtomicInteger openAttempts = new AtomicInteger();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> {
                    int attempt = openAttempts.incrementAndGet();
                    if (attempt == 1) {
                        firstOpenEntered.countDown();
                        releaseFirstOpen.await(1, TimeUnit.SECONDS);
                    } else {
                        secondOpenEntered.countDown();
                    }
                    throw new IllegalStateException("device unavailable");
                },
                failure -> {
                }
        );

        mixer.start();
        mixer.apply(playing("area", "loop.wav", 1.0f, true, 0, 60_000));
        assertTrue(firstOpenEntered.await(1, TimeUnit.SECONDS));

        mixer.apply(PlaybackState.stopped());
        releaseFirstOpen.countDown();

        assertFalse(secondOpenEntered.await(750, TimeUnit.MILLISECONDS));
        assertEquals(1, openAttempts.get());
        mixer.close();
    }

    @Test
    void unrecoverableMixerErrorIsReportedBeforeThreadExit() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("thread.wav"), (short) 1200, 4096);
        AtomicReference<AudioFailure> failure = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> {
                    throw new NoClassDefFoundError("javazoom/spi/mpeg/sampled/convert/MpegFormatConversionProvider");
                },
                error -> {
                    failure.set(error);
                    reported.countDown();
                }
        );

        mixer.start();
        mixer.apply(playing("area", "thread.wav", 1.0f, true, 0, 0));

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(AudioFailure.Kind.THREAD, failure.get().kind());
        assertTrue(failure.get().cause() instanceof NoClassDefFoundError);
        mixer.close();
    }

    private static PlaybackState playing(
            String areaId,
            String musicId,
            float volume,
            boolean loop,
            int fadeInMs,
            int fadeOutMs
    ) {
        return PlaybackState.playing(
                areaId,
                List.of(new AreaTrackDefinition(musicId, 0, volume, loop, fadeInMs, fadeOutMs)),
                false
        );
    }

    private static void writeConstantWav(Path path, short sample, int frames) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] pcm = new byte[frames * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, sample);
            PcmMath.writeLittleEndian(pcm, offset + 2, sample);
        }
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), AudioStreamFactory.MIX_FORMAT, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static final class FakeOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<byte[]> firstBlock = new AtomicReference<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void write(byte[] pcm) throws InterruptedException {
            if (firstBlock.compareAndSet(null, pcm.clone())) {
                firstWrite.countDown();
            }
            closed.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class StartFailingOutput implements PcmAudioMixer.AudioOutput {
        private final AtomicBoolean closed;

        private StartFailingOutput(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public void start() {
            throw new IllegalStateException("start failed");
        }

        @Override
        public void stop() {
        }

        @Override
        public void write(byte[] pcm) {
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class WriteFailingOutput implements PcmAudioMixer.AudioOutput {
        private final AtomicBoolean closed;

        private WriteFailingOutput(AtomicBoolean closed) {
            this.closed = closed;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void write(byte[] pcm) {
            throw new IllegalStateException("write failed");
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }

    private static final class NonBlockingOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean drained = new AtomicBoolean();
        private final AtomicBoolean closedAfterDrain = new AtomicBoolean();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void write(byte[] pcm) {
            firstWrite.countDown();
        }

        @Override
        public void drain() {
            drained.set(true);
        }

        @Override
        public void close() {
            closedAfterDrain.set(drained.get());
            closed.countDown();
        }
    }

    private static final class PauseAtEndOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final CountDownLatch resumed = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger startCount = new AtomicInteger();

        @Override
        public void start() {
            if (startCount.incrementAndGet() > 1) {
                resumed.countDown();
            }
        }

        @Override
        public void stop() {
            stopped.countDown();
        }

        @Override
        public void write(byte[] pcm) throws InterruptedException {
            firstWrite.countDown();
            releaseWrite.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void drain() {
            try {
                resumed.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public void close() {
            releaseWrite.countDown();
            resumed.countDown();
            closed.countDown();
        }
    }
}
