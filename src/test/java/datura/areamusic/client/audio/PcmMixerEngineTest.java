package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmMixerEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void publicApplyAndRenderApisDoNotDeclarePerTrackFailures() throws Exception {
        assertThrows(
                NoSuchMethodException.class,
                () -> PcmMixerEngine.class.getMethod("apply", PlaybackState.class)
        );
        assertEquals(
                0,
                PcmMixerEngine.class
                        .getMethod("apply", long.class, PlaybackState.class)
                        .getExceptionTypes().length
        );
        assertEquals(
                0,
                PcmMixerEngine.class
                        .getMethod("renderFrames", int.class, float.class)
                        .getExceptionTypes().length
        );
    }

    @Test
    void renderFrameBufferSizeOverflowThrowsArithmeticException() {
        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.empty(tempDir.resolve("music")))) {
            assertThrows(
                    ArithmeticException.class,
                    () -> engine.renderFrames(Integer.MAX_VALUE, 1.0f)
            );
        }
    }

    @Test
    void startsDelayedTrackOnTheExactFrameWhileAnotherTrackKeepsPlaying() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("first.wav"), constantFrames(44_101, (short) 1000));
        writeWav(root.resolve("second.wav"), constantFrames(44_101, (short) 2000));

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("first.wav", 0, true, 0, 0),
                    track("second.wav", 1, true, 0, 0)
            ));

            byte[] beforeDelay = engine.renderFrames(44_100, 1.0f);

            assertEquals(1000, leftSample(beforeDelay, 44_099));
            assertEquals(3000, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void startsMultipleDueNonLoopTracksOnTheSameFrame() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("first.wav"), new short[]{1000});
        writeWav(root.resolve("second.wav"), new short[]{2000});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("first.wav", 0, false, 0, 0),
                    track("second.wav", 0, false, 0, 0)
            ));

            assertEquals(3000, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void appliesEachTrackVolumeBeforeMixing() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("first.wav"), new short[]{10_000, 10_000});
        writeWav(root.resolve("second.wav"), new short[]{10_000, 10_000});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("first.wav", 0, 0.5f, false, 0, 0),
                    track("second.wav", 0, 0.25f, false, 0, 0)
            ));

            assertEquals(7500, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(3750, firstLeftSample(engine.renderFrames(1, 0.5f)));
        }
    }

    @Test
    void zeroVolumeLoopKeepsReadingAndContinuesFromItsAdvancedFrame() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("ramp.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "quiet",
                    false,
                    track("ramp.wav", 0, 0.0f, true, 0, 20)
            ));

            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.hasWork());

            engine.apply(1L, state(
                    "audible",
                    false,
                    track("ramp.wav", 0, 1.0f, true, 0, 20)
            ));

            assertEquals(2000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, openCount.get());
        }
    }

    @Test
    void zeroVolumeLoopClosesOnlyAfterItsOutgoingFadeCompletes() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("quiet.wav"), constantFrames(2000, (short) 1234));
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream stream = new CloseCountingAudioInputStream(
                pcmFrames(constantFrames(2000, (short) 1234)), closeCount
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "quiet",
                    false,
                    track("quiet.wav", 0, 0.0f, true, 0, 20)
            ));
            engine.renderFrames(1, 1.0f);

            engine.apply(1L, PlaybackState.stopped());

            assertTrue(engine.hasWork());
            assertEquals(0, closeCount.get());

            engine.renderFrames(882, 1.0f);

            assertFalse(engine.hasWork());
            assertEquals(1, closeCount.get());
        }
    }

    @Test
    void opensSimultaneouslyDueDuplicateIdsInStableJsonOrder() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("same.wav"), new short[]{1000});
        writeWav(root.resolve("third.wav"), new short[]{2000});
        List<String> openOrder = new ArrayList<>();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openOrder.add(path.getFileName().toString());
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("same.wav", 0, false, 0, 0),
                    track("same.wav", 0, false, 0, 0),
                    track("third.wav", 0, false, 0, 0)
            ));

            assertEquals(List.of("same.wav", "same.wav", "third.wav"), openOrder);
            assertEquals(4000, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void loopRestartAdvancesOnlyTheTrackThatReachedEof() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("short-loop.wav"), new short[]{100, 200});
        writeWav(root.resolve("long-once.wav"), new short[]{1000, 2000, 3000});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("short-loop.wav", 0, true, 0, 0),
                    track("long-once.wav", 0, false, 0, 0)
            ));

            byte[] rendered = engine.renderFrames(3, 1.0f);

            assertEquals(1100, leftSample(rendered, 0));
            assertEquals(2200, leftSample(rendered, 1));
            assertEquals(3100, leftSample(rendered, 2));
        }
    }

    @Test
    void nonLoopEofCompletesOnlyThatTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{1000});
        writeWav(root.resolve("loop.wav"), new short[]{2000});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("once.wav", 0, false, 0, 0),
                    track("loop.wav", 0, true, 0, 0)
            ));

            byte[] rendered = engine.renderFrames(2, 1.0f);

            assertEquals(3000, leftSample(rendered, 0));
            assertEquals(2000, leftSample(rendered, 1));
            assertTrue(engine.hasWork());
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void saturatesMixedSamplesAtSignedPcmBounds() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("positive.wav"), new short[]{30_000});
        writeWav(root.resolve("negative.wav"), new short[]{-30_000});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "positive",
                    false,
                    track("positive.wav", 0, false, 0, 0),
                    track("positive.wav", 0, false, 0, 0)
            ));
            assertEquals(Short.MAX_VALUE, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "negative",
                    false,
                    track("negative.wav", 0, false, 0, 0),
                    track("negative.wav", 0, false, 0, 0)
            ));
            assertEquals(Short.MIN_VALUE, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void largeRenderBlockCrossesMultipleDelayBoundariesExactly() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("first.wav"), constantFrames(88_201, (short) 1000));
        writeWav(root.resolve("second.wav"), constantFrames(88_201, (short) 2000));
        writeWav(root.resolve("third.wav"), constantFrames(88_201, (short) 4000));

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("first.wav", 0, true, 0, 0),
                    track("second.wav", 1, true, 0, 0),
                    track("third.wav", 2, true, 0, 0)
            ));

            byte[] rendered = engine.renderFrames(88_201, 1.0f);

            assertEquals(1000, leftSample(rendered, 44_099));
            assertEquals(3000, leftSample(rendered, 44_100));
            assertEquals(3000, leftSample(rendered, 88_199));
            assertEquals(7000, leftSample(rendered, 88_200));
        }
    }

    @Test
    void stoppedTransitionAndRepeatedCloseReleaseEachStreamOnce() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("first.wav"), new short[]{1000});
        writeWav(root.resolve("second.wav"), new short[]{2000});
        AtomicInteger firstCloseCount = new AtomicInteger();
        AtomicInteger secondCloseCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                boolean first = path.getFileName().toString().equals("first.wav");
                return new CloseCountingAudioInputStream(
                        pcmFrames(first ? (short) 1000 : (short) 2000),
                        first ? firstCloseCount : secondCloseCount
                );
            }
        };
        PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root));
        engine.apply(1L, state(
                "area",
                false,
                track("first.wav", 0, true, 0, 0),
                track("second.wav", 0, true, 0, 0)
        ));
        engine.apply(1L, PlaybackState.stopped());

        assertEquals(1, firstCloseCount.get());
        assertEquals(1, secondCloseCount.get());

        engine.close();
        engine.close();

        assertEquals(1, firstCloseCount.get());
        assertEquals(1, secondCloseCount.get());
    }

    @Test
    void missingDueTrackDoesNotStopAnotherDueTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("good.wav"), new short[]{2345});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("missing.wav", 0, false, 0, 0),
                    track("good.wav", 0, false, 0, 0)
            ));

            assertEquals(2345, firstLeftSample(engine.renderFrames(1, 1.0f)));
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.MISSING_FILE, failures.get(0).kind());
            assertEquals("missing.wav", failures.get(0).musicId());
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void reportsPendingDelayedTrackAsWorkUntilItIsCancelled() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("delayed.wav"), new short[]{1234});

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area", false, track("delayed.wav", 1, false, 0, 0)
            ));

            assertTrue(engine.hasWork());

            engine.apply(1L, PlaybackState.stopped());

            assertFalse(engine.hasWork());
        }
    }

    @Test
    void openFailureDoesNotStopAnotherDueTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{1111});
        writeWav(root.resolve("good.wav"), new short[]{2345});
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("bad.wav")) {
                    throw new IOException("open failed");
                }
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, false, 0, 0),
                    track("good.wav", 0, false, 0, 0)
            ));

            assertEquals(2345, firstLeftSample(engine.renderFrames(1, 1.0f)));
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertEquals("bad.wav", failures.get(0).musicId());
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void firstDecodeFailureDoesNotStopAnotherActiveTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{1111});
        writeWav(root.resolve("good.wav"), new short[]{2345});
        AtomicInteger badCloseCount = new AtomicInteger();
        AudioInputStream badStream = new ThrowingReadAudioInputStream(
                pcmFrames((short) 1111), 0, badCloseCount
        );
        AudioStreamFactory factory = streamFactoryWithBadStream(badStream);

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, false, 0, 0),
                    track("good.wav", 0, true, 0, 0)
            ));

            assertEquals(2345, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertSingleDecodeFailure(engine, "bad.wav");
            assertEquals(1, badCloseCount.get());
        }
    }

    @Test
    void runningDecodeFailureTerminatesOnlyThatTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{1111});
        writeWav(root.resolve("good.wav"), new short[]{2345});
        AtomicInteger badCloseCount = new AtomicInteger();
        AudioInputStream badStream = new ThrowingReadAudioInputStream(
                pcmFrames((short) 1111), 1, badCloseCount
        );
        AudioStreamFactory factory = streamFactoryWithBadStream(badStream);

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, false, 0, 0),
                    track("good.wav", 0, true, 0, 0)
            ));

            assertEquals(3456, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(2345, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertSingleDecodeFailure(engine, "bad.wav");
            assertEquals(1, badCloseCount.get());
        }
    }

    @Test
    void decodeFailureAfterReadablePrefixStillMixesTheCompletePrefixFrames() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{100, 200});
        writeWav(root.resolve("good.wav"), new short[]{1000, 1000, 1000});
        AtomicInteger badCloseCount = new AtomicInteger();
        AudioInputStream badStream = new ThrowingReadAudioInputStream(
                pcmFrames((short) 100, (short) 200), 1, badCloseCount
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                streamFactoryWithBadStream(badStream), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, false, 0, 0),
                    track("good.wav", 0, true, 0, 0)
            ));

            byte[] rendered = engine.renderFrames(3, 1.0f);

            assertEquals(1100, leftSample(rendered, 0));
            assertEquals(1200, leftSample(rendered, 1));
            assertEquals(1000, leftSample(rendered, 2));
            assertSingleDecodeFailure(engine, "bad.wav");
            assertEquals(1, badCloseCount.get());

            engine.renderFrames(1, 1.0f);
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void loopReopenFailureAfterReadablePrefixStillMixesThePrefixFrames() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{100, 200});
        writeWav(root.resolve("good.wav"), new short[]{1000, 1000, 1000});
        AtomicInteger badOpenCount = new AtomicInteger();
        AtomicInteger badCloseCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (!path.getFileName().toString().equals("bad.wav")) {
                    return super.open(path);
                }
                if (badOpenCount.getAndIncrement() > 0) {
                    throw new IOException("reopen failed after prefix");
                }
                return new CloseCountingAudioInputStream(
                        pcmFrames((short) 100, (short) 200), badCloseCount
                );
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, true, 0, 0),
                    track("good.wav", 0, true, 0, 0)
            ));

            byte[] rendered = engine.renderFrames(3, 1.0f);

            assertEquals(1100, leftSample(rendered, 0));
            assertEquals(1200, leftSample(rendered, 1));
            assertEquals(1000, leftSample(rendered, 2));
            assertSingleDecodeFailure(engine, "bad.wav");
            assertEquals(2, badOpenCount.get());
            assertEquals(1, badCloseCount.get());
        }
    }

    @Test
    void partialPcmFrameIsRejectedWithoutMixingIllegalBytes() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{777});
        writeWav(root.resolve("good.wav"), new short[]{1000});
        AtomicInteger badCloseCount = new AtomicInteger();
        AudioInputStream badStream = new PartialFrameAudioInputStream(
                pcmFrames((short) 777), badCloseCount
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                streamFactoryWithBadStream(badStream), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, false, 0, 0),
                    track("good.wav", 0, true, 0, 0)
            ));

            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            PcmMixerEngine.AudioPlaybackException failure = assertInstanceOf(
                    PcmMixerEngine.AudioPlaybackException.class, failures.get(0).cause()
            );
            assertInstanceOf(IOException.class, failure.getCause());
            assertTrue(failure.getCause().getMessage().contains("partial PCM frame"));
            assertTrue(engine.drainFailures().isEmpty());
            assertEquals(1, badCloseCount.get());
        }
    }

    @Test
    void loopReopenFailureTerminatesOnlyThatTrackWithoutDoubleClose() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{1111});
        writeWav(root.resolve("good.wav"), new short[]{2345});
        AtomicInteger badOpenCount = new AtomicInteger();
        AtomicInteger badCloseCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (!path.getFileName().toString().equals("bad.wav")) {
                    return super.open(path);
                }
                if (badOpenCount.getAndIncrement() > 0) {
                    throw new IOException("reopen failed");
                }
                return new CloseCountingAudioInputStream(
                        pcmFrames((short) 1111), badCloseCount
                );
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("bad.wav", 0, true, 0, 0),
                    track("good.wav", 0, true, 0, 0)
            ));

            assertEquals(3456, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(2345, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertSingleDecodeFailure(engine, "bad.wav");
            assertEquals(2, badOpenCount.get());
            assertEquals(1, badCloseCount.get());
        }
    }

    @Test
    void keepsStreamPositionWhenAdjacentAreasUseTheSameMusicId() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("track.wav"), new short[]{1000, 2000, 3000});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(1L, playing("first", "track.wav", 1.0f, false, 0, 0));
        assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

        engine.apply(1L, playing("second", "track.wav", 0.5f, false, 0, 0));
        assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

        engine.close();
    }

    @Test
    void matchesImmediateSameMusicIdsByOccurrenceOrderAcrossAreas() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("shared.wav"), new short[]{1000, 2000, 3000});
        writeWav(root.resolve("old-marker.wav"), constantFrames(3, (short) 0));
        writeWav(root.resolve("new-marker.wav"), constantFrames(3, (short) 0));
        List<String> openOrder = new ArrayList<>();
        AtomicInteger sharedOpenCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                String fileName = path.getFileName().toString();
                openOrder.add(fileName);
                if (fileName.equals("shared.wav")) {
                    sharedOpenCount.incrementAndGet();
                }
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "first",
                    false,
                    track("shared.wav", 0, true, 0, 0),
                    track("old-marker.wav", 0, true, 0, 0),
                    track("shared.wav", 0, true, 0, 0)
            ));
            assertEquals(2000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "second",
                    false,
                    track("new-marker.wav", 0, true, 0, 0),
                    track("shared.wav", 0, true, 0, 0),
                    track("shared.wav", 0, true, 0, 0)
            ));

            assertEquals(4000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(2, sharedOpenCount.get());
            assertEquals(
                    List.of("shared.wav", "old-marker.wav", "shared.wav", "new-marker.wav"),
                    openOrder
            );
        }
    }

    @Test
    void delayedDestinationTrackDoesNotStealAnOutgoingSameIdStream() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("shared.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "immediate", false, track("shared.wav", 0, true, 0, 0)
            ));
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "delayed", false, track("shared.wav", 5, true, 0, 0)
            ));

            byte[] firstSecond = engine.renderFrames(44_100, 1.0f);
            assertEquals(0, leftSample(firstSecond, 44_099));
            assertEquals(1, openCount.get());
            assertAllSilent(engine.renderFrames(176_399, 1.0f));
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(2, openCount.get());
        }
    }

    @Test
    void sameIdOccurrenceIdentityIncludesPendingSourceDefinitions() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("shared.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "source",
                    false,
                    track("shared.wav", 5, 0.0f, true, 0, 0),
                    track("shared.wav", 0, 1.0f, true, 0, 0)
            ));
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "destination",
                    false,
                    track("shared.wav", 0, 0.0f, true, 0, 0),
                    track("shared.wav", 0, 1.0f, true, 0, 0)
            ));

            assertEquals(2000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(3000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(2, openCount.get());
        }
    }

    @Test
    void sameIdContinuationFadesVolumeFromZeroToOneWithoutReopening() throws Exception {
        Path root = tempDir.resolve("music");
        short[] frames = constantFrames(44_103, (short) 10_000);
        frames[0] = 1000;
        frames[44_101] = 12_000;
        writeWav(root.resolve("ramp.wav"), frames);
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "quiet",
                    false,
                    track("ramp.wav", 0, 0.0f, false, 0, 0)
            ));
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "audible",
                    false,
                    track("ramp.wav", 0, 1.0f, false, 1000, 0)
            ));

            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.renderFrames(22_049, 1.0f);
            assertEquals(5000, firstLeftSample(engine.renderFrames(1, 1.0f)), 2);
            engine.renderFrames(22_049, 1.0f);
            assertEquals(12_000, firstLeftSample(engine.renderFrames(1, 1.0f)), 2);
            assertEquals(1, openCount.get());
        }
    }

    @Test
    void sameIdContinuationFadesVolumeFromOneToZeroWithoutImmediateSilence() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("constant.wav"), constantFrames(44_103, (short) 10_000));
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "audible",
                    false,
                    track("constant.wav", 0, 1.0f, false, 0, 0)
            ));
            assertEquals(10_000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "quiet",
                    false,
                    track("constant.wav", 0, 0.0f, false, 1000, 0)
            ));

            assertEquals(10_000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.renderFrames(22_049, 1.0f);
            assertEquals(5000, firstLeftSample(engine.renderFrames(1, 1.0f)), 2);
            engine.renderFrames(22_049, 1.0f);
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.hasWork());
            assertEquals(1, openCount.get());
        }
    }

    @Test
    void outgoingFadeFreezesAnInProgressVolumeRiseAndRemainsMonotonic() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("constant.wav"), new short[]{10_000});
        AtomicInteger closeCount = new AtomicInteger();
        AudioInputStream stream = new CloseCountingAudioInputStream(
                pcmFrames(constantFrames(70_000, (short) 10_000)), closeCount
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "quiet",
                    false,
                    track("constant.wav", 0, 0.0f, true, 0, 1000)
            ));
            engine.renderFrames(1, 1.0f);
            engine.apply(1L, state(
                    "audible",
                    false,
                    track("constant.wav", 0, 1.0f, true, 1000, 1000)
            ));
            engine.renderFrames(22_050, 1.0f);

            engine.apply(1L, PlaybackState.stopped());

            int firstOutgoing = firstLeftSample(engine.renderFrames(1, 1.0f));
            engine.renderFrames(11_024, 1.0f);
            int quarterFade = firstLeftSample(engine.renderFrames(1, 1.0f));

            assertEquals(5000, firstOutgoing, 2);
            assertEquals(3750, quarterFade, 2);
            assertTrue(quarterFade < firstOutgoing);

            engine.renderFrames(33_074, 1.0f);

            assertFalse(engine.hasWork());
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, closeCount.get());
        }
    }

    @Test
    void crossfadesOldAndNewTracksAtTheSameTime() throws Exception {
        Path root = tempDir.resolve("music");
        short[] positive = constantFrames(50_000, (short) 10_000);
        short[] negative = constantFrames(50_000, (short) -10_000);
        writeWav(root.resolve("old.wav"), positive);
        writeWav(root.resolve("new.wav"), negative);
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(1L, playing("old", "old.wav", 1.0f, false, 0, 1000));
        engine.renderFrames(1, 1.0f);
        engine.apply(1L, playing("new", "new.wav", 1.0f, false, 1000, 1000));

        engine.renderFrames(22_050, 1.0f);
        int halfWaySample = firstLeftSample(engine.renderFrames(1, 1.0f));

        assertEquals(0, halfWaySample, 2);
        engine.close();
    }

    @Test
    void appliesEachTrackFadeInIndependently() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("immediate.wav"), constantFrames(50_000, (short) 10_000));
        writeWav(root.resolve("fading.wav"), constantFrames(50_000, (short) 10_000));

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "area",
                    false,
                    track("immediate.wav", 0, true, 0, 0),
                    track("fading.wav", 0, true, 1000, 0)
            ));

            assertEquals(10_000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.renderFrames(22_049, 1.0f);
            assertEquals(15_000, firstLeftSample(engine.renderFrames(1, 1.0f)), 2);
        }
    }

    @Test
    void appliesEachOutgoingTrackFadeOutIndependently() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("immediate.wav"), constantFrames(50_000, (short) 10_000));
        writeWav(root.resolve("fading.wav"), constantFrames(50_000, (short) 10_000));
        writeWav(root.resolve("silent.wav"), constantFrames(50_000, (short) 0));

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, state(
                    "old",
                    false,
                    track("immediate.wav", 0, true, 0, 0),
                    track("fading.wav", 0, true, 0, 1000)
            ));
            assertEquals(20_000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(1L, state(
                    "new", false, track("silent.wav", 0, true, 0, 0)
            ));

            assertEquals(10_000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.renderFrames(22_049, 1.0f);
            assertEquals(5000, firstLeftSample(engine.renderFrames(1, 1.0f)), 2);
        }
    }

    @Test
    void loopsByReopeningTheStreamAtEndOfFile() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));
        engine.apply(1L, playing("loop", "loop.wav", 1.0f, true, 0, 0));

        byte[] rendered = engine.renderFrames(3, 1.0f);

        assertEquals(1000, leftSample(rendered, 0));
        assertEquals(2000, leftSample(rendered, 1));
        assertEquals(1000, leftSample(rendered, 2));
        engine.close();
    }

    @Test
    void completedNonLoopTrackStaysSilentWhenIdenticalStateIsReapplied() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{4321});
        MusicLibrary library = MusicLibrary.scan(root);
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), library);
        PlaybackState state = playing("once", "once.wav", 1.0f, false, 0, 0);

        engine.apply(1L, state);
        assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));

        engine.setMusicLibrary(library);
        engine.apply(1L, state);

        assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    @Test
    void completedNonLoopTrackRestartsWhenReloadChangesItToLooping() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{4321});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(1L, playing("once", "once.wav", 1.0f, false, 0, 0));
        assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));

        engine.apply(1L, playing("once", "once.wav", 1.0f, true, 0, 0));

        assertEquals(4321, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    @Test
    void fifthRapidAreaTransitionSoftFadesInsteadOfHardEvicting() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("track0.wav"), constantFrames(4096, (short) 10_000));
        for (int index = 1; index < 5; index++) {
            writeWav(root.resolve("track" + index + ".wav"), constantFrames(4096, (short) 0));
        }
        AtomicInteger[] closeCounts = new AtomicInteger[5];
        for (int index = 0; index < closeCounts.length; index++) {
            closeCounts[index] = new AtomicInteger();
        }
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                String fileName = path.getFileName().toString();
                int index = Integer.parseInt(fileName.substring(5, fileName.length() - 4));
                short sample = index == 0 ? (short) 10_000 : 0;
                return new CloseCountingAudioInputStream(
                        pcmFrames(constantFrames(4096, sample)), closeCounts[index]
                );
            }
        };
        PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root));

        try {
            for (int index = 0; index < 4; index++) {
                engine.apply(1L, playing(
                        "area" + index,
                        "track" + index + ".wav",
                        1.0f,
                        true,
                        0,
                        60_000
                ));
                engine.renderFrames(1, 1.0f);
            }

            engine.apply(1L, playing(
                    "area4", "track4.wav", 1.0f, true, 0, 60_000
            ));

            assertEquals(4, engine.liveSessionCount());
            assertEquals(5, engine.retainedSessionCount());
            assertEquals(0, closeCounts[0].get());
            assertTrue(firstLeftSample(engine.renderFrames(1, 1.0f)) > 9_000);
            assertEquals(0, closeCounts[0].get());
            engine.renderFrames(881, 1.0f);
            assertEquals(4, engine.retainedSessionCount());
            assertEquals(1, closeCounts[0].get());
            for (int index = 1; index < closeCounts.length; index++) {
                assertEquals(0, closeCounts[index].get());
            }

            engine.close();
            for (AtomicInteger closeCount : closeCounts) {
                assertEquals(1, closeCount.get());
            }
        } finally {
            engine.close();
        }
    }

    @Test
    void fifthRapidTransitionStartsAfterOneBoundedSoftFade() throws Exception {
        Path root = tempDir.resolve("music");
        for (int index = 0; index < 4; index++) {
            writeWav(root.resolve("track" + index + ".wav"), constantFrames(4096, (short) 0));
        }
        writeWav(root.resolve("track4.wav"), constantFrames(4096, (short) 5000));
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        for (int index = 0; index < 4; index++) {
            engine.apply(1L, playing(
                    "area" + index, "track" + index + ".wav", 1.0f, true, 0, 60_000
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(1L, playing("area4", "track4.wav", 1.0f, true, 0, 60_000));
        engine.renderFrames(1024, 1.0f);

        assertEquals(5000, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    @Test
    void rapidTransitionExpeditesTheQuietestEffectiveVolumeSession() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("audible.wav"), constantFrames(4096, (short) 10_000));
        for (int index = 1; index < 5; index++) {
            writeWav(root.resolve("silent" + index + ".wav"), constantFrames(4096, (short) 0));
        }
        PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root)
        );

        engine.apply(1L, state(
                "area0", false, track("audible.wav", 0, 1.0f, true, 0, 60_000)
        ));
        engine.renderFrames(1, 1.0f);
        engine.apply(1L, state(
                "area1", false, track("silent1.wav", 0, 0.0f, true, 0, 60_000)
        ));
        engine.renderFrames(1, 1.0f);
        for (int index = 2; index < 4; index++) {
            engine.apply(1L, state(
                    "area" + index,
                    false,
                    track("silent" + index + ".wav", 0, 1.0f, true, 0, 60_000)
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(1L, state(
                "area4", false, track("silent4.wav", 0, 1.0f, true, 0, 60_000)
        ));
        engine.renderFrames(882, 1.0f);

        assertTrue(firstLeftSample(engine.renderFrames(1, 1.0f)) > 9_000);
        engine.close();
    }

    @Test
    void rapidSharedContinuationsKeepLogicalAndRetainedSessionsBounded() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("shared.wav"), constantFrames(4096, (short) 100));
        for (int index = 0; index < 10; index++) {
            writeWav(
                    root.resolve("replacement" + index + ".wav"),
                    constantFrames(4096, (short) 0)
            );
        }
        AtomicInteger sharedOpenCount = new AtomicInteger();
        AtomicInteger sharedCloseCount = new AtomicInteger();
        AtomicInteger[] replacementCloseCounts = new AtomicInteger[10];
        for (int index = 0; index < replacementCloseCounts.length; index++) {
            replacementCloseCounts[index] = new AtomicInteger();
        }
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                String fileName = path.getFileName().toString();
                if (fileName.equals("shared.wav")) {
                    sharedOpenCount.incrementAndGet();
                    return new CloseCountingAudioInputStream(
                            pcmFrames(constantFrames(4096, (short) 100)), sharedCloseCount
                    );
                }
                int index = Integer.parseInt(
                        fileName.substring("replacement".length(), fileName.length() - 4)
                );
                return new CloseCountingAudioInputStream(
                        pcmFrames(constantFrames(4096, (short) 0)),
                        replacementCloseCounts[index]
                );
            }
        };

        PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root));
        try {
            for (int index = 0; index < 10; index++) {
                engine.apply(1L, state(
                        "area" + index,
                        false,
                        track("shared.wav", 0, true, 0, 60_000),
                        track("replacement" + index + ".wav", 0, true, 0, 60_000)
                ));
                assertEquals(
                        Math.min(index + 1, 4),
                        engine.liveSessionCount(),
                        "unexpected logical live sessions after transition " + index
                );
                assertEquals(100, firstLeftSample(engine.renderFrames(1, 1.0f)));
            }

            assertEquals(1, sharedOpenCount.get());
            assertEquals(0, sharedCloseCount.get());
            for (AtomicInteger closeCount : replacementCloseCounts) {
                assertEquals(0, closeCount.get());
            }
            assertTrue(engine.retainedSessionCount() > engine.liveSessionCount());
            engine.renderFrames(882, 1.0f);
            assertEquals(1, sharedOpenCount.get());
            assertEquals(0, sharedCloseCount.get());
            assertEquals(engine.liveSessionCount(), engine.retainedSessionCount());
            assertEquals(4, engine.retainedSessionCount());
            for (int index = 0; index < replacementCloseCounts.length; index++) {
                assertEquals(index < 6 ? 1 : 0, replacementCloseCounts[index].get());
            }

            engine.close();
            assertEquals(1, sharedCloseCount.get());
            for (AtomicInteger closeCount : replacementCloseCounts) {
                assertEquals(1, closeCount.get());
            }
        } finally {
            engine.close();
        }
    }

    @Test
    void temporaryZeroReadsDoNotInsertSilentFrames() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Files.write(root.resolve("scripted.wav"), new byte[0]);
        byte[] expected = new byte[2 * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        PcmMath.writeLittleEndian(expected, 0, 1234);
        PcmMath.writeLittleEndian(expected, 2, -2345);
        PcmMath.writeLittleEndian(expected, 4, 3456);
        PcmMath.writeLittleEndian(expected, 6, -4567);
        AudioInputStream stream = new ScriptedZeroReadAudioInputStream(expected, 3, false);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(1L, playing("scripted", "scripted.wav", 1.0f, false, 0, 0));

            assertArrayEquals(expected, engine.renderFrames(2, 1.0f));
        }
    }

    @Test
    void sixtyFourConsecutiveZeroReadsStillDeliverTheNextFrame() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Files.write(root.resolve("boundary.wav"), new byte[0]);
        byte[] expected = new byte[AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        PcmMath.writeLittleEndian(expected, 0, 1234);
        PcmMath.writeLittleEndian(expected, 2, -2345);
        ScriptedZeroReadAudioInputStream stream =
                new ScriptedZeroReadAudioInputStream(expected, 64, false);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(1L, playing("boundary", "boundary.wav", 1.0f, false, 0, 0));

            assertArrayEquals(expected, engine.renderFrames(1, 1.0f));
            assertEquals(64, stream.zeroReadsReturned);
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void sixtyFifthConsecutiveZeroReadQueuesTheDecodeFailure() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Files.write(root.resolve("boundary.wav"), new byte[0]);
        ScriptedZeroReadAudioInputStream stream =
                new ScriptedZeroReadAudioInputStream(new byte[0], 0, true);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(1L, playing("boundary", "boundary.wav", 1.0f, false, 0, 0));

            engine.renderFrames(1, 1.0f);

            assertEquals(65, stream.zeroReadsReturned);
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertFalse(engine.hasWork());
        }
    }

    @Test
    void persistentZeroReadsQueueOneDecodeFailure() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Files.write(root.resolve("stalled.wav"), new byte[0]);
        AudioInputStream stream = new ScriptedZeroReadAudioInputStream(new byte[0], 0, true);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(1L, playing("stalled", "stalled.wav", 1.0f, false, 0, 0));

            assertTimeoutPreemptively(
                    Duration.ofSeconds(2), () -> engine.renderFrames(2, 1.0f)
            );
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            PcmMixerEngine.AudioPlaybackException cause = assertInstanceOf(
                    PcmMixerEngine.AudioPlaybackException.class, failures.get(0).cause()
            );
            assertInstanceOf(IOException.class, cause.getCause());
            assertTrue(engine.drainFailures().isEmpty());
            assertFalse(engine.hasWork());
        }
    }

    @Test
    void oggTemporaryZeroReadsDoNotAddRenderedBlocks() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Path source = root.resolve("test.ogg");
        InputStream fixture = getClass().getResourceAsStream("/datura/areamusic/audio/test.ogg");
        assertNotNull(fixture);
        try (fixture) {
            Files.copy(fixture, source);
        }

        DecodedFrameCount decodeResult;
        try (AudioInputStream decoded = new AudioStreamFactory().open(source)) {
            decodeResult = countDecodedFrames(decoded);
        }
        assertTrue(decodeResult.frames() > 0);
        assertTrue(decodeResult.zeroReads() > 0, "test.ogg must exercise temporary zero-byte reads");
        long expectedBlocks = (decodeResult.frames() + 1023) / 1024;
        int renderedBlocks = 0;
        int renderGuard = Math.toIntExact(expectedBlocks + 64);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(1L, playing("ogg", "test.ogg", 1.0f, false, 0, 0));
            while (engine.hasWork() && renderedBlocks < renderGuard) {
                engine.renderFrames(1024, 1.0f);
                renderedBlocks++;
            }
            assertFalse(engine.hasWork(), "Mixer did not reach the end of test.ogg within the render guard");
        }

        assertEquals(expectedBlocks, renderedBlocks);
    }

    @Test
    void reentersMatchingResumableAreaByReclaimingItsLiveOutgoingSession() throws Exception {
        Path root = tempDir.resolve("music");
        short[] areaFrames = increasingFrames(1000, 2000);
        writeWav(root.resolve("area.wav"), areaFrames);
        writeWav(root.resolve("between.wav"), constantFrames(2000, (short) 0));
        AtomicInteger areaOpenCount = new AtomicInteger();
        AtomicInteger areaCloseCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("area.wav")) {
                    areaOpenCount.incrementAndGet();
                    return new CloseCountingAudioInputStream(
                            pcmFrames(areaFrames), areaCloseCount
                    );
                }
                return super.open(path);
            }
        };
        PlaybackState area = state(
                "area", true, track("area.wav", 0, true, 0, 10)
        );
        PlaybackState between = state(
                "between", false, track("between.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, area);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, between);
            assertEquals(1001, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(7L, area);

            assertEquals(1002, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, areaOpenCount.get());
            assertEquals(0, areaCloseCount.get());

            engine.apply(7L, PlaybackState.stopped());
            engine.renderFrames(441, 1.0f);
            assertEquals(1, areaCloseCount.get());
            engine.apply(7L, area);
            assertEquals(1444, firstNonSilentSample(engine));
            assertEquals(2, areaOpenCount.get());
        }
    }

    @Test
    void outgoingResumableSessionRequiresCompletePlaybackStateMatch() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("area.wav"), constantFrames(1000, (short) 1000));
        writeWav(root.resolve("between.wav"), constantFrames(1000, (short) 0));
        AtomicInteger areaOpenCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("area.wav")) {
                    areaOpenCount.incrementAndGet();
                }
                return super.open(path);
            }
        };
        PlaybackState original = state(
                "area", true, track("area.wav", 0, 1.0f, true, 0, 100)
        );
        PlaybackState changed = state(
                "area", true, track("area.wav", 0, 0.5f, true, 0, 100)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, original);
            engine.renderFrames(1, 1.0f);
            engine.apply(7L, state(
                    "between", false, track("between.wav", 0, true, 0, 0)
            ));
            engine.renderFrames(1, 1.0f);

            engine.apply(7L, changed);

            assertEquals(2, areaOpenCount.get());
        }
    }

    @Test
    void reclaimedResumableSessionKeepsPendingDelayFrozen() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("carrier.wav"), constantFrames(100_000, (short) 0));
        writeWav(root.resolve("delayed.wav"), new short[]{1234});
        writeWav(root.resolve("between.wav"), constantFrames(100_000, (short) 0));
        AtomicInteger carrierOpenCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("carrier.wav")) {
                    carrierOpenCount.incrementAndGet();
                }
                return super.open(path);
            }
        };
        PlaybackState area = state(
                "area",
                true,
                track("carrier.wav", 0, true, 0, 60_000),
                track("delayed.wav", 2, false, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, area);
            assertAllSilent(engine.renderFrames(44_100, 1.0f));
            engine.apply(7L, state(
                    "between", false, track("between.wav", 0, true, 0, 0)
            ));
            assertAllSilent(engine.renderFrames(20_000, 1.0f));

            engine.apply(7L, area);

            assertEquals(1, carrierOpenCount.get());
            assertAllSilent(engine.renderFrames(44_099, 1.0f));
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1234, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void reclaimedSessionRestoresOnlyStartedTracksRemovedAtFadeEndpoints() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("short.wav"), increasingFrames(1000, 2000));
        writeWav(root.resolve("long.wav"), constantFrames(2000, (short) 0));
        writeWav(root.resolve("once.wav"), new short[]{0});
        writeWav(root.resolve("between.wav"), constantFrames(2000, (short) 0));
        AtomicInteger shortOpenCount = new AtomicInteger();
        AtomicInteger longOpenCount = new AtomicInteger();
        AtomicInteger onceOpenCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                switch (path.getFileName().toString()) {
                    case "short.wav" -> shortOpenCount.incrementAndGet();
                    case "long.wav" -> longOpenCount.incrementAndGet();
                    case "once.wav" -> onceOpenCount.incrementAndGet();
                    default -> {
                    }
                }
                return super.open(path);
            }
        };
        PlaybackState area = state(
                "area",
                true,
                track("short.wav", 0, true, 0, 1),
                track("long.wav", 0, true, 0, 100),
                track("once.wav", 0, false, 0, 1)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, area);
            engine.renderFrames(2, 1.0f);
            engine.apply(7L, state(
                    "between", false, track("between.wav", 0, true, 0, 0)
            ));
            engine.renderFrames(44, 1.0f);

            engine.apply(7L, area);

            assertEquals(1046, firstNonSilentSample(engine));
            assertEquals(2, shortOpenCount.get());
            assertEquals(1, longOpenCount.get());
            assertEquals(1, onceOpenCount.get());
        }
    }

    @Test
    void ordinaryLeaveReportsACompletedReclaimPreparationFailureExactlyOnce() throws Exception {
        Path root = tempDir.resolve("music");
        Path sentinelPath = tempDir.resolve("preparation-sentinel");
        writeWav(root.resolve("short.wav"), increasingFrames(1000, 2000));
        writeWav(root.resolve("long.wav"), increasingFrames(2000, 2000));
        writeWav(root.resolve("between.wav"), constantFrames(2000, (short) 0));
        AtomicInteger shortOpenCount = new AtomicInteger();
        AtomicInteger longOpenCount = new AtomicInteger();
        AtomicInteger sentinelOpenCount = new AtomicInteger();
        CountDownLatch firstSentinelOpen = new CountDownLatch(1);
        CountDownLatch secondSentinelOpen = new CountDownLatch(1);
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.equals(sentinelPath)) {
                    if (sentinelOpenCount.incrementAndGet() == 1) {
                        firstSentinelOpen.countDown();
                    } else {
                        secondSentinelOpen.countDown();
                    }
                    return new CloseCountingAudioInputStream(
                            pcmFrames((short) 0), new AtomicInteger()
                    );
                }
                if (path.getFileName().toString().equals("short.wav")) {
                    if (shortOpenCount.incrementAndGet() == 2) {
                        throw new IOException("reclaim restore failed");
                    }
                } else if (path.getFileName().toString().equals("long.wav")) {
                    longOpenCount.incrementAndGet();
                }
                return delegate.open(path);
            }
        };
        PlaybackState area = state(
                "area",
                true,
                track("short.wav", 0, true, 0, 1),
                track("long.wav", 0, true, 0, 100)
        );
        PlaybackState between = state(
                "between", false, track("between.wav", 0, true, 0, 0)
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(root), preparer
             )) {
            engine.apply(7L, area);
            engine.renderFrames(2, 1.0f);
            engine.apply(7L, between);
            engine.renderFrames(44, 1.0f);

            engine.apply(7L, area);
            CompletableFuture<AudioInputStream> firstSentinel = preparer.prepare(
                    sentinelPath, 0L
            );
            assertTrue(firstSentinelOpen.await(1, TimeUnit.SECONDS));
            try (AudioInputStream ignored = firstSentinel.get(1, TimeUnit.SECONDS)) {
            }

            engine.apply(7L, between);
            engine.apply(7L, area);
            CompletableFuture<AudioInputStream> secondSentinel = preparer.prepare(
                    sentinelPath, 0L
            );
            assertTrue(secondSentinelOpen.await(1, TimeUnit.SECONDS));
            try (AudioInputStream ignored = secondSentinel.get(1, TimeUnit.SECONDS)) {
            }

            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertEquals("short.wav", failures.get(0).musicId());
            assertEquals(2, shortOpenCount.get());
            assertEquals(1, longOpenCount.get());
            assertEquals(2046, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void resumeDisabledRestartsTheTrackFromItsFirstFrame() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("ramp.wav"), new short[]{1000, 2000, 3000});
        PlaybackState state = state(
                "area", false, track("ramp.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertEquals(1000, firstNonSilentSample(engine));
        }
    }

    @Test
    void resumeEnabledContinuesAtTheNextFrameAfterPreparation() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("ramp.wav"), new short[]{1000, 2000, 3000});
        PlaybackState state = state(
                "area", true, track("ramp.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertEquals(2000, firstNonSilentSample(engine));
        }
    }

    @Test
    void resumeKeepsTheRemainingPendingDelayFrozenWhileOutsideTheArea() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("delayed.wav"), new short[]{1234});
        PlaybackState state = state(
                "area", true, track("delayed.wav", 2, false, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertAllSilent(engine.renderFrames(44_100, 1.0f));
            engine.apply(7L, PlaybackState.stopped());
            engine.renderFrames(20_000, 1.0f);
            engine.apply(7L, state);
            assertAllSilent(engine.renderFrames(44_099, 1.0f));
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1234, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void resumeKeepsACompletedNonLoopTrackSilentWithoutReopeningIt() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{1000});
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };
        PlaybackState state = state(
                "area", true, track("once.wav", 0, false, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            byte[] completed = engine.renderFrames(2, 1.0f);
            assertEquals(1000, leftSample(completed, 0));
            assertEquals(0, leftSample(completed, 1));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertAllSilent(engine.renderFrames(4, 1.0f));
            assertEquals(1, openCount.get());
        }
    }

    @Test
    void resumeKeepsALoopAtItsCursorWithinTheCurrentPass() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        PlaybackState state = state(
                "area", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertEquals(2000, firstNonSilentSample(engine));
        }
    }

    @Test
    void resumePreservesAFailedTrackWhileRestoringAnotherTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{500});
        writeWav(root.resolve("good.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger badOpenCount = new AtomicInteger();
        AtomicInteger goodOpenCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("bad.wav")) {
                    badOpenCount.incrementAndGet();
                    throw new IOException("decode failed");
                }
                goodOpenCount.incrementAndGet();
                return super.open(path);
            }
        };
        PlaybackState state = state(
                "area", true,
                track("bad.wav", 0, true, 0, 0),
                track("good.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertSingleDecodeFailure(engine, "bad.wav");
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertEquals(2000, firstNonSilentSample(engine));
            assertTrue(engine.drainFailures().isEmpty());
            assertEquals(1, badOpenCount.get());
            assertEquals(2, goodOpenCount.get());
        }
    }

    @Test
    void successfulLibraryUpdateAndNewRevisionDiscardOldResumeSnapshots() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        MusicLibrary library = MusicLibrary.scan(root);
        PlaybackState state = state(
                "area", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), library)) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.setMusicLibrary(library);
            engine.apply(7L, state);
            assertEquals(1000, firstNonSilentSample(engine));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(8L, state);
            assertEquals(1000, firstNonSilentSample(engine));
        }
    }

    @Test
    void successfulLibraryUpdateInvalidatesTheStillActiveSessionBeforeItCanSnapshot() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        MusicLibrary library = MusicLibrary.scan(root);
        PlaybackState state = state(
                "area", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), library)) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.setMusicLibrary(library);
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);

            assertEquals(1000, firstNonSilentSample(engine));
        }
    }

    @Test
    void libraryUpdateCancelsPendingRestoreWithoutHardClosingHealthyRuntime()
            throws Exception {
        Path oldRoot = tempDir.resolve("old-music-pending");
        Path newRoot = tempDir.resolve("new-music-pending");
        Path sentinelPath = tempDir.resolve("pending-refresh-sentinel");
        writeWav(oldRoot.resolve("restore.wav"), constantFrames(10_000, (short) 100));
        writeWav(oldRoot.resolve("healthy.wav"), constantFrames(10_000, (short) 1000));
        writeWav(oldRoot.resolve("between.wav"), constantFrames(10_000, (short) 0));
        writeWav(newRoot.resolve("restore.wav"), constantFrames(10_000, (short) 4000));
        writeWav(newRoot.resolve("healthy.wav"), constantFrames(10_000, (short) 8000));
        Path normalizedOldRoot = oldRoot.toAbsolutePath().normalize();
        Path normalizedNewRoot = newRoot.toAbsolutePath().normalize();
        CountDownLatch pendingRestoreEntered = new CountDownLatch(1);
        CountDownLatch releasePendingRestore = new CountDownLatch(1);
        CountDownLatch lateRestoreClosed = new CountDownLatch(1);
        AtomicInteger oldRestoreOpenCount = new AtomicInteger();
        AtomicInteger oldRestoreCloseCount = new AtomicInteger();
        AtomicInteger oldHealthyCloseCount = new AtomicInteger();
        AtomicInteger lateRestoreCloseCount = new AtomicInteger();
        AtomicInteger newOpenCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.equals(sentinelPath)) {
                    return new CloseCountingAudioInputStream(
                            pcmFrames((short) 0), new AtomicInteger()
                    );
                }
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.startsWith(normalizedOldRoot)) {
                    if (path.getFileName().toString().equals("restore.wav")) {
                        if (oldRestoreOpenCount.incrementAndGet() == 1) {
                            return new CloseCountingAudioInputStream(
                                    pcmFrames(constantFrames(10_000, (short) 100)),
                                    oldRestoreCloseCount
                            );
                        }
                        pendingRestoreEntered.countDown();
                        awaitLatchIgnoringInterrupt(releasePendingRestore);
                        return new CloseSignallingAudioInputStream(
                                pcmFrames(constantFrames(10_000, (short) 100)),
                                lateRestoreCloseCount,
                                lateRestoreClosed
                        );
                    }
                    if (path.getFileName().toString().equals("healthy.wav")) {
                        return new CloseCountingAudioInputStream(
                                pcmFrames(constantFrames(10_000, (short) 1000)),
                                oldHealthyCloseCount
                        );
                    }
                    return delegate.open(path);
                }
                if (normalized.startsWith(normalizedNewRoot)) {
                    newOpenCount.incrementAndGet();
                    short sample = path.getFileName().toString().equals("restore.wav")
                            ? (short) 4000
                            : (short) 8000;
                    return new CloseCountingAudioInputStream(
                            pcmFrames(constantFrames(10_000, sample)),
                            new AtomicInteger()
                    );
                }
                return delegate.open(path);
            }
        };
        PlaybackState area = state(
                "area",
                true,
                track("restore.wav", 0, true, 0, 1),
                track("healthy.wav", 0, true, 0, 100)
        );
        PlaybackState between = state(
                "between", false, track("between.wav", 0, true, 0, 0)
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(oldRoot), preparer
             )) {
            engine.apply(7L, area);
            assertEquals(1100, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, between);
            engine.renderFrames(44, 1.0f);
            assertEquals(1, oldRestoreCloseCount.get());
            assertEquals(0, oldHealthyCloseCount.get());

            engine.apply(7L, area);
            assertTrue(pendingRestoreEntered.await(1, TimeUnit.SECONDS));
            assertEquals(2, oldRestoreOpenCount.get());

            engine.setMusicLibrary(MusicLibrary.scan(newRoot));
            assertEquals(0, oldHealthyCloseCount.get());
            assertTrue(engine.hasWork());
            engine.apply(7L, area);

            assertEquals(0, oldHealthyCloseCount.get());
            assertEquals(0, newOpenCount.get());
            CompletableFuture<AudioInputStream> sentinel = preparer.prepare(sentinelPath, 0L);
            releasePendingRestore.countDown();
            assertTrue(lateRestoreClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1, lateRestoreCloseCount.get());
            try (AudioInputStream ignored = sentinel.get(1, TimeUnit.SECONDS)) {
            }
            assertEquals(13000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(2, newOpenCount.get());
            assertTrue(engine.drainFailures().isEmpty());

            engine.renderFrames(4409, 1.0f);
            assertEquals(1, oldHealthyCloseCount.get());
        } finally {
            releasePendingRestore.countDown();
        }
    }

    @Test
    void libraryRefreshKeepsCompletedTrackSilentWhileReplacingBlockedRestore()
            throws Exception {
        Path oldRoot = tempDir.resolve("old-completed-refresh");
        Path newRoot = tempDir.resolve("new-completed-refresh");
        writeWav(oldRoot.resolve("once.wav"), new short[]{4000});
        writeWav(oldRoot.resolve("loop.wav"), new short[]{1000, 2000, 3000, 4000});
        writeWav(newRoot.resolve("once.wav"), new short[]{6000});
        writeWav(newRoot.resolve("loop.wav"), new short[]{7000, 8000, 9000, 10000});
        Path normalizedOldRoot = oldRoot.toAbsolutePath().normalize();
        Path normalizedNewRoot = newRoot.toAbsolutePath().normalize();
        CountDownLatch blockedRestoreEntered = new CountDownLatch(1);
        CountDownLatch releaseBlockedRestore = new CountDownLatch(1);
        CountDownLatch lateRestoreClosed = new CountDownLatch(1);
        AtomicInteger oldLoopOpenCount = new AtomicInteger();
        AtomicInteger lateRestoreCloseCount = new AtomicInteger();
        AtomicInteger newOnceOpenCount = new AtomicInteger();
        AtomicInteger newLoopOpenCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.startsWith(normalizedOldRoot)
                        && path.getFileName().toString().equals("loop.wav")) {
                    if (oldLoopOpenCount.incrementAndGet() == 1) {
                        return delegate.open(path);
                    }
                    blockedRestoreEntered.countDown();
                    awaitLatchIgnoringInterrupt(releaseBlockedRestore);
                    return new CloseSignallingAudioInputStream(
                            pcmFrames((short) 1000, (short) 2000, (short) 3000, (short) 4000),
                            lateRestoreCloseCount,
                            lateRestoreClosed
                    );
                }
                if (normalized.startsWith(normalizedNewRoot)) {
                    if (path.getFileName().toString().equals("once.wav")) {
                        newOnceOpenCount.incrementAndGet();
                        return new CloseCountingAudioInputStream(
                                pcmFrames((short) 6000), new AtomicInteger()
                        );
                    }
                    newLoopOpenCount.incrementAndGet();
                    return new CloseCountingAudioInputStream(
                            pcmFrames(
                                    (short) 7000,
                                    (short) 8000,
                                    (short) 9000,
                                    (short) 10000
                            ),
                            new AtomicInteger()
                    );
                }
                return delegate.open(path);
            }
        };
        PlaybackState area = state(
                "area",
                true,
                track("once.wav", 0, false, 0, 0),
                track("loop.wav", 0, true, 0, 0)
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(oldRoot), preparer
             )) {
            engine.apply(7L, area);
            engine.renderFrames(2, 1.0f);
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, area);
            assertTrue(blockedRestoreEntered.await(1, TimeUnit.SECONDS));

            engine.setMusicLibrary(MusicLibrary.scan(newRoot));
            engine.apply(7L, area);

            assertEquals(0, newOnceOpenCount.get());
            releaseBlockedRestore.countDown();
            assertTrue(lateRestoreClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1, lateRestoreCloseCount.get());
            assertEquals(9000, firstNonSilentSample(engine));
            assertEquals(0, newOnceOpenCount.get());
            assertEquals(1, newLoopOpenCount.get());
            assertTrue(engine.drainFailures().isEmpty());
        } finally {
            releaseBlockedRestore.countDown();
        }
    }

    @Test
    void libraryRefreshPreservesRemainingDelayWhileReplacingBlockedRestore()
            throws Exception {
        Path oldRoot = tempDir.resolve("old-delay-refresh");
        Path newRoot = tempDir.resolve("new-delay-refresh");
        Path sentinelPath = tempDir.resolve("delay-refresh-sentinel");
        writeWav(oldRoot.resolve("carrier.wav"), constantFrames(100_000, (short) 0));
        writeWav(oldRoot.resolve("delayed.wav"), new short[]{1234});
        writeWav(newRoot.resolve("carrier.wav"), constantFrames(100_000, (short) 0));
        writeWav(newRoot.resolve("delayed.wav"), new short[]{1234});
        Path normalizedOldRoot = oldRoot.toAbsolutePath().normalize();
        Path normalizedNewRoot = newRoot.toAbsolutePath().normalize();
        CountDownLatch blockedRestoreEntered = new CountDownLatch(1);
        CountDownLatch releaseBlockedRestore = new CountDownLatch(1);
        CountDownLatch lateRestoreClosed = new CountDownLatch(1);
        AtomicInteger oldCarrierOpenCount = new AtomicInteger();
        AtomicInteger lateRestoreCloseCount = new AtomicInteger();
        AtomicInteger newDelayedOpenCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.equals(sentinelPath)) {
                    return new CloseCountingAudioInputStream(
                            pcmFrames((short) 0), new AtomicInteger()
                    );
                }
                Path normalized = path.toAbsolutePath().normalize();
                if (normalized.startsWith(normalizedOldRoot)
                        && path.getFileName().toString().equals("carrier.wav")) {
                    if (oldCarrierOpenCount.incrementAndGet() == 1) {
                        return delegate.open(path);
                    }
                    blockedRestoreEntered.countDown();
                    awaitLatchIgnoringInterrupt(releaseBlockedRestore);
                    return new CloseSignallingAudioInputStream(
                            pcmFrames(constantFrames(100_000, (short) 0)),
                            lateRestoreCloseCount,
                            lateRestoreClosed
                    );
                }
                if (normalized.startsWith(normalizedNewRoot)
                        && path.getFileName().toString().equals("delayed.wav")) {
                    newDelayedOpenCount.incrementAndGet();
                }
                return delegate.open(path);
            }
        };
        PlaybackState area = state(
                "area",
                true,
                track("carrier.wav", 0, true, 0, 0),
                track("delayed.wav", 2, false, 0, 0)
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(oldRoot), preparer
             )) {
            engine.apply(7L, area);
            assertAllSilent(engine.renderFrames(44_100, 1.0f));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, area);
            assertTrue(blockedRestoreEntered.await(1, TimeUnit.SECONDS));

            engine.setMusicLibrary(MusicLibrary.scan(newRoot));
            engine.apply(7L, area);
            CompletableFuture<AudioInputStream> sentinel = preparer.prepare(sentinelPath, 0L);
            releaseBlockedRestore.countDown();
            assertTrue(lateRestoreClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1, lateRestoreCloseCount.get());
            try (AudioInputStream ignored = sentinel.get(1, TimeUnit.SECONDS)) {
            }

            assertAllSilent(engine.renderFrames(44_099, 1.0f));
            assertEquals(0, newDelayedOpenCount.get());
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, newDelayedOpenCount.get());
            assertEquals(1234, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.drainFailures().isEmpty());
        } finally {
            releaseBlockedRestore.countDown();
        }
    }

    @Test
    void successfulLibraryUpdateDoesNotTransferAnActiveRuntimeIntoTheNewRevision()
            throws Exception {
        Path oldRoot = tempDir.resolve("old-music");
        Path newRoot = tempDir.resolve("new-music");
        writeWav(oldRoot.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        writeWav(newRoot.resolve("loop.wav"), new short[]{7000, 8000, 9000});
        AtomicInteger oldOpenCount = new AtomicInteger();
        AtomicInteger newOpenCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.startsWith(oldRoot.toAbsolutePath().normalize())) {
                    oldOpenCount.incrementAndGet();
                }
                if (path.startsWith(newRoot.toAbsolutePath().normalize())) {
                    newOpenCount.incrementAndGet();
                }
                return super.open(path);
            }
        };
        MusicLibrary newLibrary = MusicLibrary.scan(newRoot);
        PlaybackState state = state(
                "area", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                factory, MusicLibrary.scan(oldRoot))) {
            engine.apply(6L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.setMusicLibrary(newLibrary);
            engine.apply(7L, state);
            assertEquals(8000, firstNonSilentSample(engine));

            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);

            assertEquals(9000, firstNonSilentSample(engine));
            assertEquals(1, oldOpenCount.get());
            assertEquals(2, newOpenCount.get());
        }
    }

    @Test
    void revisionChangeTransfersCompatibleLiveStreamWithoutReusingOldSnapshot() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                openCount.incrementAndGet();
                return super.open(path);
            }
        };
        PlaybackState state = state(
                "area", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(6L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(7L, state);
            assertEquals(2000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, openCount.get());

            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);

            assertEquals(3000, firstNonSilentSample(engine));
            assertEquals(2, openCount.get());
        }
    }

    @Test
    void revisionOnlyUpdatePreservesCompletedPendingFailedAndCursorStates()
            throws Exception {
        Path root = tempDir.resolve("revision-timeline");
        short[] cursorFrames = new short[100_000];
        cursorFrames[0] = 1000;
        cursorFrames[1] = 1001;
        cursorFrames[2] = 1002;
        writeWav(root.resolve("completed.wav"), new short[]{4000});
        writeWav(root.resolve("delayed.wav"), new short[]{1234});
        writeWav(root.resolve("failed.wav"), new short[]{500});
        writeWav(root.resolve("cursor.wav"), cursorFrames);
        AtomicInteger completedOpenCount = new AtomicInteger();
        AtomicInteger delayedOpenCount = new AtomicInteger();
        AtomicInteger failedOpenCount = new AtomicInteger();
        AtomicInteger cursorOpenCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                switch (path.getFileName().toString()) {
                    case "completed.wav" -> completedOpenCount.incrementAndGet();
                    case "delayed.wav" -> delayedOpenCount.incrementAndGet();
                    case "failed.wav" -> {
                        failedOpenCount.incrementAndGet();
                        throw new IOException("expected decode failure");
                    }
                    case "cursor.wav" -> cursorOpenCount.incrementAndGet();
                    default -> {
                    }
                }
                return delegate.open(path);
            }
        };
        PlaybackState state = state(
                "area",
                true,
                track("completed.wav", 0, false, 0, 0),
                track("delayed.wav", 2, false, 0, 0),
                track("failed.wav", 0, false, 0, 0),
                track("cursor.wav", 0, false, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            byte[] initial = engine.renderFrames(2, 1.0f);
            assertEquals(5000, leftSample(initial, 0));
            assertEquals(1001, leftSample(initial, 1));
            assertSingleDecodeFailure(engine, "failed.wav");

            engine.apply(8L, state);

            assertEquals(1002, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, completedOpenCount.get());
            assertEquals(0, delayedOpenCount.get());
            assertEquals(1, failedOpenCount.get());
            assertEquals(1, cursorOpenCount.get());
            assertTrue(engine.drainFailures().isEmpty());

            engine.renderFrames(88_196, 1.0f);
            assertEquals(0, delayedOpenCount.get());
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertEquals(1, delayedOpenCount.get());
            assertEquals(1234, firstLeftSample(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void failedReloadPathWithoutLibraryUpdateKeepsTheResumeSnapshot() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        PlaybackState state = state(
                "area", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(9L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(9L, PlaybackState.stopped());
            engine.apply(9L, state);
            assertEquals(2000, firstNonSilentSample(engine));
        }
    }

    @Test
    void outgoingTracksStopAdvancingAtTheirIndividualFadeEndpoints() throws Exception {
        Path root = tempDir.resolve("music");
        Path sentinelPath = tempDir.resolve("restore-sentinel");
        writeWav(root.resolve("short.wav"), increasingFrames(1000, 256));
        writeWav(root.resolve("long.wav"), increasingFrames(2000, 256));
        CountDownLatch releaseRestoreOpens = new CountDownLatch(1);
        CountDownLatch sentinelOpenEntered = new CountDownLatch(1);
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger sentinelCloseCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.equals(sentinelPath)) {
                    sentinelOpenEntered.countDown();
                    return new CloseCountingAudioInputStream(
                            pcmFrames((short) 0), sentinelCloseCount
                    );
                }
                if (openCount.incrementAndGet() > 2) {
                    awaitLatch(releaseRestoreOpens);
                }
                return delegate.open(path);
            }
        };
        PlaybackState state = state(
                "area", true,
                track("short.wav", 0, true, 0, 1),
                track("long.wav", 0, true, 0, 2)
        );

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(root), preparer
             )) {
            engine.apply(7L, state);
            assertEquals(3000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.renderFrames(200, 1.0f);
            engine.apply(7L, state);
            CompletableFuture<AudioInputStream> sentinel = preparer.prepare(sentinelPath, 0L);
            releaseRestoreOpens.countDown();
            assertTrue(sentinelOpenEntered.await(1, TimeUnit.SECONDS));
            try (AudioInputStream ignored = sentinel.get(1, TimeUnit.SECONDS)) {
            }
            assertEquals(4, openCount.get());
            assertEquals(1, sentinelCloseCount.get());
            engine.renderFrames(0, 1.0f);
            assertEquals(3134, firstLeftSample(engine.renderFrames(1, 1.0f)));
        } finally {
            releaseRestoreOpens.countDown();
        }
    }

    @Test
    void resumeSnapshotRequiresTheCompletePlaybackStateToMatch() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        PlaybackState original = state(
                "area", true, track("loop.wav", 0, 1.0f, true, 0, 0)
        );
        PlaybackState changedDefinition = state(
                "area", true, track("loop.wav", 0, 0.5f, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(7L, original);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, changedDefinition);
            assertEquals(500, firstNonSilentSample(engine));
        }
    }

    @Test
    void resumeSnapshotsAreScopedIndependentlyByAreaId() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        PlaybackState firstArea = state(
                "first", true, track("loop.wav", 0, true, 0, 0)
        );
        PlaybackState secondArea = state(
                "second", true, track("loop.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(7L, firstArea);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());

            engine.apply(7L, secondArea);
            assertEquals(1000, firstNonSilentSample(engine));
            engine.apply(7L, PlaybackState.stopped());

            engine.apply(7L, firstArea);
            assertEquals(2000, firstNonSilentSample(engine));
        }
    }

    @Test
    void restoredCursorDoesNotAdvanceWhileAsynchronousPreparationIsBlocked() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        CountDownLatch restoreOpenEntered = new CountDownLatch(1);
        CountDownLatch releaseRestoreOpen = new CountDownLatch(1);
        AtomicInteger openCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (openCount.incrementAndGet() == 2) {
                    restoreOpenEntered.countDown();
                    awaitLatch(releaseRestoreOpen);
                }
                return delegate.open(path);
            }
        };
        PlaybackState state = state("area", true, track("loop.wav", 0, true, 0, 0));

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(root), preparer
             )) {
            engine.apply(7L, state);
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertTrue(restoreOpenEntered.await(1, TimeUnit.SECONDS));
            assertTrue(engine.hasWork());
            assertAllSilent(engine.renderFrames(100, 1.0f));
            releaseRestoreOpen.countDown();
            assertEquals(2000, firstNonSilentSample(engine));
        } finally {
            releaseRestoreOpen.countDown();
        }
    }

    @Test
    void leavingBeforeACompletedRestoreAttachesClosesItsStreamExactlyOnce() throws Exception {
        Path root = tempDir.resolve("music");
        Path sentinelPath = tempDir.resolve("restore-sentinel");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        CountDownLatch releaseRestoreOpen = new CountDownLatch(1);
        CountDownLatch sentinelOpenEntered = new CountDownLatch(1);
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger restoredCloseCount = new AtomicInteger();
        AtomicInteger sentinelCloseCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.equals(sentinelPath)) {
                    sentinelOpenEntered.countDown();
                    return new CloseCountingAudioInputStream(
                            pcmFrames((short) 0), sentinelCloseCount
                    );
                }
                if (openCount.incrementAndGet() == 1) {
                    return delegate.open(path);
                }
                awaitLatch(releaseRestoreOpen);
                return new CloseCountingAudioInputStream(
                        pcmFrames((short) 1000, (short) 2000, (short) 3000),
                        restoredCloseCount
                );
            }
        };
        PlaybackState state = state("area", true, track("loop.wav", 0, true, 0, 0));

        try (AudioStreamPreparer preparer = new AudioStreamPreparer(factory, 1);
             PcmMixerEngine engine = new PcmMixerEngine(
                     factory, MusicLibrary.scan(root), preparer
             )) {
            engine.apply(7L, state);
            engine.renderFrames(1, 1.0f);
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            CompletableFuture<AudioInputStream> sentinel = preparer.prepare(sentinelPath, 0L);
            releaseRestoreOpen.countDown();
            assertTrue(sentinelOpenEntered.await(1, TimeUnit.SECONDS));
            try (AudioInputStream ignored = sentinel.get(1, TimeUnit.SECONDS)) {
            }
            assertEquals(2, openCount.get());
            assertEquals(1, sentinelCloseCount.get());
            engine.apply(7L, PlaybackState.stopped());
            assertEquals(1, restoredCloseCount.get());
        } finally {
            releaseRestoreOpen.countDown();
        }
    }

    @Test
    void closedEngineRejectsApplyWithoutReopeningOrLeakingAStream() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AtomicReference<AudioInputStream> lateStream = new AtomicReference<>();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                AudioInputStream stream = new CloseCountingAudioInputStream(
                        pcmFrames((short) 1000, (short) 2000, (short) 3000),
                        closeCount
                );
                if (openCount.incrementAndGet() > 1) {
                    lateStream.set(stream);
                }
                return stream;
            }
        };
        PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root));
        PlaybackState state = state("area", false, track("loop.wav", 0, true, 0, 0));

        engine.apply(1L, state);
        engine.close();

        try {
            IllegalStateException failure = null;
            try {
                engine.apply(2L, state);
            } catch (IllegalStateException exception) {
                failure = exception;
            }
            engine.close();

            assertEquals(1, openCount.get());
            assertEquals(1, closeCount.get());
            assertNotNull(failure);
        } finally {
            engine.close();
            AudioInputStream leaked = lateStream.getAndSet(null);
            if (leaked != null) {
                leaked.close();
            }
        }
    }

    @Test
    void closedEngineRejectsMusicLibraryUpdates() {
        MusicLibrary library = MusicLibrary.empty(tempDir.resolve("music"));
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), library);
        engine.close();

        try {
            assertThrows(IllegalStateException.class, () -> engine.setMusicLibrary(library));
        } finally {
            engine.close();
        }
    }

    @Test
    void closedEngineRejectsRendering() {
        PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.empty(tempDir.resolve("music"))
        );
        engine.close();

        try {
            assertThrows(IllegalStateException.class, () -> engine.renderFrames(0, 1.0f));
        } finally {
            engine.close();
        }
    }

    @Test
    void engineClosesOnlyThePreparerItOwns() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        MusicLibrary library = MusicLibrary.scan(root);
        AtomicInteger externalOpenCount = new AtomicInteger();
        AudioStreamFactory externalFactory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                externalOpenCount.incrementAndGet();
                return new CloseCountingAudioInputStream(
                        pcmFrames((short) 1000), new AtomicInteger()
                );
            }
        };
        AudioStreamPreparer external = new AudioStreamPreparer(externalFactory, 1);
        PcmMixerEngine injectedEngine = new PcmMixerEngine(externalFactory, library, external);

        injectedEngine.close();
        try (AudioInputStream ignored = external.prepare(
                root.resolve("loop.wav"), 0
        ).get(1, TimeUnit.SECONDS)) {
            assertEquals(1, externalOpenCount.get());
        } finally {
            external.close();
        }

        CountDownLatch ownedRestoreOpen = new CountDownLatch(1);
        CountDownLatch releaseOwnedRestore = new CountDownLatch(1);
        CountDownLatch lateStreamClosed = new CountDownLatch(1);
        AtomicInteger ownedOpenCount = new AtomicInteger();
        AtomicInteger lateCloseCount = new AtomicInteger();
        AtomicReference<Thread> ownedWorker = new AtomicReference<>();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory ownedFactory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (ownedOpenCount.incrementAndGet() == 1) {
                    return delegate.open(path);
                }
                ownedWorker.set(Thread.currentThread());
                ownedRestoreOpen.countDown();
                awaitLatchIgnoringInterrupt(releaseOwnedRestore);
                return new CloseSignallingAudioInputStream(
                        pcmFrames((short) 1000, (short) 2000),
                        lateCloseCount,
                        lateStreamClosed
                );
            }
        };
        PcmMixerEngine ownedEngine = new PcmMixerEngine(ownedFactory, library);
        PlaybackState state = state("area", true, track("loop.wav", 0, true, 0, 0));

        try {
            ownedEngine.apply(7L, state);
            ownedEngine.renderFrames(1, 1.0f);
            ownedEngine.apply(7L, PlaybackState.stopped());
            ownedEngine.apply(7L, state);
            assertTrue(ownedRestoreOpen.await(1, TimeUnit.SECONDS));
            ownedEngine.close();
            releaseOwnedRestore.countDown();
            assertTrue(lateStreamClosed.await(1, TimeUnit.SECONDS));
            assertEquals(1, lateCloseCount.get());
            assertTrue(awaitCondition(
                    () -> !ownedWorker.get().isAlive(), Duration.ofSeconds(2)
            ));
        } finally {
            releaseOwnedRestore.countDown();
            ownedEngine.close();
        }
    }

    @Test
    void restoredPreparationFailureIsRememberedWhileAnotherTrackKeepsResuming()
            throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{500, 600, 700});
        writeWav(root.resolve("good.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger badOpenCount = new AtomicInteger();
        AtomicInteger goodOpenCount = new AtomicInteger();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("bad.wav")) {
                    if (badOpenCount.incrementAndGet() > 1) {
                        throw new IOException("restore failed");
                    }
                } else {
                    goodOpenCount.incrementAndGet();
                }
                return delegate.open(path);
            }
        };
        PlaybackState state = state(
                "area", true,
                track("bad.wav", 0, true, 0, 0),
                track("good.wav", 0, true, 0, 0)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(1500, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertEquals(2000, firstNonSilentSample(engine));
            assertSingleDecodeFailure(engine, "bad.wav");
            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);
            assertEquals(3000, firstNonSilentSample(engine));
            assertTrue(engine.drainFailures().isEmpty());
            assertEquals(2, badOpenCount.get());
            assertEquals(3, goodOpenCount.get());
        }
    }

    @Test
    void decodeFailureDuringOutgoingFadeIsStoredAsFailedAndNotRetried() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("bad.wav"), new short[]{500});
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger closeCount = new AtomicInteger();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path) {
                openCount.incrementAndGet();
                return new ThrowingReadAudioInputStream(
                        pcmFrames((short) 500), 1, closeCount
                );
            }
        };
        PlaybackState state = state(
                "area", true, track("bad.wav", 0, true, 0, 10)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(500, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertSingleDecodeFailure(engine, "bad.wav");
            engine.apply(7L, state);
            assertAllSilent(engine.renderFrames(4, 1.0f));
            assertTrue(engine.drainFailures().isEmpty());
            assertEquals(1, openCount.get());
            assertEquals(1, closeCount.get());
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
        return state(areaId, false, track(musicId, 0, volume, loop, fadeInMs, fadeOutMs));
    }

    private static AreaTrackDefinition track(
            String musicId,
            int delaySeconds,
            boolean loop,
            int fadeInMs,
            int fadeOutMs
    ) {
        return track(musicId, delaySeconds, 1.0f, loop, fadeInMs, fadeOutMs);
    }

    private static AreaTrackDefinition track(
            String musicId,
            int delaySeconds,
            float volume,
            boolean loop,
            int fadeInMs,
            int fadeOutMs
    ) {
        return new AreaTrackDefinition(musicId, delaySeconds, volume, loop, fadeInMs, fadeOutMs);
    }

    private static PlaybackState state(
            String areaId,
            boolean resumeOnReenter,
            AreaTrackDefinition... tracks
    ) {
        return PlaybackState.playing(areaId, List.of(tracks), resumeOnReenter);
    }

    private static short[] constantFrames(int count, short sample) {
        short[] frames = new short[count];
        java.util.Arrays.fill(frames, sample);
        return frames;
    }

    private static short[] increasingFrames(int firstSample, int count) {
        short[] frames = new short[count];
        for (int index = 0; index < count; index++) {
            frames[index] = (short) (firstSample + index);
        }
        return frames;
    }

    private static int firstNonSilentSample(PcmMixerEngine engine) {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            int sample = firstLeftSample(engine.renderFrames(1, 1.0f));
            if (sample != 0) {
                return sample;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1L));
        }
        throw new AssertionError("Timed out waiting for a non-silent rendered frame");
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

    private static void awaitLatch(CountDownLatch latch) throws IOException {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", exception);
        }
    }

    private static void awaitLatchIgnoringInterrupt(CountDownLatch latch) {
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

    private static void assertAllSilent(byte[] pcm) {
        int frameSize = AudioStreamFactory.MIX_FORMAT.getFrameSize();
        for (int offset = 0; offset < pcm.length; offset += frameSize) {
            assertEquals(0, PcmMath.readLittleEndian(pcm, offset));
            assertEquals(0, PcmMath.readLittleEndian(pcm, offset + 2));
        }
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }

    private static void writeWav(Path path, short[] monoSamples) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] pcm = new byte[monoSamples.length * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < monoSamples.length; frame++) {
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, monoSamples[frame]);
            PcmMath.writeLittleEndian(pcm, offset + 2, monoSamples[frame]);
        }
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), AudioStreamFactory.MIX_FORMAT, monoSamples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static byte[] pcmFrames(short... samples) {
        byte[] pcm = new byte[samples.length * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < samples.length; frame++) {
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, samples[frame]);
            PcmMath.writeLittleEndian(pcm, offset + 2, samples[frame]);
        }
        return pcm;
    }

    private static AudioStreamFactory streamFactoryWithBadStream(AudioInputStream badStream) {
        return new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (path.getFileName().toString().equals("bad.wav")) {
                    return badStream;
                }
                return super.open(path);
            }
        };
    }

    private static void assertSingleDecodeFailure(PcmMixerEngine engine, String musicId) {
        List<AudioFailure> failures = engine.drainFailures();
        assertEquals(1, failures.size());
        assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
        assertEquals(musicId, failures.get(0).musicId());
        assertTrue(engine.drainFailures().isEmpty());
    }

    private static DecodedFrameCount countDecodedFrames(AudioInputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        long decodedBytes = 0;
        int consecutiveZeroReads = 0;
        int zeroReads = 0;
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                consecutiveZeroReads++;
                zeroReads++;
                if (consecutiveZeroReads > 64) {
                    throw new IOException("Decoder exceeded 64 consecutive zero-byte reads");
                }
                continue;
            }
            consecutiveZeroReads = 0;
            decodedBytes += read;
        }
        assertEquals(0, decodedBytes % stream.getFormat().getFrameSize());
        return new DecodedFrameCount(decodedBytes / stream.getFormat().getFrameSize(), zeroReads);
    }

    private static int firstLeftSample(byte[] pcm) {
        return leftSample(pcm, 0);
    }

    private static int leftSample(byte[] pcm, int frame) {
        return PcmMath.readLittleEndian(pcm, frame * AudioStreamFactory.MIX_FORMAT.getFrameSize());
    }

    private record DecodedFrameCount(long frames, int zeroReads) {
    }

    private static final class FixedAudioStreamFactory extends AudioStreamFactory {
        private final AudioInputStream stream;

        private FixedAudioStreamFactory(AudioInputStream stream) {
            this.stream = stream;
        }

        @Override
        public AudioInputStream open(Path path) {
            return stream;
        }
    }

    private static final class ScriptedZeroReadAudioInputStream extends AudioInputStream {
        private int zeroReadsRemaining;
        private final boolean zeroForever;
        private int zeroReadsReturned;

        private ScriptedZeroReadAudioInputStream(byte[] pcm, int zeroReadsBeforeData, boolean zeroForever) {
            super(
                    new ByteArrayInputStream(pcm),
                    AudioStreamFactory.MIX_FORMAT,
                    pcm.length / AudioStreamFactory.MIX_FORMAT.getFrameSize()
            );
            this.zeroReadsRemaining = zeroReadsBeforeData;
            this.zeroForever = zeroForever;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted while simulating zero-byte reads");
            }
            if (zeroForever) {
                zeroReadsReturned++;
                return 0;
            }
            if (zeroReadsRemaining > 0) {
                zeroReadsRemaining--;
                zeroReadsReturned++;
                return 0;
            }
            return super.read(buffer, offset, length);
        }
    }

    private static final class ThrowingReadAudioInputStream extends AudioInputStream {
        private int successfulReadsRemaining;
        private final AtomicInteger closeCount;

        private ThrowingReadAudioInputStream(
                byte[] pcm,
                int successfulReadsBeforeFailure,
                AtomicInteger closeCount
        ) {
            super(
                    new ByteArrayInputStream(pcm),
                    AudioStreamFactory.MIX_FORMAT,
                    pcm.length / AudioStreamFactory.MIX_FORMAT.getFrameSize()
            );
            this.successfulReadsRemaining = successfulReadsBeforeFailure;
            this.closeCount = closeCount;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (successfulReadsRemaining == 0) {
                throw new IOException("decode failed");
            }
            successfulReadsRemaining--;
            return super.read(buffer, offset, length);
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }
    }

    private static class CloseCountingAudioInputStream extends AudioInputStream {
        private final AtomicInteger closeCount;

        private CloseCountingAudioInputStream(byte[] pcm, AtomicInteger closeCount) {
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

    private static final class CloseSignallingAudioInputStream
            extends CloseCountingAudioInputStream {
        private final CountDownLatch closed;

        private CloseSignallingAudioInputStream(
                byte[] pcm,
                AtomicInteger closeCount,
                CountDownLatch closed
        ) {
            super(pcm, closeCount);
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

    private static final class PartialFrameAudioInputStream extends AudioInputStream {
        private final AtomicInteger closeCount;
        private boolean partialReturned;

        private PartialFrameAudioInputStream(byte[] pcm, AtomicInteger closeCount) {
            super(
                    new ByteArrayInputStream(pcm),
                    AudioStreamFactory.MIX_FORMAT,
                    pcm.length / AudioStreamFactory.MIX_FORMAT.getFrameSize()
            );
            this.closeCount = closeCount;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (partialReturned) {
                return -1;
            }
            partialReturned = true;
            buffer[offset] = 0x12;
            buffer[offset + 1] = 0x34;
            return 2;
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }
    }
}
