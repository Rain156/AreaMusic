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
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmMixerEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void publicApplyAndRenderApisDoNotDeclarePerTrackFailures() throws Exception {
        assertEquals(
                0,
                PcmMixerEngine.class
                        .getMethod("apply", PlaybackState.class)
                        .getExceptionTypes().length
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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
                    "quiet",
                    false,
                    track("ramp.wav", 0, 0.0f, true, 0, 20)
            ));

            assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.hasWork());

            engine.apply(state(
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
            engine.apply(state(
                    "quiet",
                    false,
                    track("quiet.wav", 0, 0.0f, true, 0, 20)
            ));
            engine.renderFrames(1, 1.0f);

            engine.apply(PlaybackState.stopped());

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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
                    "positive",
                    false,
                    track("positive.wav", 0, false, 0, 0),
                    track("positive.wav", 0, false, 0, 0)
            ));
            assertEquals(Short.MAX_VALUE, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(state(
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
            engine.apply(state(
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
        engine.apply(state(
                "area",
                false,
                track("first.wav", 0, true, 0, 0),
                track("second.wav", 0, true, 0, 0)
        ));
        engine.apply(PlaybackState.stopped());

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
            engine.apply(state(
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
            engine.apply(state(
                    "area", false, track("delayed.wav", 1, false, 0, 0)
            ));

            assertTrue(engine.hasWork());

            engine.apply(PlaybackState.stopped());

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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
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
            engine.apply(state(
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

        engine.apply(playing("first", "track.wav", 1.0f, false, 0, 0));
        assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

        engine.apply(playing("second", "track.wav", 0.5f, false, 0, 0));
        assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

        engine.close();
    }

    @Test
    void crossfadesOldAndNewTracksAtTheSameTime() throws Exception {
        Path root = tempDir.resolve("music");
        short[] positive = constantFrames(50_000, (short) 10_000);
        short[] negative = constantFrames(50_000, (short) -10_000);
        writeWav(root.resolve("old.wav"), positive);
        writeWav(root.resolve("new.wav"), negative);
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(playing("old", "old.wav", 1.0f, false, 0, 1000));
        engine.renderFrames(1, 1.0f);
        engine.apply(playing("new", "new.wav", 1.0f, false, 1000, 1000));

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
            engine.apply(state(
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
            engine.apply(state(
                    "old",
                    false,
                    track("immediate.wav", 0, true, 0, 0),
                    track("fading.wav", 0, true, 0, 1000)
            ));
            assertEquals(20_000, firstLeftSample(engine.renderFrames(1, 1.0f)));

            engine.apply(state(
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
        engine.apply(playing("loop", "loop.wav", 1.0f, true, 0, 0));

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

        engine.apply(state);
        assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));

        engine.setMusicLibrary(library);
        engine.apply(state);

        assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    @Test
    void completedNonLoopTrackRestartsWhenReloadChangesItToLooping() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{4321});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(playing("once", "once.wav", 1.0f, false, 0, 0));
        assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));

        engine.apply(playing("once", "once.wav", 1.0f, true, 0, 0));

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
            engine.apply(playing(
                    "area" + index, "track" + index + ".wav", 1.0f, true, 0, 10_000
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(playing("area4", "track4.wav", 1.0f, true, 0, 10_000));

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
            engine.apply(playing(
                    "area" + index, "track" + index + ".wav", 1.0f, true, 0, 60_000
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(playing("area4", "track4.wav", 1.0f, true, 0, 60_000));
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

        engine.apply(state(
                "area0", false, track("audible.wav", 0, 1.0f, true, 0, 60_000)
        ));
        engine.renderFrames(1, 1.0f);
        engine.apply(state(
                "area1", false, track("silent1.wav", 0, 0.0f, true, 0, 60_000)
        ));
        engine.renderFrames(1, 1.0f);
        for (int index = 2; index < 4; index++) {
            engine.apply(state(
                    "area" + index,
                    false,
                    track("silent" + index + ".wav", 0, 1.0f, true, 0, 60_000)
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(state(
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
            engine.apply(playing("scripted", "scripted.wav", 1.0f, false, 0, 0));

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
            engine.apply(playing("stalled", "stalled.wav", 1.0f, false, 0, 0));

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
            engine.apply(playing("ogg", "test.ogg", 1.0f, false, 0, 0));
            while (engine.hasWork() && renderedBlocks < renderGuard) {
                engine.renderFrames(1024, 1.0f);
                renderedBlocks++;
            }
            assertFalse(engine.hasWork(), "Mixer did not reach the end of test.ogg within the render guard");
        }

        assertEquals(expectedBlocks, renderedBlocks);
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

    private static final class CloseCountingAudioInputStream extends AudioInputStream {
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
