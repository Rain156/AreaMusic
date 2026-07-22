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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

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
    void fifthRapidTransitionDoesNotHardEvictAnAudibleTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("track0.wav"), constantFrames(16, (short) 10_000));
        for (int index = 1; index < 5; index++) {
            writeWav(root.resolve("track" + index + ".wav"), constantFrames(16, (short) 0));
        }
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        for (int index = 0; index < 4; index++) {
            engine.apply(1L, playing(
                    "area" + index, "track" + index + ".wav", 1.0f, true, 0, 10_000
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(1L, playing("area4", "track4.wav", 1.0f, true, 0, 10_000));

        assertTrue(firstLeftSample(engine.renderFrames(1, 1.0f)) > 9_000);
        engine.close();
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
            assertEquals(7000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);

            assertEquals(8000, firstNonSilentSample(engine));
            assertEquals(1, oldOpenCount.get());
            assertEquals(2, newOpenCount.get());
        }
    }

    @Test
    void revisionChangeDoesNotTransferTheOldCursorIntoANewRevisionSnapshot() throws Exception {
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
            assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(7L, PlaybackState.stopped());
            engine.apply(7L, state);

            assertEquals(2000, firstNonSilentSample(engine));
            assertEquals(3, openCount.get());
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
        writeWav(root.resolve("short.wav"), increasingFrames(1000, 256));
        writeWav(root.resolve("long.wav"), increasingFrames(2000, 256));
        AtomicInteger openCount = new AtomicInteger();
        List<Thread> restoreWorkers = new CopyOnWriteArrayList<>();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (openCount.incrementAndGet() > 2) {
                    restoreWorkers.add(Thread.currentThread());
                }
                return delegate.open(path);
            }
        };
        PlaybackState state = state(
                "area", true,
                track("short.wav", 0, true, 0, 1),
                track("long.wav", 0, true, 0, 2)
        );

        try (PcmMixerEngine engine = new PcmMixerEngine(factory, MusicLibrary.scan(root))) {
            engine.apply(7L, state);
            assertEquals(3000, firstLeftSample(engine.renderFrames(1, 1.0f)));
            engine.apply(7L, PlaybackState.stopped());
            engine.renderFrames(200, 1.0f);
            engine.apply(7L, state);
            assertTrue(awaitCondition(
                    () -> openCount.get() == 4
                            && restoreWorkers.size() == 2
                            && restoreWorkers.stream().allMatch(
                            worker -> worker.getState() == Thread.State.WAITING
                    ),
                    Duration.ofSeconds(2)
            ));
            engine.renderFrames(0, 1.0f);
            assertEquals(3134, firstLeftSample(engine.renderFrames(1, 1.0f)));
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
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000, 3000});
        AtomicInteger openCount = new AtomicInteger();
        AtomicInteger restoredCloseCount = new AtomicInteger();
        AtomicReference<Thread> preparationWorker = new AtomicReference<>();
        AudioStreamFactory delegate = new AudioStreamFactory();
        AudioStreamFactory factory = new AudioStreamFactory() {
            @Override
            public AudioInputStream open(Path path)
                    throws UnsupportedAudioFileException, IOException {
                if (openCount.incrementAndGet() == 1) {
                    return delegate.open(path);
                }
                preparationWorker.set(Thread.currentThread());
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
            assertTrue(awaitCondition(
                    () -> preparationWorker.get() != null
                            && preparationWorker.get().getState() == Thread.State.WAITING,
                    Duration.ofSeconds(2)
            ));
            engine.apply(7L, PlaybackState.stopped());
            assertEquals(1, restoredCloseCount.get());
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
            Thread.yield();
        }
        throw new AssertionError("Timed out waiting for a non-silent rendered frame");
    }

    private static boolean awaitCondition(Check condition, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.value()) {
                return true;
            }
            Thread.yield();
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
                return 0;
            }
            if (zeroReadsRemaining > 0) {
                zeroReadsRemaining--;
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
