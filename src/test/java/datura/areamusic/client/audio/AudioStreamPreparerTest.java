package datura.areamusic.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioStreamPreparerTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesConstructorAndPrepareArgumentsBeforeSchedulingWork() {
        assertThrows(NullPointerException.class, () -> new AudioStreamPreparer(null, 1));
        assertThrows(IllegalArgumentException.class,
                () -> new AudioStreamPreparer(new AudioStreamFactory(), 0));

        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                openCount.incrementAndGet();
                return stream(pcmFrames((short) 100), new AtomicInteger());
            }
        };
        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1)) {
            assertThrows(NullPointerException.class, () -> preparer.prepare(null, 0));
            assertThrows(IllegalArgumentException.class,
                    () -> preparer.prepare(Path.of("track.wav"), -1));
            assertThrows(ArithmeticException.class, () -> preparer.prepare(
                    Path.of("track.wav"), Long.MAX_VALUE
            ));
            assertEquals(0, openCount.get());
        }
    }

    @Test
    void prepareReturnsImmediatelyWhileTheFactoryIsBlocked() throws Exception {
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) throws IOException {
                openEntered.countDown();
                awaitOrThrow(releaseOpen);
                return stream(pcmFrames((short) 100), closeCount);
            }
        };

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1)) {
            CompletableFuture<AudioInputStream> prepared = assertTimeoutPreemptively(
                    Duration.ofMillis(250),
                    () -> preparer.prepare(Path.of("blocked.wav"), 0)
            );
            assertTrue(openEntered.await(1, TimeUnit.SECONDS));
            assertFalse(prepared.isDone());

            releaseOpen.countDown();
            AudioInputStream result = prepared.get(1, TimeUnit.SECONDS);
            assertEquals(0, closeCount.get());
            result.close();
            assertEquals(1, closeCount.get());
        } finally {
            releaseOpen.countDown();
        }
    }

    @Test
    void fixedDaemonPoolNeverExceedsItsWorkerCount() throws Exception {
        int workerCount = 2;
        CountDownLatch workersEntered = new CountDownLatch(workerCount);
        CountDownLatch releaseWorkers = new CountDownLatch(1);
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        AtomicBoolean allDaemon = new AtomicBoolean(true);
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) throws IOException {
                int current = active.incrementAndGet();
                maximumActive.accumulateAndGet(current, Math::max);
                allDaemon.compareAndSet(true, Thread.currentThread().isDaemon());
                workersEntered.countDown();
                try {
                    awaitOrThrow(releaseWorkers);
                    return stream(pcmFrames((short) 100), new AtomicInteger());
                } finally {
                    active.decrementAndGet();
                }
            }
        };

        List<CompletableFuture<AudioInputStream>> futures = new ArrayList<>();
        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, workerCount)) {
            for (int index = 0; index < 5; index++) {
                futures.add(preparer.prepare(Path.of("track-" + index + ".wav"), 0));
            }

            assertTrue(workersEntered.await(1, TimeUnit.SECONDS));
            assertEquals(workerCount, maximumActive.get());
            assertTrue(allDaemon.get());
            assertTrue(futures.stream().noneMatch(CompletableFuture::isDone));

            releaseWorkers.countDown();
            for (CompletableFuture<AudioInputStream> future : futures) {
                future.get(1, TimeUnit.SECONDS).close();
            }
            assertTrue(maximumActive.get() <= workerCount);
        } finally {
            releaseWorkers.countDown();
        }
    }

    @Test
    void preparationUsesChunkedReadDiscardWithoutCallingDecoderSkip() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger skipCalls = new AtomicInteger();
        AudioInputStream source = new RejectingSkipAudioInputStream(
                pcmFrames((short) 100, (short) 200, (short) 300),
                closeCount,
                skipCalls
        );
        AudioStreamFactory factory = fixedFactory(source);

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             AudioInputStream prepared = preparer.prepare(
                     Path.of("fallback.wav"), 2
             ).get(1, TimeUnit.SECONDS)) {
            byte[] frame = prepared.readNBytes(AudioStreamFactory.MIX_FORMAT.getFrameSize());
            assertEquals(300, PcmMath.readLittleEndian(frame, 0));
            assertEquals(AudioStreamFactory.MIX_FORMAT.getFrameSize(), frame.length);
            assertEquals(0, skipCalls.get());
        }
        assertEquals(1, closeCount.get());
    }

    @Test
    void endOfStreamBeforeOffsetFailsAndClosesTheOpenedStream() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new CountingAudioInputStream(
                pcmFrames((short) 100), closeCount
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1)) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> preparer
                    .prepare(Path.of("short.wav"), 2)
                    .get(1, TimeUnit.SECONDS));

            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("offset"));
            assertClosedExactlyOnce(closeCount);
        }
    }

    @Test
    void persistentZeroReadsFailWithinABoundedNumberOfAttemptsAndCloseTheStream()
            throws Exception {
        AtomicInteger readCalls = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new PersistentZeroAudioInputStream(readCalls, closeCount);

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1)) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> preparer
                    .prepare(Path.of("stalled.wav"), 1)
                    .get(2, TimeUnit.SECONDS));

            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("zero bytes"));
            assertTrue(readCalls.get() <= 65, "persistent zero reads must be bounded");
            assertClosedExactlyOnce(closeCount);
        }
    }

    @Test
    void readFailureCompletesExceptionallyAndClosesTheOpenedStream() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new FailingReadAudioInputStream(closeCount);

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1)) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> preparer
                    .prepare(Path.of("broken.wav"), 1)
                    .get(1, TimeUnit.SECONDS));

            assertInstanceOf(IOException.class, failure.getCause());
            assertClosedExactlyOnce(closeCount);
        }
    }

    @Test
    void partialPcmFrameDuringDiscardFailsAndClosesTheOpenedStream() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new PartialFrameReadAudioInputStream(closeCount);

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1)) {
            ExecutionException failure = assertThrows(ExecutionException.class, () -> preparer
                    .prepare(Path.of("partial.wav"), 1)
                    .get(1, TimeUnit.SECONDS));

            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("frame size"));
            assertClosedExactlyOnce(closeCount);
        }
    }

    @Test
    void cancellationBeforeAQueuedTaskStartsNeverOpensItsStream() throws Exception {
        CountDownLatch firstOpenEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOpen = new CountDownLatch(1);
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) throws IOException {
                int opened = openCount.incrementAndGet();
                if (opened == 1) {
                    firstOpenEntered.countDown();
                    awaitOrThrow(releaseFirstOpen);
                }
                return stream(pcmFrames((short) opened), new AtomicInteger());
            }
        };

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1)) {
            CompletableFuture<AudioInputStream> first = preparer.prepare(Path.of("first.wav"), 0);
            assertTrue(firstOpenEntered.await(1, TimeUnit.SECONDS));
            CompletableFuture<AudioInputStream> queued = preparer.prepare(Path.of("queued.wav"), 0);

            assertTrue(queued.cancel(true));
            releaseFirstOpen.countDown();
            first.get(1, TimeUnit.SECONDS).close();

            assertTrue(queued.isCancelled());
            assertEquals(1, openCount.get());
        } finally {
            releaseFirstOpen.countDown();
        }
    }

    @Test
    void cancellationWhileFactoryOpenReturnsClosesTheLateStream() throws Exception {
        CountDownLatch openEntered = new CountDownLatch(1);
        CountDownLatch releaseOpen = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new CloseSignallingAudioInputStream(closeCount, streamClosed);
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                openEntered.countDown();
                awaitIgnoringInterrupt(releaseOpen);
                return source;
            }
        };

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1)) {
            CompletableFuture<AudioInputStream> prepared = preparer.prepare(
                    Path.of("cancel-open.wav"), 0
            );
            assertTrue(openEntered.await(1, TimeUnit.SECONDS));

            assertTrue(prepared.cancel(true));
            releaseOpen.countDown();

            assertTrue(streamClosed.await(1, TimeUnit.SECONDS));
            assertTrue(prepared.isCancelled());
            assertEquals(1, closeCount.get());
        } finally {
            releaseOpen.countDown();
        }
    }

    @Test
    void cancellationDuringReadInterruptsAndClosesTheOpenedStream() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new BlockingReadAudioInputStream(
                readEntered, releaseRead, closeCount, streamClosed
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1)) {
            CompletableFuture<AudioInputStream> prepared = preparer.prepare(
                    Path.of("cancel-read.wav"), 1
            );
            assertTrue(readEntered.await(1, TimeUnit.SECONDS));

            assertTrue(prepared.cancel(true));

            assertTrue(streamClosed.await(1, TimeUnit.SECONDS));
            assertTrue(prepared.isCancelled());
            assertEquals(1, closeCount.get());
        } finally {
            releaseRead.countDown();
        }
    }

    @Test
    void cancellationWithoutInterruptStillClosesAStreamBlockedInRead() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = new BlockingReadAudioInputStream(
                readEntered, releaseRead, closeCount, streamClosed
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1)) {
            CompletableFuture<AudioInputStream> prepared = preparer.prepare(
                    Path.of("cancel-without-interrupt.wav"), 1
            );
            assertTrue(readEntered.await(1, TimeUnit.SECONDS));

            assertTrue(prepared.cancel(false));

            assertTrue(streamClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1, closeCount.get());
            releaseRead.countDown();
        } finally {
            releaseRead.countDown();
        }
    }

    @Test
    void successfulCompletionTransfersOwnershipEvenIfCancelAndCloseFollow() throws Exception {
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream source = stream(pcmFrames((short) 100), closeCount);
        AudioStreamPreparer preparer = new AudioStreamPreparer(fixedFactory(source), 1);

        CompletableFuture<AudioInputStream> prepared = preparer.prepare(
                Path.of("owned.wav"), 0
        );
        AudioInputStream result = prepared.get(1, TimeUnit.SECONDS);

        assertFalse(prepared.cancel(true));
        preparer.close();
        preparer.close();
        assertEquals(0, closeCount.get());

        result.close();
        assertEquals(1, closeCount.get());
    }

    @Test
    void closeCancelsPendingWorkStopsDaemonWorkerAndRejectsNewPrepare() throws Exception {
        CountDownLatch readEntered = new CountDownLatch(1);
        CountDownLatch releaseRead = new CountDownLatch(1);
        CountDownLatch streamClosed = new CountDownLatch(1);
        AtomicInteger closeCount = new AtomicInteger();
        AtomicInteger openCount = new AtomicInteger();
        AtomicReference<Thread> worker = new AtomicReference<>();
        AudioInputStream source = new BlockingReadAudioInputStream(
                readEntered, releaseRead, closeCount, streamClosed
        );
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                worker.compareAndSet(null, Thread.currentThread());
                openCount.incrementAndGet();
                return source;
            }
        };
        AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
        CompletableFuture<AudioInputStream> active = preparer.prepare(
                Path.of("active.wav"), 1
        );
        assertTrue(readEntered.await(1, TimeUnit.SECONDS));
        CompletableFuture<AudioInputStream> queued = preparer.prepare(
                Path.of("queued.wav"), 0
        );

        preparer.close();
        preparer.close();

        assertTrue(active.isCancelled());
        assertTrue(queued.isCancelled());
        assertTrue(streamClosed.await(1, TimeUnit.SECONDS));
        assertEquals(1, closeCount.get());
        assertEquals(1, openCount.get());
        assertThrows(RejectedExecutionException.class,
                () -> preparer.prepare(Path.of("late.wav"), 0));
        assertTrue(awaitCondition(() -> !worker.get().isAlive(), Duration.ofSeconds(2)));
        releaseRead.countDown();
    }

    @Test
    void realWavOffsetTwoStartsAtTheThirdFrame() throws Exception {
        Path wav = tempDir.resolve("offset.wav");
        writeWav(wav, new short[]{100, 200, 300});

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(
                new AudioStreamFactory(), 1
        ); AudioInputStream prepared = preparer.prepare(wav, 2).get(1, TimeUnit.SECONDS)) {
            byte[] frame = prepared.readNBytes(AudioStreamFactory.MIX_FORMAT.getFrameSize());
            assertEquals(AudioStreamFactory.MIX_FORMAT.getFrameSize(), frame.length);
            assertEquals(300, PcmMath.readLittleEndian(frame, 0));
            assertEquals(300, PcmMath.readLittleEndian(frame, 2));
        }
    }

    @Test
    void realMp3OffsetCompletesWithinBoundAndMatchesBulkRead() throws Exception {
        assertRealCodecOffset("test.mp3", 11_025L);
    }

    @Test
    void realOggOffsetCompletesWithinBoundAndMatchesBulkRead() throws Exception {
        assertRealCodecOffset("test.ogg", 11_025L);
    }

    private void assertRealCodecOffset(String fixtureName, long frameOffset) throws Exception {
        Path source = tempDir.resolve(fixtureName);
        try (InputStream fixture = getClass().getResourceAsStream(
                "/datura/areamusic/audio/" + fixtureName
        )) {
            assertNotNull(fixture);
            Files.copy(fixture, source);
        }
        byte[] expected = frameAtOffsetByBulkRead(source, frameOffset);

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(
                new AudioStreamFactory(), 1
        ); AudioInputStream prepared = preparer.prepare(
                source, frameOffset
        ).get(3, TimeUnit.SECONDS)) {
            assertArrayEquals(expected, readExactFrame(prepared));
        }
    }

    private static byte[] frameAtOffsetByBulkRead(Path path, long frameOffset) throws Exception {
        try (AudioInputStream stream = new AudioStreamFactory().open(path)) {
            long remaining = Math.multiplyExact(
                    frameOffset,
                    (long) AudioStreamFactory.MIX_FORMAT.getFrameSize()
            );
            byte[] discard = new byte[8192];
            int zeroReads = 0;
            while (remaining > 0L) {
                int requested = (int) Math.min(remaining, (long) discard.length);
                int read = stream.read(discard, 0, requested);
                if (read < 0) {
                    throw new IOException("Fixture ended before requested frame offset");
                }
                if (read == 0) {
                    if (++zeroReads > 64) {
                        throw new IOException("Fixture decoder stalled during bulk read");
                    }
                    continue;
                }
                assertEquals(0, read % AudioStreamFactory.MIX_FORMAT.getFrameSize());
                remaining -= read;
                zeroReads = 0;
            }
            return readExactFrame(stream);
        }
    }

    private static byte[] readExactFrame(AudioInputStream stream) throws IOException {
        int frameSize = AudioStreamFactory.MIX_FORMAT.getFrameSize();
        byte[] frame = new byte[frameSize];
        int total = 0;
        int zeroReads = 0;
        while (total < frameSize) {
            int read = stream.read(frame, total, frameSize - total);
            if (read < 0) {
                throw new IOException("Fixture ended before comparison frame");
            }
            if (read == 0) {
                if (++zeroReads > 64) {
                    throw new IOException("Fixture decoder stalled before comparison frame");
                }
                continue;
            }
            total += read;
            zeroReads = 0;
        }
        return frame;
    }

    private static AudioStreamFactory fixedFactory(AudioInputStream source) {
        return new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                return source;
            }
        };
    }

    private static AudioInputStream stream(byte[] pcm, AtomicInteger closeCount) {
        return new CountingAudioInputStream(pcm, closeCount);
    }

    private static byte[] pcmFrames(short... samples) {
        int frameSize = AudioStreamFactory.MIX_FORMAT.getFrameSize();
        byte[] pcm = new byte[samples.length * frameSize];
        for (int frame = 0; frame < samples.length; frame++) {
            int offset = frame * frameSize;
            PcmMath.writeLittleEndian(pcm, offset, samples[frame]);
            PcmMath.writeLittleEndian(pcm, offset + 2, samples[frame]);
        }
        return pcm;
    }

    private static void writeWav(Path path, short[] samples) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] pcm = pcmFrames(samples);
        try (AudioInputStream source = new AudioInputStream(
                new ByteArrayInputStream(pcm),
                AudioStreamFactory.MIX_FORMAT,
                samples.length
        )) {
            AudioSystem.write(source, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static void awaitOrThrow(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", exception);
        }
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static boolean awaitCondition(Check condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.value()) {
                return true;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        return condition.value();
    }

    private static void assertClosedExactlyOnce(AtomicInteger closeCount) {
        assertTrue(awaitCondition(
                () -> closeCount.get() == 1, Duration.ofSeconds(2)
        ));
        assertEquals(1, closeCount.get());
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }

    private static class CountingAudioInputStream extends AudioInputStream {
        private final AtomicInteger closeCount;

        private CountingAudioInputStream(byte[] pcm, AtomicInteger closeCount) {
            super(
                    new ByteArrayInputStream(pcm),
                    AudioStreamFactory.MIX_FORMAT,
                    pcm.length / AudioStreamFactory.MIX_FORMAT.getFrameSize()
            );
            this.closeCount = closeCount;
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }
    }

    private static final class CloseSignallingAudioInputStream extends CountingAudioInputStream {
        private final CountDownLatch closed;

        private CloseSignallingAudioInputStream(
                AtomicInteger closeCount,
                CountDownLatch closed
        ) {
            super(pcmFrames((short) 100), closeCount);
            this.closed = closed;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                closed.countDown();
            }
        }
    }

    private static final class RejectingSkipAudioInputStream extends CountingAudioInputStream {
        private final AtomicInteger skipCalls;

        private RejectingSkipAudioInputStream(
                byte[] pcm,
                AtomicInteger closeCount,
                AtomicInteger skipCalls
        ) {
            super(pcm, closeCount);
            this.skipCalls = skipCalls;
        }

        @Override
        public long skip(long bytes) throws IOException {
            skipCalls.incrementAndGet();
            throw new IOException("decoder skip must not be used");
        }
    }

    private static final class PersistentZeroAudioInputStream extends CountingAudioInputStream {
        private final AtomicInteger readCalls;

        private PersistentZeroAudioInputStream(
                AtomicInteger readCalls,
                AtomicInteger closeCount
        ) {
            super(pcmFrames((short) 100), closeCount);
            this.readCalls = readCalls;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            readCalls.incrementAndGet();
            return 0;
        }
    }

    private static final class FailingReadAudioInputStream extends CountingAudioInputStream {
        private FailingReadAudioInputStream(AtomicInteger closeCount) {
            super(pcmFrames((short) 100), closeCount);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw new IOException("read failed");
        }
    }

    private static final class PartialFrameReadAudioInputStream extends CountingAudioInputStream {
        private PartialFrameReadAudioInputStream(AtomicInteger closeCount) {
            super(pcmFrames((short) 100), closeCount);
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            buffer[offset] = 0x12;
            buffer[offset + 1] = 0x34;
            return 2;
        }
    }

    private static final class BlockingReadAudioInputStream extends CountingAudioInputStream {
        private final CountDownLatch entered;
        private final CountDownLatch release;
        private final CountDownLatch closed;

        private BlockingReadAudioInputStream(
                CountDownLatch entered,
                CountDownLatch release,
                AtomicInteger closeCount,
                CountDownLatch closed
        ) {
            super(pcmFrames((short) 100, (short) 200), closeCount);
            this.entered = entered;
            this.release = release;
            this.closed = closed;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            entered.countDown();
            awaitOrThrow(release);
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                closed.countDown();
            }
        }
    }
}
