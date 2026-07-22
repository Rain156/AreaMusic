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
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioMixerTest {
    private static final int BLOCK_FRAMES = 1024;
    private static final int BLOCK_BYTES =
            BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize();

    @TempDir
    Path tempDir;

    @Test
    void writesSilentBlocksUntilADelayedTrackBecomesDue() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("delayed.wav"), (short) 3456, 1);
        DelayCapturingOutput output = new DelayCapturingOutput();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root), () -> output, failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.playing(
                    "area",
                    List.of(new AreaTrackDefinition("delayed.wav", 1, 1.0f, false, 0, 0)),
                    false
            ));

            assertTrue(output.firstWrite.await(1, TimeUnit.SECONDS));
            assertTrue(output.firstNonSilent.await(2, TimeUnit.SECONDS));
            assertEquals(0, PcmMath.readLittleEndian(output.firstBlock.get(), 0));
            assertEquals(44_100, output.firstNonSilentFrame.get());
            assertTrue(failures.isEmpty());
        } finally {
            mixer.close();
        }
    }

    @Test
    void reportsOneTrackFailureWhileAnotherTrackKeepsWriting() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("good.wav"), (short) 2345, 4096);
        FakeOutput output = new FakeOutput();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> output,
                failure -> {
                    failures.add(failure);
                    reported.countDown();
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.playing(
                    "area",
                    List.of(
                            new AreaTrackDefinition("missing.wav", 0, 1.0f, false, 0, 0),
                            new AreaTrackDefinition("good.wav", 0, 1.0f, true, 0, 0)
                    ),
                    false
            ));

            assertTrue(output.firstWrite.await(1, TimeUnit.SECONDS));
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertEquals(2345, PcmMath.readLittleEndian(output.firstBlock.get(), 0));
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.MISSING_FILE, failures.get(0).kind());
            assertEquals("missing.wav", failures.get(0).musicId());
        } finally {
            mixer.close();
        }
    }

    @Test
    void pauseFreezesDelayedPlaybackUntilTheWorkerResumes() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("delayed.wav"), (short) 3456, 1);
        PausableDelayOutput output = new PausableDelayOutput();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root), () -> output, failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.playing(
                    "area",
                    List.of(new AreaTrackDefinition("delayed.wav", 1, 1.0f, false, 0, 0)),
                    false
            ));
            assertTrue(output.firstWriteEntered.await(1, TimeUnit.SECONDS));

            mixer.setPaused(true);
            output.releaseFirstWrite.countDown();

            assertTrue(output.stopped.await(1, TimeUnit.SECONDS));
            assertFalse(output.secondWrite.await(250, TimeUnit.MILLISECONDS));

            mixer.setPaused(false);

            assertTrue(output.firstNonSilent.await(2, TimeUnit.SECONDS));
            assertEquals(44_100, output.firstNonSilentFrame.get());
        } finally {
            output.releaseFirstWrite.countDown();
            mixer.close();
        }
    }

    @Test
    void deviceOpenBackoffDoesNotAdvanceDelayedPlayback() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("delayed.wav"), (short) 3456, 1);
        DelayCapturingOutput workingOutput = new DelayCapturingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> {
                    if (openAttempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("open failed");
                    }
                    return workingOutput;
                },
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.playing(
                    "area",
                    List.of(new AreaTrackDefinition("delayed.wav", 1, 1.0f, false, 0, 0)),
                    false
            ));

            assertTrue(workingOutput.firstNonSilent.await(3, TimeUnit.SECONDS));
            assertEquals(44_100, workingOutput.firstNonSilentFrame.get());
            assertEquals(2, openAttempts.get());
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            mixer.close();
        }
    }

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
        mixer.apply(1L, playing("area", "thread.wav", 1.0f, true, 0, 0));

        assertTrue(output.firstWrite.await(2, TimeUnit.SECONDS));
        assertEquals(1200, PcmMath.readLittleEndian(output.firstBlock.get(), 0));
        assertTrue(errors.isEmpty());
        mixer.close();
    }

    @Test
    void forwardsTheRevisionWithTheLatestPendingPlaybackState() throws Exception {
        RevisionRecordingEngine engine = new RevisionRecordingEngine();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                NonBlockingOutput::new,
                library -> engine,
                failure -> {
                }
        );
        PlaybackState older = playing("older", "older.wav", 1.0f, true, 0, 0);
        PlaybackState latest = playing("latest", "latest.wav", 1.0f, true, 0, 0);

        mixer.apply(4L, older);
        mixer.apply(5L, latest);
        mixer.start();

        assertTrue(engine.applied.await(1, TimeUnit.SECONDS));
        assertEquals(5L, engine.revision.get());
        assertEquals(latest, engine.state.get());
        mixer.close();
    }

    @Test
    void rejectedNullStateDoesNotCorruptThePendingRevisionStatePair() throws Exception {
        RevisionRecordingEngine engine = new RevisionRecordingEngine();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                NonBlockingOutput::new,
                library -> engine,
                failure -> {
                }
        );
        PlaybackState valid = playing("valid", "valid.wav", 1.0f, true, 0, 0);

        mixer.apply(5L, valid);
        assertThrows(NullPointerException.class, () -> mixer.apply(6L, null));
        mixer.start();

        assertTrue(engine.applied.await(1, TimeUnit.SECONDS));
        assertEquals(5L, engine.revision.get());
        assertEquals(valid, engine.state.get());
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
        mixer.apply(1L, playing("area", "thread.wav", 1.0f, true, 0, 0));

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
        mixer.apply(1L, playing("area", "short.wav", 1.0f, false, 0, 0));

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
        mixer.apply(1L, playing("area", "thread.wav", 1.0f, true, 0, 0));

        boolean retried = workingOutput.firstWrite.await(1, TimeUnit.SECONDS);
        mixer.close();
        assertTrue(retried);
        assertTrue(failedOutputClosed.get());
        assertTrue(error.get().getMessage().contains("write failed"));
    }

    @Test
    void writeFailureRetriesTheSameRenderedBlockWithoutSkippingContent() throws Exception {
        Path root = tempDir.resolve("music");
        writeTwoBlockWav(root.resolve("sequence.wav"), (short) 1200, (short) 2400);
        FakeOutput workingOutput = new FakeOutput();
        AtomicBoolean failedOutputClosed = new AtomicBoolean();
        AtomicReference<byte[]> failedBlock = new AtomicReference<>();
        AtomicInteger openAttempts = new AtomicInteger();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0
                        ? new WriteFailingOutput(failedOutputClosed, failedBlock)
                        : workingOutput,
                failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "sequence.wav", 1.0f, false, 0, 0));

            assertTrue(workingOutput.firstWrite.await(2, TimeUnit.SECONDS));
            assertNotNull(failedBlock.get());
            assertArrayEquals(failedBlock.get(), workingOutput.firstBlock.get());
            assertEquals(1200, PcmMath.readLittleEndian(workingOutput.firstBlock.get(), 0));
            assertTrue(failedOutputClosed.get());
        } finally {
            mixer.close();
        }
    }

    @Test
    void combinesMultiplePartialWritesIntoExactlyOneRenderedBlock() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("partial.wav"), (short) 1200, BLOCK_FRAMES * 2);
        ChunkedOutput output = new ChunkedOutput(256, 1024, 4, 2048, 764);
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root), () -> output, failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "partial.wav", 1.0f, true, 0, 0));

            assertTrue(output.complete.await(2, TimeUnit.SECONDS));
            assertArrayEquals(constantPcmBlock((short) 1200), output.completedBlock.get());
            assertEquals(List.of(0, 256, 1280, 1284, 3332), output.offsets);
        } finally {
            mixer.close();
        }
    }

    @Test
    void zeroByteWriteRetriesTheSameBufferAndOffsetAfterBackoff() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("zero.wav"), (short) 1300, BLOCK_FRAMES * 2);
        ZeroThenCompleteOutput output = new ZeroThenCompleteOutput();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root), () -> output, failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "zero.wav", 1.0f, true, 0, 0));

            assertTrue(output.complete.await(2, TimeUnit.SECONDS));
            assertTrue(output.sameBuffer.get());
            assertEquals(0, output.secondOffset.get());
            assertEquals(AudioStreamFactory.MIX_FORMAT.getFrameSize() * BLOCK_FRAMES,
                    output.secondLength.get());
            assertTrue(
                    output.delayNanos.get() >= Duration.ofMillis(5).toNanos(),
                    "zero-byte writes must use a short retry wait"
            );
        } finally {
            mixer.close();
        }
    }

    @Test
    void writeExceptionAfterPartialProgressRetriesOnlyTheRemainingSuffix() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("suffix.wav"), (short) 1400, BLOCK_FRAMES * 2);
        PrefixThenFailingOutput failedOutput = new PrefixThenFailingOutput(776);
        SuffixCapturingOutput workingOutput = new SuffixCapturingOutput(776);
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0 ? failedOutput : workingOutput,
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "suffix.wav", 1.0f, true, 0, 0));

            assertTrue(workingOutput.complete.await(3, TimeUnit.SECONDS));
            byte[] combined = new byte[failedOutput.prefix.size() + workingOutput.suffix.size()];
            System.arraycopy(failedOutput.prefix.toByteArray(), 0, combined, 0,
                    failedOutput.prefix.size());
            System.arraycopy(workingOutput.suffix.toByteArray(), 0, combined,
                    failedOutput.prefix.size(), workingOutput.suffix.size());
            assertArrayEquals(constantPcmBlock((short) 1400), combined);
            assertEquals(776, failedOutput.failureOffset.get());
            assertEquals(776, workingOutput.firstOffset.get());
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            mixer.close();
        }
    }

    @Test
    void pauseAndPlayingCommandKeepThePendingBlockWithoutRerendering() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("old.wav"), (short) 1500, BLOCK_FRAMES * 2);
        writeConstantWav(root.resolve("new.wav"), (short) 2500, BLOCK_FRAMES * 2);
        ControlledZeroOutput output = new ControlledZeroOutput();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root), () -> output, failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("old", "old.wav", 1.0f, true, 0, 0));
            assertTrue(output.firstWriteEntered.await(1, TimeUnit.SECONDS));

            mixer.setPaused(true);
            mixer.apply(1L, playing("new", "new.wav", 1.0f, true, 0, 0));
            output.returnZero.countDown();

            assertTrue(output.stopped.await(1, TimeUnit.SECONDS));
            assertFalse(output.secondWrite.await(150, TimeUnit.MILLISECONDS));

            mixer.setPaused(false);

            assertTrue(output.secondWrite.await(1, TimeUnit.SECONDS));
            assertTrue(output.sameBuffer.get());
            assertEquals(0, output.secondOffset.get());
        } finally {
            output.returnZero.countDown();
            mixer.close();
        }
    }

    @Test
    void invalidWriteCountIsReportedAsDeviceFailureAndRetriesTheBlock() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("invalid.wav"), (short) 1600, BLOCK_FRAMES * 2);
        FakeOutput workingOutput = new FakeOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0
                        ? new InvalidCountOutput()
                        : workingOutput,
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "invalid.wav", 1.0f, true, 0, 0));

            assertTrue(workingOutput.firstWrite.await(2, TimeUnit.SECONDS));
            assertEquals(1600, PcmMath.readLittleEndian(workingOutput.firstBlock.get(), 0));
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            mixer.close();
        }
    }

    @Test
    void nonFrameAlignedWriteCountReportsOnceWithoutAdvancingTheRetryOffset() throws Exception {
        SequencedEngine engine = new SequencedEngine(1);
        NonAlignedCountOutput failedOutput = new NonAlignedCountOutput();
        ReplayCapturingOutput replacement = new ReplayCapturingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> openAttempts.getAndIncrement() == 0 ? failedOutput : replacement,
                library -> engine,
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(replacement.firstWrite.await(2, TimeUnit.SECONDS));
            assertEquals(1, failedOutput.writeCalls.get());
            assertEquals(-1, failedOutput.secondOffset.get());
            assertEquals(0, replacement.firstOffset.get());
            assertEquals(BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize(),
                    replacement.firstLength.get());
            assertEquals(1000, PcmMath.readLittleEndian(replacement.firstBytes.get(), 0));
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            mixer.close();
        }
    }

    @Test
    void nonFrameAlignedRenderedLengthStopsBeforeWritingToTheDevice() throws Exception {
        UnalignedRenderEngine engine = new UnalignedRenderEngine();
        ThreadFailureOutput output = new ThreadFailureOutput();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> output,
                library -> engine,
                failure -> {
                    failures.add(failure);
                    reported.countDown();
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertEquals(AudioFailure.Kind.THREAD, failures.get(0).kind());
            assertTrue(engine.closed.await(1, TimeUnit.SECONDS));
            assertTrue(output.closed.await(1, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertEquals(1, failures.size());
            assertEquals(1, engine.renderCalls.get());
            assertEquals(0, output.writeCalls.get());
        } finally {
            mixer.close();
        }
    }

    @Test
    void closeAfterBlockedWriteReturnsWithoutReportingDeviceFailure() throws Exception {
        assertCloseDuringBlockedWriteIsSilent(false);
    }

    @Test
    void closeAfterBlockedWriteThrowsWithoutReportingDeviceFailure() throws Exception {
        assertCloseDuringBlockedWriteIsSilent(true);
    }

    private void assertCloseDuringBlockedWriteIsSilent(boolean throwFromWrite) throws Exception {
        ShutdownRenderEngine engine = new ShutdownRenderEngine();
        ShutdownBlockingOutput output = new ShutdownBlockingOutput(throwFromWrite);
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> output,
                library -> engine,
                failures::add
        );
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closeThread = new Thread(() -> {
            try {
                mixer.close();
            } catch (Throwable error) {
                closeFailure.set(error);
            }
        }, "PcmAudioMixerTest close");

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());
            assertTrue(output.writeEntered.await(1, TimeUnit.SECONDS));

            closeThread.start();
            closeThread.join(1500L);

            assertFalse(closeThread.isAlive(), "mixer.close() must not wait for its join timeout");
            assertNull(closeFailure.get());
            assertTrue(engine.closed.await(1, TimeUnit.SECONDS));
            assertTrue(output.closed.await(1, TimeUnit.SECONDS));
            assertTrue(failures.isEmpty());
            assertEquals(0, output.postClosePositionReads.get());
            assertEquals(1, engine.renderCalls.get());
            assertEquals(1, output.writeCalls.get());
            assertEquals(2, output.closeCalls.get());
        } finally {
            output.forceRelease();
            closeThread.interrupt();
            closeThread.join(2500L);
            mixer.close();
        }
    }

    @Test
    void zeroLengthRenderedBlockReportsOneThreadFailureAndStopsWorker() throws Exception {
        assertWrongSizedRenderedBlockStopsWorker(0);
    }

    @Test
    void alignedShortRenderedBlockReportsOneThreadFailureAndStopsWorker() throws Exception {
        assertWrongSizedRenderedBlockStopsWorker(AudioStreamFactory.MIX_FORMAT.getFrameSize());
    }

    @Test
    void oversizedRenderedBlockReportsOneThreadFailureAndStopsWorker() throws Exception {
        assertWrongSizedRenderedBlockStopsWorker(BLOCK_BYTES * 2);
    }

    private void assertWrongSizedRenderedBlockStopsWorker(int actualBytes) throws Exception {
        WrongSizedRenderEngine engine = new WrongSizedRenderEngine(actualBytes);
        ThreadFailureOutput output = new ThreadFailureOutput();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> output,
                library -> engine,
                failure -> {
                    failures.add(failure);
                    reported.countDown();
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertTrue(engine.closed.await(1, TimeUnit.SECONDS));
            assertTrue(output.closed.await(1, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertEquals(1, failures.size());
            assertEquals(1L, failures.stream()
                    .filter(failure -> failure.kind() == AudioFailure.Kind.THREAD)
                    .count());
            assertEquals(0L, failures.stream()
                    .filter(failure -> failure.kind() == AudioFailure.Kind.DEVICE)
                    .count());
            assertEquals(0L, failures.stream()
                    .filter(failure -> failure.kind() == AudioFailure.Kind.DECODE)
                    .count());
            assertTrue(failures.get(0).cause().getMessage()
                    .contains("expected=" + BLOCK_BYTES));
            assertTrue(failures.get(0).cause().getMessage()
                    .contains("actual=" + actualBytes));
            assertEquals(1, engine.renderCalls.get());
            assertEquals(0, output.writeCalls.get());
        } finally {
            mixer.close();
        }
    }

    @Test
    void stoppedCommandPreservesPendingBytesUntilARecoveredDeviceWritesThem() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("pending.wav"), (short) 1700, BLOCK_FRAMES * 2);
        SignallingWriteFailingOutput failedOutput = new SignallingWriteFailingOutput();
        CapturingClosingOutput workingOutput = new CapturingClosingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> openAttempts.getAndIncrement() == 0 ? failedOutput : workingOutput,
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "pending.wav", 1.0f, true, 0, 0));
            assertTrue(failedOutput.writeFailed.await(1, TimeUnit.SECONDS));

            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(workingOutput.firstWrite.await(2, TimeUnit.SECONDS));
            assertArrayEquals(failedOutput.attemptedBlock.get(), workingOutput.firstBlock.get());
            assertEquals(1700, PcmMath.readLittleEndian(workingOutput.firstBlock.get(), 0));
            assertTrue(workingOutput.closed.await(2, TimeUnit.SECONDS));
            assertEquals(2, openAttempts.get());
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            mixer.close();
        }
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
        mixer.apply(1L, playing("area", "once.wav", 1.0f, false, 0, 0));

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
        mixer.apply(1L, playing("area", "once.wav", 1.0f, false, 0, 0));
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
        mixer.apply(1L, playing("area", "missing.mp3", 1.0f, true, 0, 0));

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(AudioFailure.Kind.MISSING_FILE, failure.get().kind());
        assertEquals("missing.mp3", failure.get().musicId());
        mixer.close();
    }

    @Test
    void stoppedStateFinishesOutgoingFadeAfterUnavailableDeviceRecovers() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("loop.wav"), (short) 1200, 4096);
        CountDownLatch firstOpenEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOpen = new CountDownLatch(1);
        CapturingClosingOutput workingOutput = new CapturingClosingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> {
                    int attempt = openAttempts.incrementAndGet();
                    if (attempt == 1) {
                        firstOpenEntered.countDown();
                        releaseFirstOpen.await(1, TimeUnit.SECONDS);
                        throw new IllegalStateException("device unavailable");
                    }
                    return workingOutput;
                },
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, playing("area", "loop.wav", 1.0f, true, 0, 20));
            assertTrue(firstOpenEntered.await(1, TimeUnit.SECONDS));

            mixer.apply(1L, PlaybackState.stopped());
            releaseFirstOpen.countDown();

            assertTrue(workingOutput.firstWrite.await(2, TimeUnit.SECONDS));
            byte[] faded = workingOutput.firstBlock.get();
            assertEquals(1200, PcmMath.readLittleEndian(faded, 0));
            assertEquals(0, PcmMath.readLittleEndian(
                    faded, 900 * AudioStreamFactory.MIX_FORMAT.getFrameSize()
            ));
            assertTrue(workingOutput.closed.await(2, TimeUnit.SECONDS));
            assertEquals(2, openAttempts.get());
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            releaseFirstOpen.countDown();
            mixer.close();
        }
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
        mixer.apply(1L, playing("area", "thread.wav", 1.0f, true, 0, 0));

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(AudioFailure.Kind.THREAD, failure.get().kind());
        assertTrue(failure.get().cause() instanceof NoClassDefFoundError);
        mixer.close();
    }

    @Test
    void unexpectedRenderExceptionReportsOneThreadFailureAndStopsWorker() throws Exception {
        ThrowingRenderEngine engine = new ThrowingRenderEngine();
        ThreadFailureOutput output = new ThreadFailureOutput();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        CountDownLatch reported = new CountDownLatch(1);
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> output,
                library -> engine,
                failure -> {
                    failures.add(failure);
                    reported.countDown();
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertEquals(AudioFailure.Kind.THREAD, failures.get(0).kind());
            assertTrue(engine.closed.await(1, TimeUnit.SECONDS));
            assertTrue(output.closed.await(1, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertEquals(1, failures.size());
            assertEquals(1, engine.renderCalls.get());
            assertEquals(0, output.writeCalls.get());
        } finally {
            mixer.close();
        }
    }

    @Test
    void unconfirmedAcceptedBlockIsReplayedFromItsStartAfterDeviceFailure() throws Exception {
        SequencedEngine engine = new SequencedEngine(2);
        AcceptThenFailOutput failedOutput = new AcceptThenFailOutput(0L);
        ReplayCapturingOutput replacement = new ReplayCapturingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> openAttempts.getAndIncrement() == 0 ? failedOutput : replacement,
                library -> engine,
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(replacement.firstWrite.await(2, TimeUnit.SECONDS));
            assertEquals(1000, PcmMath.readLittleEndian(replacement.firstBytes.get(), 0));
            assertEquals(0, replacement.firstOffset.get());
            assertEquals(BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize(),
                    replacement.firstLength.get());
            assertEquals(2, engine.renderCalls.get());
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
        } finally {
            mixer.close();
        }
    }

    @Test
    void partiallyConfirmedBlockReplaysOnlyItsExactUnplayedSuffix() throws Exception {
        int confirmedFrames = 256;
        int confirmedBytes = confirmedFrames * AudioStreamFactory.MIX_FORMAT.getFrameSize();
        SequencedEngine engine = new SequencedEngine(2);
        AcceptThenFailOutput failedOutput = new AcceptThenFailOutput(confirmedFrames);
        ReplayCapturingOutput replacement = new ReplayCapturingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> openAttempts.getAndIncrement() == 0 ? failedOutput : replacement,
                library -> engine,
                failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(replacement.firstWrite.await(2, TimeUnit.SECONDS));
            byte[] original = constantPcmBlock((short) 1000);
            assertEquals(confirmedBytes, replacement.firstOffset.get());
            assertEquals(original.length - confirmedBytes, replacement.firstLength.get());
            byte[] combined = new byte[original.length];
            System.arraycopy(failedOutput.accepted.toByteArray(), 0, combined, 0, confirmedBytes);
            System.arraycopy(replacement.firstBytes.get(), 0, combined, confirmedBytes,
                    replacement.firstBytes.get().length);

            assertArrayEquals(original, combined);
            assertEquals(2, engine.renderCalls.get());
        } finally {
            mixer.close();
        }
    }

    @Test
    void unconfirmedQueueStopsAtEightBlocksAndResumesAfterOneBlockPlays() throws Exception {
        SequencedEngine engine = new SequencedEngine(20);
        StalledPositionOutput output = new StalledPositionOutput();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> output,
                library -> engine,
                failure -> {
                }
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(output.eightWrites.await(1, TimeUnit.SECONDS));
            assertFalse(output.ninthWrite.await(200, TimeUnit.MILLISECONDS));
            assertEquals(8, output.writeCalls.get());
            assertEquals(8, engine.renderCalls.get());

            mixer.apply(1L, PlaybackState.stopped());
            assertTrue(engine.secondApply.await(1, TimeUnit.SECONDS));
            mixer.setPaused(true);
            assertTrue(output.stopped.await(1, TimeUnit.SECONDS));

            output.playedFrames.set(BLOCK_FRAMES);
            mixer.setPaused(false);

            assertTrue(output.ninthWrite.await(1, TimeUnit.SECONDS));
            assertEquals(9, output.writeCalls.get());
            assertEquals(9, engine.renderCalls.get());
        } finally {
            mixer.close();
        }
    }

    @Test
    void backwardPlayedPositionReportsOnceAndReplaysFromLastConfirmation() throws Exception {
        assertInvalidPlayedPositionRecovery(256L, 128L, false, 1024);
    }

    @Test
    void playedPositionBeyondAcceptedReportsOnceAndReplaysFromLastConfirmation() throws Exception {
        assertInvalidPlayedPositionRecovery(512L, 512L, true, 0);
    }

    private void assertInvalidPlayedPositionRecovery(
            long firstPlayedFrames,
            long laterPlayedFrames,
            boolean failSecondWrite,
            int expectedReplayOffset
    ) throws Exception {
        SequencedEngine engine = new SequencedEngine(1);
        InvalidPositionOutput failedOutput = new InvalidPositionOutput(
                firstPlayedFrames,
                laterPlayedFrames,
                failSecondWrite
        );
        ReplayCapturingOutput replacement = new ReplayCapturingOutput();
        AtomicInteger openAttempts = new AtomicInteger();
        List<AudioFailure> failures = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.empty(tempDir.resolve("music")),
                () -> openAttempts.getAndIncrement() == 0 ? failedOutput : replacement,
                library -> engine,
                failures::add
        );

        try {
            mixer.start();
            mixer.apply(1L, PlaybackState.stopped());

            assertTrue(replacement.firstWrite.await(2, TimeUnit.SECONDS));
            assertEquals(expectedReplayOffset, replacement.firstOffset.get());
            assertEquals(
                    BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize()
                            - expectedReplayOffset,
                    replacement.firstLength.get()
            );
            assertEquals(1000, PcmMath.readLittleEndian(replacement.firstBytes.get(), 0));
            assertTrue(failedOutput.closed.await(1, TimeUnit.SECONDS));
            Thread.sleep(50L);
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DEVICE, failures.get(0).kind());
            assertEquals(1, engine.renderCalls.get());
        } finally {
            mixer.close();
        }
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

    private static void writeTwoBlockWav(Path path, short firstSample, short secondSample)
            throws Exception {
        int blockFrames = 1024;
        Files.createDirectories(path.getParent());
        byte[] pcm = new byte[blockFrames * 2 * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < blockFrames * 2; frame++) {
            short sample = frame < blockFrames ? firstSample : secondSample;
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, sample);
            PcmMath.writeLittleEndian(pcm, offset + 2, sample);
        }
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), AudioStreamFactory.MIX_FORMAT, blockFrames * 2L)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static byte[] constantPcmBlock(short sample) {
        byte[] pcm = new byte[BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < BLOCK_FRAMES; frame++) {
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, sample);
            PcmMath.writeLittleEndian(pcm, offset + 2, sample);
        }
        return pcm;
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
        public int write(byte[] pcm, int offset, int length) throws InterruptedException {
            if (firstBlock.compareAndSet(null, pcm.clone())) {
                firstWrite.countDown();
            }
            closed.await(2, TimeUnit.SECONDS);
            return length;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class DelayCapturingOutput implements PcmAudioMixer.AudioOutput {
        private static final int BLOCK_FRAMES = 1024;

        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final CountDownLatch firstNonSilent = new CountDownLatch(1);
        private final AtomicReference<byte[]> firstBlock = new AtomicReference<>();
        private final AtomicInteger blocksWritten = new AtomicInteger();
        private final AtomicInteger firstNonSilentFrame = new AtomicInteger(-1);
        private final AtomicLong playedFrames = new AtomicLong();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            firstBlock.compareAndSet(null, pcm.clone());
            firstWrite.countDown();
            int block = blocksWritten.getAndIncrement();
            int frameSize = AudioStreamFactory.MIX_FORMAT.getFrameSize();
            int firstFrame = offset / frameSize;
            int endFrame = (offset + length) / frameSize;
            for (int frame = firstFrame; frame < endFrame; frame++) {
                if (PcmMath.readLittleEndian(pcm, frame * frameSize) == 0) {
                    continue;
                }
                if (firstNonSilentFrame.compareAndSet(-1, block * BLOCK_FRAMES + frame)) {
                    firstNonSilent.countDown();
                }
                break;
            }
            playedFrames.addAndGet(length / frameSize);
            return length;
        }

        @Override
        public long playedFrames() {
            return playedFrames.get();
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class PausableDelayOutput implements PcmAudioMixer.AudioOutput {
        private static final int BLOCK_FRAMES = 1024;

        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch releaseFirstWrite = new CountDownLatch(1);
        private final CountDownLatch secondWrite = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final CountDownLatch firstNonSilent = new CountDownLatch(1);
        private final AtomicInteger blocksWritten = new AtomicInteger();
        private final AtomicInteger firstNonSilentFrame = new AtomicInteger(-1);
        private final AtomicLong playedFrames = new AtomicLong();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            stopped.countDown();
        }

        @Override
        public int write(byte[] pcm, int offset, int length) throws InterruptedException {
            int block = blocksWritten.getAndIncrement();
            if (block == 0) {
                firstWriteEntered.countDown();
                releaseFirstWrite.await(2, TimeUnit.SECONDS);
            } else {
                secondWrite.countDown();
            }
            int frameSize = AudioStreamFactory.MIX_FORMAT.getFrameSize();
            int firstFrame = offset / frameSize;
            int endFrame = (offset + length) / frameSize;
            for (int frame = firstFrame; frame < endFrame; frame++) {
                if (PcmMath.readLittleEndian(pcm, frame * frameSize) == 0) {
                    continue;
                }
                if (firstNonSilentFrame.compareAndSet(-1, block * BLOCK_FRAMES + frame)) {
                    firstNonSilent.countDown();
                }
                break;
            }
            playedFrames.addAndGet(length / frameSize);
            return length;
        }

        @Override
        public long playedFrames() {
            return playedFrames.get();
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            releaseFirstWrite.countDown();
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
        public int write(byte[] pcm, int offset, int length) {
            return length;
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
        private final AtomicReference<byte[]> attemptedBlock;

        private WriteFailingOutput(AtomicBoolean closed) {
            this(closed, null);
        }

        private WriteFailingOutput(
                AtomicBoolean closed,
                AtomicReference<byte[]> attemptedBlock
        ) {
            this.closed = closed;
            this.attemptedBlock = attemptedBlock;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            if (attemptedBlock != null) {
                attemptedBlock.compareAndSet(null, pcm.clone());
            }
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
        public int write(byte[] pcm, int offset, int length) {
            firstWrite.countDown();
            return length;
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
        public int write(byte[] pcm, int offset, int length) throws InterruptedException {
            firstWrite.countDown();
            releaseWrite.await(2, TimeUnit.SECONDS);
            return length;
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

    private static final class ChunkedOutput implements PcmAudioMixer.AudioOutput {
        private final int[] chunks;
        private final List<Integer> offsets = new CopyOnWriteArrayList<>();
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CountDownLatch complete = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<byte[]> completedBlock = new AtomicReference<>();
        private int chunkIndex;

        private ChunkedOutput(int... chunks) {
            this.chunks = chunks;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public synchronized int write(byte[] pcm, int offset, int length)
                throws InterruptedException {
            int blockBytes = BLOCK_FRAMES * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            if (received.size() == blockBytes) {
                closed.await(2, TimeUnit.SECONDS);
                return 0;
            }
            int count = Math.min(chunks[chunkIndex++], Math.min(length, blockBytes - received.size()));
            offsets.add(offset);
            received.write(pcm, offset, count);
            if (received.size() == blockBytes) {
                completedBlock.set(received.toByteArray());
                complete.countDown();
            }
            return count;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ZeroThenCompleteOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch complete = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean sameBuffer = new AtomicBoolean();
        private final AtomicInteger secondOffset = new AtomicInteger(-1);
        private final AtomicInteger secondLength = new AtomicInteger(-1);
        private final java.util.concurrent.atomic.AtomicLong delayNanos =
                new java.util.concurrent.atomic.AtomicLong();
        private byte[] firstBuffer;
        private long firstReturnNanos;
        private int calls;

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) throws InterruptedException {
            if (calls++ == 0) {
                firstBuffer = pcm;
                firstReturnNanos = System.nanoTime();
                return 0;
            }
            if (calls == 2) {
                delayNanos.set(System.nanoTime() - firstReturnNanos);
                sameBuffer.set(firstBuffer == pcm);
                secondOffset.set(offset);
                secondLength.set(length);
                complete.countDown();
                return length;
            }
            closed.await(2, TimeUnit.SECONDS);
            return 0;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class PrefixThenFailingOutput implements PcmAudioMixer.AudioOutput {
        private final int prefixLength;
        private final ByteArrayOutputStream prefix = new ByteArrayOutputStream();
        private final AtomicInteger failureOffset = new AtomicInteger(-1);
        private int calls;

        private PrefixThenFailingOutput(int prefixLength) {
            this.prefixLength = prefixLength;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            if (calls++ == 0) {
                prefix.write(pcm, offset, prefixLength);
                return prefixLength;
            }
            failureOffset.set(offset);
            throw new IllegalStateException("write failed after prefix");
        }

        @Override
        public long playedFrames() {
            return prefix.size() / AudioStreamFactory.MIX_FORMAT.getFrameSize();
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class SuffixCapturingOutput implements PcmAudioMixer.AudioOutput {
        private final int expectedOffset;
        private final ByteArrayOutputStream suffix = new ByteArrayOutputStream();
        private final AtomicInteger firstOffset = new AtomicInteger(-1);
        private final CountDownLatch complete = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private int calls;

        private SuffixCapturingOutput(int expectedOffset) {
            this.expectedOffset = expectedOffset;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) throws InterruptedException {
            if (calls++ == 0) {
                firstOffset.set(offset);
                if (offset != expectedOffset) {
                    throw new AssertionError("unexpected suffix offset " + offset);
                }
                suffix.write(pcm, offset, length);
                complete.countDown();
                return length;
            }
            closed.await(2, TimeUnit.SECONDS);
            return 0;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ControlledZeroOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch firstWriteEntered = new CountDownLatch(1);
        private final CountDownLatch returnZero = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);
        private final CountDownLatch secondWrite = new CountDownLatch(1);
        private final AtomicBoolean sameBuffer = new AtomicBoolean();
        private final AtomicInteger secondOffset = new AtomicInteger(-1);
        private byte[] firstBuffer;
        private int calls;

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            stopped.countDown();
        }

        @Override
        public int write(byte[] pcm, int offset, int length) throws InterruptedException {
            if (calls++ == 0) {
                firstBuffer = pcm;
                firstWriteEntered.countDown();
                returnZero.await(2, TimeUnit.SECONDS);
                return 0;
            }
            sameBuffer.set(firstBuffer == pcm);
            secondOffset.set(offset);
            secondWrite.countDown();
            return length;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            returnZero.countDown();
        }
    }

    private static final class InvalidCountOutput implements PcmAudioMixer.AudioOutput {
        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            return length + 1;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class NonAlignedCountOutput implements PcmAudioMixer.AudioOutput {
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger secondOffset = new AtomicInteger(-1);

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            if (writeCalls.incrementAndGet() == 1) {
                return AudioStreamFactory.MIX_FORMAT.getFrameSize() + 1;
            }
            secondOffset.set(offset);
            throw new IllegalStateException("unaligned write position was reused");
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class SignallingWriteFailingOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch writeFailed = new CountDownLatch(1);
        private final AtomicReference<byte[]> attemptedBlock = new AtomicReference<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            attemptedBlock.compareAndSet(null, pcm.clone());
            writeFailed.countDown();
            throw new IllegalStateException("write failed");
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class CapturingClosingOutput implements PcmAudioMixer.AudioOutput {
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
        public int write(byte[] pcm, int offset, int length) {
            if (firstBlock.compareAndSet(null, pcm.clone())) {
                firstWrite.countDown();
            }
            return length;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ThrowingRenderEngine implements PcmAudioMixer.AudioEngine {
        private final AtomicBoolean work = new AtomicBoolean();
        private final AtomicInteger renderCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            work.set(true);
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            renderCalls.incrementAndGet();
            work.set(false);
            throw new IllegalStateException("unexpected render failure");
        }

        @Override
        public List<AudioFailure> drainFailures() {
            return List.of();
        }

        @Override
        public boolean hasWork() {
            return work.get();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class RevisionRecordingEngine implements PcmAudioMixer.AudioEngine {
        private final AtomicLong revision = new AtomicLong(-1L);
        private final AtomicReference<PlaybackState> state = new AtomicReference<>();
        private final CountDownLatch applied = new CountDownLatch(1);

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            this.revision.set(revision);
            this.state.set(state);
            applied.countDown();
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            return new byte[BLOCK_BYTES];
        }

        @Override
        public List<AudioFailure> drainFailures() {
            return List.of();
        }

        @Override
        public boolean hasWork() {
            return false;
        }

        @Override
        public void close() {
        }
    }

    private static final class UnalignedRenderEngine implements PcmAudioMixer.AudioEngine {
        private final AtomicBoolean work = new AtomicBoolean();
        private final AtomicInteger renderCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            work.set(true);
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            renderCalls.incrementAndGet();
            work.set(false);
            return new byte[AudioStreamFactory.MIX_FORMAT.getFrameSize() + 1];
        }

        @Override
        public List<AudioFailure> drainFailures() {
            return List.of();
        }

        @Override
        public boolean hasWork() {
            return work.get();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ShutdownRenderEngine implements PcmAudioMixer.AudioEngine {
        private final AtomicBoolean work = new AtomicBoolean();
        private final AtomicInteger renderCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            work.set(true);
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            renderCalls.incrementAndGet();
            work.set(false);
            return constantPcmBlock((short) 1000);
        }

        @Override
        public List<AudioFailure> drainFailures() {
            return List.of();
        }

        @Override
        public boolean hasWork() {
            return work.get();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ShutdownBlockingOutput implements PcmAudioMixer.AudioOutput {
        private final boolean throwFromWrite;
        private final AtomicBoolean closedState = new AtomicBoolean();
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicInteger postClosePositionReads = new AtomicInteger();
        private final CountDownLatch writeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseWrite = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);

        private ShutdownBlockingOutput(boolean throwFromWrite) {
            this.throwFromWrite = throwFromWrite;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            writeCalls.incrementAndGet();
            writeEntered.countDown();
            while (!closedState.get()) {
                try {
                    releaseWrite.await();
                } catch (InterruptedException ignored) {
                }
            }
            if (throwFromWrite) {
                throw new IllegalStateException("output closed during write");
            }
            return length;
        }

        @Override
        public long playedFrames() {
            if (closedState.get()) {
                postClosePositionReads.incrementAndGet();
                return 0L;
            }
            return 100L;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            int call = closeCalls.incrementAndGet();
            closedState.set(true);
            releaseWrite.countDown();
            closed.countDown();
            if (call > 1) {
                throw new IllegalStateException("output closed more than once");
            }
        }

        private void forceRelease() {
            closedState.set(true);
            releaseWrite.countDown();
        }
    }

    private static final class WrongSizedRenderEngine implements PcmAudioMixer.AudioEngine {
        private final int renderedBytes;
        private final AtomicBoolean work = new AtomicBoolean();
        private final AtomicInteger renderCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private WrongSizedRenderEngine(int renderedBytes) {
            this.renderedBytes = renderedBytes;
        }

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            work.set(true);
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            renderCalls.incrementAndGet();
            work.set(false);
            return new byte[renderedBytes];
        }

        @Override
        public List<AudioFailure> drainFailures() {
            return List.of();
        }

        @Override
        public boolean hasWork() {
            return work.get();
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class ThreadFailureOutput implements PcmAudioMixer.AudioOutput {
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            writeCalls.incrementAndGet();
            return length;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class SequencedEngine implements PcmAudioMixer.AudioEngine {
        private final int blockCount;
        private final AtomicBoolean applied = new AtomicBoolean();
        private final AtomicInteger applyCalls = new AtomicInteger();
        private final AtomicInteger renderCalls = new AtomicInteger();
        private final CountDownLatch secondApply = new CountDownLatch(1);

        private SequencedEngine(int blockCount) {
            this.blockCount = blockCount;
        }

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            applied.set(true);
            if (applyCalls.incrementAndGet() == 2) {
                secondApply.countDown();
            }
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            int block = renderCalls.getAndIncrement();
            return constantPcmBlock((short) ((block + 1) * 1000));
        }

        @Override
        public List<AudioFailure> drainFailures() {
            return List.of();
        }

        @Override
        public boolean hasWork() {
            return applied.get() && renderCalls.get() < blockCount;
        }

        @Override
        public void close() {
        }
    }

    private static final class StalledPositionOutput implements PcmAudioMixer.AudioOutput {
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final AtomicLong playedFrames = new AtomicLong();
        private final CountDownLatch eightWrites = new CountDownLatch(8);
        private final CountDownLatch ninthWrite = new CountDownLatch(1);
        private final CountDownLatch stopped = new CountDownLatch(1);

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            stopped.countDown();
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            int call = writeCalls.incrementAndGet();
            eightWrites.countDown();
            if (call == 9) {
                ninthWrite.countDown();
            }
            return length;
        }

        @Override
        public long playedFrames() {
            return playedFrames.get();
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class InvalidPositionOutput implements PcmAudioMixer.AudioOutput {
        private static final int ACCEPTED_FRAMES_PER_WRITE = 256;

        private final long firstPlayedFrames;
        private final long laterPlayedFrames;
        private final boolean failSecondWrite;
        private final AtomicInteger writeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private InvalidPositionOutput(
                long firstPlayedFrames,
                long laterPlayedFrames,
                boolean failSecondWrite
        ) {
            this.firstPlayedFrames = firstPlayedFrames;
            this.laterPlayedFrames = laterPlayedFrames;
            this.failSecondWrite = failSecondWrite;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            int call = writeCalls.incrementAndGet();
            if (call == 2 && failSecondWrite) {
                throw new IllegalStateException("device failed after invalid position");
            }
            return ACCEPTED_FRAMES_PER_WRITE * AudioStreamFactory.MIX_FORMAT.getFrameSize();
        }

        @Override
        public long playedFrames() {
            int calls = writeCalls.get();
            if (calls == 0) {
                return 0L;
            }
            return calls == 1 ? firstPlayedFrames : laterPlayedFrames;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class AcceptThenFailOutput implements PcmAudioMixer.AudioOutput {
        private final long confirmedFrames;
        private final ByteArrayOutputStream accepted = new ByteArrayOutputStream();
        private int writeCalls;

        private AcceptThenFailOutput(long confirmedFrames) {
            this.confirmedFrames = confirmedFrames;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            if (writeCalls++ == 0) {
                accepted.write(pcm, offset, length);
                return length;
            }
            throw new IllegalStateException("device failed after accepting a block");
        }

        @Override
        public long playedFrames() {
            return writeCalls == 0 ? 0L : confirmedFrames;
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }

    private static final class ReplayCapturingOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final AtomicReference<byte[]> firstBytes = new AtomicReference<>();
        private final AtomicInteger firstOffset = new AtomicInteger(-1);
        private final AtomicInteger firstLength = new AtomicInteger(-1);
        private final AtomicLong acceptedFrames = new AtomicLong();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public int write(byte[] pcm, int offset, int length) {
            if (firstBytes.compareAndSet(null, java.util.Arrays.copyOfRange(
                    pcm, offset, offset + length))) {
                firstOffset.set(offset);
                firstLength.set(length);
                firstWrite.countDown();
            }
            acceptedFrames.addAndGet(length / AudioStreamFactory.MIX_FORMAT.getFrameSize());
            return length;
        }

        @Override
        public long playedFrames() {
            return acceptedFrames.get();
        }

        @Override
        public void drain() {
        }

        @Override
        public void close() {
        }
    }
}
