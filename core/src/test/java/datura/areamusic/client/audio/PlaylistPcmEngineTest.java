package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaylistPcmEngineTest {
    @TempDir
    Path root;

    @Test
    void playlistEngineIsAvailableInPureCore() {
        assertDoesNotThrow(() -> Class.forName(
                "datura.areamusic.client.audio.PlaylistPcmEngine"
        ));
    }

    @Test
    void attachesInitialPreparationAndPrefetchesNextEntryBeforeEof() throws Exception {
        Path first = createMusicFile("first.ogg");
        Path second = createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(7L, playlistState("area", List.of("first.ogg", "second.ogg")));

            assertEquals(List.of(new Request(first, 0L)), preparer.requests());

            preparer.complete(0, streamWithSamples(100, 100));
            engine.renderFrames(0, 1.0f);

            assertEquals(
                    List.of(new Request(first, 0L), new Request(second, 0L)),
                    preparer.requests()
            );
        }
    }

    @Test
    void playsInOrderWithoutAGapAndWrapsToTheFirstEntry() throws Exception {
        createMusicFile("first.ogg");
        createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(3L, playlistState("area", List.of("first.ogg", "second.ogg")));
            preparer.complete(0, streamWithSamples(100, 100));
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, streamWithSamples(200, 200));

            assertEquals(List.of(100, 200), leftSamples(engine.renderFrames(2, 1.0f)));

            preparer.complete(2, streamWithSamples(100, 100));
            assertEquals(List.of(100), leftSamples(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void identicalRevisionAndStateDoNotRestartAHealthySession() throws Exception {
        createMusicFile("first.ogg");
        createMusicFile("second.ogg");
        PlaybackState state = playlistState(
                "area", List.of("first.ogg", "second.ogg")
        );
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(11L, state);
            preparer.complete(0, streamWithSamples(100, 100));
            engine.renderFrames(0, 1.0f);

            engine.apply(11L, state);

            assertEquals(2, preparer.requests().size());
        }
    }

    @Test
    void reportsMissingEntryOnceAndSkipsToTheNextIndex() throws Exception {
        Path good = createMusicFile("good.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(
                    1L,
                    playlistState("area", List.of("missing.ogg", "good.ogg"))
            );

            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.MISSING_FILE, failures.get(0).kind());
            assertEquals("missing.ogg", failures.get(0).musicId());
            assertEquals(List.of(new Request(good, 0L)), preparer.requests());

            preparer.complete(0, streamWithSamples(321, 321));
            assertEquals(List.of(321), leftSamples(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void preparationFailureIsDecodeAndSkipsToTheNextIndex() throws Exception {
        Path first = createMusicFile("first.ogg");
        Path second = createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(4L, playlistState("area", List.of("first.ogg", "second.ogg")));
            preparer.fail(0, new IllegalArgumentException("unsupported stream"));

            engine.renderFrames(0, 1.0f);

            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertEquals("first.ogg", failures.get(0).musicId());
            assertEquals(
                    List.of(new Request(first, 0L), new Request(second, 0L)),
                    preparer.requests()
            );

            preparer.complete(1, streamWithSamples(432, 432));
            assertEquals(List.of(432), leftSamples(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void preservesCompletePrefixBeforeReadFailureAndContinuesWithoutGap() throws Exception {
        createMusicFile("bad.ogg");
        createMusicFile("good.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(6L, playlistState("area", List.of("bad.ogg", "good.ogg")));
            preparer.complete(0, new PrefixThenFailureStream(
                    pcmBytes(111, 111), new IOException("decoder exploded")
            ));
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, streamWithSamples(222, 222));

            assertEquals(List.of(111, 222), leftSamples(engine.renderFrames(2, 1.0f)));
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertEquals("bad.ogg", failures.get(0).musicId());
        }
    }

    @Test
    void partialZeroAndEmptyStreamsFailOnceAndAllBadStops() throws Exception {
        createMusicFile("partial.ogg");
        createMusicFile("zero.ogg");
        createMusicFile("empty.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(
                    8L,
                    playlistState(
                            "area", List.of("partial.ogg", "zero.ogg", "empty.ogg")
                    )
            );
            preparer.complete(0, new PartialFrameStream());
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, new PersistentZeroStream());

            assertEquals(List.of(0), leftSamples(engine.renderFrames(1, 1.0f)));

            preparer.complete(2, streamWithSamples());
            assertEquals(List.of(0), leftSamples(engine.renderFrames(1, 1.0f)));

            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(
                    List.of("partial.ogg", "zero.ogg", "empty.ogg"),
                    failures.stream().map(AudioFailure::musicId).toList()
            );
            assertTrue(failures.stream().allMatch(
                    failure -> failure.kind() == AudioFailure.Kind.DECODE
            ));
            assertFalse(engine.hasWork());
            assertTrue(preparer.cancelled(3));
            assertEquals(List.of(0, 0), leftSamples(engine.renderFrames(2, 1.0f)));
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void sixtyFourZeroReadsBeforeACompleteFrameAreAccepted() throws Exception {
        createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(9L, playlistState("area", List.of("song.ogg")));
            preparer.complete(0, new ZeroThenDataStream(64, pcmBytes(321, 321)));

            assertEquals(List.of(321), leftSamples(engine.renderFrames(1, 1.0f)));
            assertTrue(engine.drainFailures().isEmpty());
        }
    }

    @Test
    void appliesSharedVolumeMasterAndFadeWithoutRestartingAtSongBoundary() throws Exception {
        createMusicFile("first.ogg");
        createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area",
                List.of("first.ogg", "second.ogg"),
                0.5f,
                1,
                0,
                true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(12L, state);
            preparer.complete(0, streamWithSamples(20_000, 20_000));
            engine.renderFrames(0, 0.5f);
            preparer.complete(1, streamWithSamples(20_000, 20_000));

            assertEquals(
                    List.of(0, Math.round(20_000.0f * 0.25f / 44.0f)),
                    leftSamples(engine.renderFrames(2, 0.5f))
            );
        }
    }

    @Test
    void pendingPreparationDoesNotConsumeIncomingFadeIn() throws Exception {
        createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 100, 0, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(13L, state);

            assertEquals(
                    List.of(0, 0),
                    leftSamples(engine.renderFrames(4_410, 1.0f)).subList(0, 2)
            );
            preparer.complete(0, streamWithSamples(10_000, 10_000));

            assertEquals(List.of(0), leftSamples(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void crossfadesPlaylistSessionsAndSaturatesTheirMix() throws Exception {
        createMusicFile("old.ogg");
        createMusicFile("new.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState oldState = PlaybackState.playingPlaylistLoop(
                "old-area", List.of("old.ogg"), 1.0f, 0, 1, true
        );
        PlaybackState newState = PlaybackState.playingPlaylistLoop(
                "new-area", List.of("new.ogg"), 1.0f, 0, 0, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(21L, oldState);
            preparer.complete(0, streamWithSamples(25_000, 25_000, 25_000, 25_000));
            engine.renderFrames(0, 1.0f);

            engine.apply(21L, newState);
            assertTrue(preparer.cancelled(1));
            preparer.complete(2, streamWithSamples(25_000, 25_000));

            assertEquals(List.of((int) Short.MAX_VALUE), leftSamples(
                    engine.renderFrames(1, 1.0f)
            ));
        }
    }

    @Test
    void reenteringSameResumableStateReclaimsLiveOutgoingStream() throws Exception {
        createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 0, 100, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(31L, state);
            preparer.complete(
                    0,
                    streamWithSamples(100, 100, 200, 200, 300, 300)
            );
            assertEquals(List.of(100), leftSamples(engine.renderFrames(1, 1.0f)));

            engine.apply(31L, PlaybackState.stopped());
            assertTrue(preparer.cancelled(1));
            engine.apply(31L, state);

            assertEquals(3, preparer.requests().size());
            assertEquals(List.of(200), leftSamples(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void restoresExactPlaylistIndexAndFrameAfterFadeCompletes() throws Exception {
        Path first = createMusicFile("first.ogg");
        Path second = createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area",
                List.of("first.ogg", "second.ogg"),
                1.0f,
                0,
                1,
                true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(41L, state);
            preparer.complete(0, streamWithSamples(10, 10));
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, constantStream(200, 60));
            assertEquals(List.of(10, 200), leftSamples(engine.renderFrames(2, 1.0f)));

            engine.apply(41L, PlaybackState.stopped());
            assertTrue(preparer.cancelled(2));
            engine.renderFrames(44, 1.0f);

            engine.apply(41L, state);

            assertEquals(new Request(second, 45L), preparer.requests().get(3));
            assertFalse(preparer.requests().get(3).equals(new Request(first, 0L)));
            preparer.complete(3, streamWithSamples(999, 999));
            assertEquals(List.of(999), leftSamples(engine.renderFrames(1, 1.0f)));
        }
    }

    @Test
    void leaveClosesPreparedPrefetchImmediatelyAndCurrentAfterFadeExactlyOnce()
            throws Exception {
        createMusicFile("first.ogg");
        createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        AtomicInteger currentCloses = new AtomicInteger();
        AtomicInteger prefetchedCloses = new AtomicInteger();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area",
                List.of("first.ogg", "second.ogg"),
                1.0f,
                0,
                1,
                true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            preparer.completeOnRequest(
                    new CloseCountingStream(constantStream(100, 60), currentCloses)
            );
            engine.apply(51L, state);
            engine.renderFrames(0, 1.0f);
            preparer.complete(
                    1,
                    new CloseCountingStream(constantStream(200, 60), prefetchedCloses)
            );

            engine.apply(51L, PlaybackState.stopped());

            assertEquals(1, prefetchedCloses.get());
            assertEquals(0, currentCloses.get());
            engine.renderFrames(44, 1.0f);
            assertEquals(1, currentCloses.get());
            engine.renderFrames(44, 1.0f);
            assertEquals(1, currentCloses.get());
            assertEquals(1, prefetchedCloses.get());
        }
    }

    @Test
    void zeroDurationFadeOutClosesAttachedCurrentImmediately() throws Exception {
        createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        AtomicInteger streamCloses = new AtomicInteger();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 0, 0, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            preparer.completeOnRequest(new CloseCountingStream(
                    constantStream(100, 60), streamCloses
            ));
            engine.apply(52L, state);
            engine.renderFrames(0, 1.0f);

            engine.apply(52L, PlaybackState.stopped());

            assertEquals(1, streamCloses.get());
            assertEquals(0, engine.retainedSessionCount());
        }
        assertEquals(1, streamCloses.get());
    }

    @Test
    void libraryReplacementClosesLiveResourcesAndRestartsFromNewLibrary()
            throws Exception {
        Path oldRoot = Files.createDirectories(root.resolve("old"));
        Path newRoot = Files.createDirectories(root.resolve("new"));
        Path oldSong = createMusicFile(oldRoot, "song.ogg");
        Path newSong = createMusicFile(newRoot, "song.ogg");
        AtomicInteger activeCloses = new AtomicInteger();
        AtomicInteger prefetchCloses = new AtomicInteger();
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = playlistState("area", List.of("song.ogg"));
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(oldRoot), preparer
        )) {
            engine.apply(61L, state);
            preparer.complete(
                    0,
                    new CloseCountingStream(constantStream(100, 10), activeCloses)
            );
            engine.renderFrames(0, 1.0f);
            preparer.complete(
                    1,
                    new CloseCountingStream(constantStream(100, 10), prefetchCloses)
            );

            engine.setMusicLibrary(MusicLibrary.scan(newRoot));

            assertEquals(1, activeCloses.get());
            assertEquals(1, prefetchCloses.get());
            engine.apply(61L, state);
            assertEquals(new Request(oldSong, 0L), preparer.requests().get(0));
            assertEquals(new Request(newSong, 0L), preparer.requests().get(2));
        }
    }

    @Test
    void revisionChangeInvalidatesSnapshotsEvenIfOldRevisionReturns() throws Exception {
        Path song = createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 0, 1, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(71L, state);
            preparer.complete(0, constantStream(100, 60));
            engine.renderFrames(1, 1.0f);
            engine.apply(71L, PlaybackState.stopped());
            engine.renderFrames(44, 1.0f);

            engine.apply(72L, PlaybackState.stopped());
            engine.apply(71L, state);

            assertEquals(new Request(song, 0L), preparer.requests().get(2));
        }
    }

    @Test
    void changedCompleteStateInvalidatesOlderSnapshotForSameArea() throws Exception {
        Path oldSong = createMusicFile("old.ogg");
        createMusicFile("replacement.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState oldState = PlaybackState.playingPlaylistLoop(
                "area", List.of("old.ogg"), 1.0f, 0, 1, true
        );
        PlaybackState replacement = PlaybackState.playingPlaylistLoop(
                "area", List.of("replacement.ogg"), 1.0f, 0, 0, false
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(81L, oldState);
            preparer.complete(0, constantStream(100, 60));
            engine.renderFrames(1, 1.0f);

            engine.apply(81L, replacement);
            engine.apply(81L, PlaybackState.stopped());
            engine.renderFrames(44, 1.0f);
            engine.apply(81L, oldState);

            assertEquals(new Request(oldSong, 0L), preparer.requests().get(3));
        }
    }

    @Test
    void ownerThreadPollsFailedPrefetchAndAdvancesPrefetchOrder() throws Exception {
        Path first = createMusicFile("first.ogg");
        createMusicFile("second.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(91L, playlistState("area", List.of("first.ogg", "second.ogg")));
            preparer.complete(0, constantStream(100, 20));
            engine.renderFrames(0, 1.0f);
            preparer.fail(1, new IOException("prefetch decode failure"));

            assertTrue(engine.drainFailures().isEmpty());
            engine.renderFrames(0, 1.0f);

            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals("second.ogg", failures.get(0).musicId());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertEquals(new Request(first, 0L), preparer.requests().get(2));
        }
    }

    @Test
    void lateCompletionAfterCloseIsClosedOnceAndInjectedPreparerIsNotOwned()
            throws Exception {
        createMusicFile("song.ogg");
        LatePreparer preparer = new LatePreparer();
        AtomicInteger streamCloses = new AtomicInteger();
        PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        );
        engine.apply(101L, playlistState("area", List.of("song.ogg")));

        engine.close();
        engine.close();
        preparer.future.complete(new CloseCountingStream(
                constantStream(100, 1), streamCloses
        ));

        assertEquals(1, streamCloses.get());
        assertEquals(0, preparer.closeCount.get());
    }

    @Test
    void lateCompletionAfterLeaveIsClosedExactlyOnce() throws Exception {
        createMusicFile("song.ogg");
        LatePreparer preparer = new LatePreparer();
        AtomicInteger streamCloses = new AtomicInteger();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(102L, playlistState("area", List.of("song.ogg")));

            engine.apply(102L, PlaybackState.stopped());
            preparer.future.complete(new CloseCountingStream(
                    constantStream(100, 1), streamCloses
            ));

            assertEquals(1, streamCloses.get());
        }
    }

    @Test
    void lateCompletionAfterLibraryReplacementIsClosedExactlyOnce() throws Exception {
        createMusicFile("song.ogg");
        Path replacementRoot = Files.createDirectories(root.resolve("replacement"));
        LatePreparer preparer = new LatePreparer();
        AtomicInteger streamCloses = new AtomicInteger();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(103L, playlistState("area", List.of("song.ogg")));

            engine.setMusicLibrary(MusicLibrary.scan(replacementRoot));
            preparer.future.complete(new CloseCountingStream(
                    constantStream(100, 1), streamCloses
            ));

            assertEquals(1, streamCloses.get());
        }
    }

    @Test
    void rapidTransitionsGateAtFourOwnedSessionsAndCoalesceLatestState()
            throws Exception {
        List<PlaybackState> states = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        for (int index = 0; index < 12; index++) {
            paths.add(createMusicFile("song-" + index + ".ogg"));
            states.add(PlaybackState.playingPlaylistLoop(
                    "area-" + index,
                    List.of("song-" + index + ".ogg"),
                    1.0f,
                    0,
                    10_000,
                    true
            ));
        }
        ManualPreparer preparer = new ManualPreparer();
        List<AtomicInteger> attachedCloses = new ArrayList<>();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            for (int index = 0; index < 4; index++) {
                AtomicInteger closeCount = new AtomicInteger();
                attachedCloses.add(closeCount);
                preparer.completeOnRequest(new CloseCountingStream(
                        constantStream(100, 20_000), closeCount
                ));
                engine.apply(111L, states.get(index));
                engine.renderFrames(0, 1.0f);
                assertTrue(engine.retainedSessionCount() <= 4);
            }
            int requestsAtCapacity = preparer.requests().size();

            assertEquals(4, engine.liveSessionCount());
            assertEquals(4, engine.retainedSessionCount());

            for (int index = 4; index < states.size(); index++) {
                engine.apply(111L, states.get(index));
                assertEquals(4, engine.liveSessionCount());
                assertEquals(4, engine.retainedSessionCount());
                assertEquals(requestsAtCapacity, preparer.requests().size());
                assertEquals(states.get(index), engine.currentState());
                assertEquals(
                        0,
                        attachedCloses.stream().mapToInt(AtomicInteger::get).sum()
                );
            }

            engine.renderFrames(882, 1.0f);

            assertEquals(4, engine.liveSessionCount());
            assertEquals(4, engine.retainedSessionCount());
            assertEquals(
                    1,
                    attachedCloses.stream().mapToInt(AtomicInteger::get).sum()
            );
            assertEquals(requestsAtCapacity + 1, preparer.requests().size());
            assertEquals(
                    new Request(paths.get(11), 0L),
                    preparer.requests().get(requestsAtCapacity)
            );
            assertEquals(states.get(11), engine.currentState());
        }
    }

    @Test
    void validatesArgumentsModesStateAndClosedOperationsConsistently() throws Exception {
        createMusicFile("song.ogg");
        MusicLibrary library = MusicLibrary.scan(root);
        ManualPreparer preparer = new ManualPreparer();
        assertThrows(
                NullPointerException.class,
                () -> new PlaylistPcmEngine(null, library)
        );
        assertThrows(
                NullPointerException.class,
                () -> new PlaylistPcmEngine(new AudioStreamFactory(), null, preparer)
        );
        assertThrows(
                NullPointerException.class,
                () -> new PlaylistPcmEngine(new AudioStreamFactory(), library, null)
        );

        PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), library, preparer
        );
        PlaybackState playlist = playlistState("area", List.of("song.ogg"));
        PlaybackState parallel = PlaybackState.playing(
                "parallel-area",
                List.of(new AreaTrackDefinition("song.ogg", 0, 1.0f, false, 0, 0)),
                false
        );

        assertEquals(PlaybackState.stopped(), engine.currentState());
        assertThrows(NullPointerException.class, () -> engine.apply(0L, null));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(-1L, playlist));
        assertThrows(IllegalArgumentException.class, () -> engine.apply(0L, parallel));
        assertEquals(PlaybackState.stopped(), engine.currentState());
        assertThrows(IllegalArgumentException.class, () -> engine.renderFrames(-1, 1.0f));
        assertThrows(ArithmeticException.class,
                () -> engine.renderFrames(Integer.MAX_VALUE, 1.0f));
        assertThrows(IllegalArgumentException.class,
                () -> engine.renderFrames(1, Float.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> engine.renderFrames(1, Float.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> engine.renderFrames(1, -0.01f));
        assertThrows(IllegalArgumentException.class, () -> engine.renderFrames(1, 1.01f));
        assertThrows(NullPointerException.class, () -> engine.setMusicLibrary(null));

        engine.apply(0L, playlist);
        assertEquals(playlist, engine.currentState());
        engine.close();
        engine.close();

        assertThrows(IllegalStateException.class,
                () -> engine.setMusicLibrary(library));
        assertThrows(IllegalStateException.class,
                () -> engine.apply(0L, PlaybackState.stopped()));
        assertThrows(IllegalStateException.class, () -> engine.renderFrames(0, 1.0f));
        assertThrows(IllegalStateException.class, engine::drainFailures);
        assertThrows(IllegalStateException.class, engine::hasWork);
        assertThrows(IllegalStateException.class, engine::currentState);
    }

    @Test
    void closesOnlyOwnedPreparerAndDoesSoOnce() throws Exception {
        MusicLibrary library = MusicLibrary.empty(root);
        ManualPreparer injected = new ManualPreparer();
        PlaylistPcmEngine injectedEngine = new PlaylistPcmEngine(
                new AudioStreamFactory(), library, injected
        );
        injectedEngine.close();
        injectedEngine.close();
        assertEquals(0, injected.closeCount.get());

        ManualPreparer owned = new ManualPreparer();
        PlaylistPcmEngine ownedEngine = new PlaylistPcmEngine(
                new AudioStreamFactory(), library, owned, true
        );
        ownedEngine.close();
        ownedEngine.close();
        assertEquals(1, owned.closeCount.get());
    }

    @Test
    void oneEntryPlaylistReopensAndLoopsWithinTheSameBuffer() throws Exception {
        Path song = createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(121L, playlistState("area", List.of("song.ogg")));
            preparer.complete(0, streamWithSamples(7, 7));
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, streamWithSamples(8, 8));

            assertEquals(List.of(7, 8), leftSamples(engine.renderFrames(2, 1.0f)));
            assertEquals(new Request(song, 0L), preparer.requests().get(0));
            assertEquals(new Request(song, 0L), preparer.requests().get(1));
        }
    }

    @Test
    void duplicateMusicIdsRemainIndependentByPlaylistIndex() throws Exception {
        Path duplicate = createMusicFile("duplicate.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(
                    122L,
                    playlistState("area", List.of("duplicate.ogg", "duplicate.ogg"))
            );
            preparer.complete(0, streamWithSamples());
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, streamWithSamples(55, 55));

            assertEquals(List.of(55), leftSamples(engine.renderFrames(1, 1.0f)));
            assertEquals(1, engine.drainFailures().size());
            assertEquals(new Request(duplicate, 0L), preparer.requests().get(0));
            assertEquals(new Request(duplicate, 0L), preparer.requests().get(1));
            assertTrue(engine.hasWork());
        }
    }

    @Test
    void resumeDisabledAlwaysRestartsAtIndexZeroFrameZero() throws Exception {
        Path song = createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 0, 100, false
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(123L, state);
            preparer.complete(0, streamWithSamples(10, 10, 20, 20));
            engine.renderFrames(1, 1.0f);
            engine.apply(123L, PlaybackState.stopped());
            engine.apply(123L, state);

            assertEquals(new Request(song, 0L), preparer.requests().get(2));
        }
    }

    @Test
    void restoredFailedIndicesStaySkipped() throws Exception {
        Path good = createMusicFile("good.ogg");
        createMusicFile("bad.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("bad.ogg", "good.ogg"), 1.0f, 0, 1, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(124L, state);
            preparer.complete(0, streamWithSamples());
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, constantStream(100, 60));
            engine.renderFrames(1, 1.0f);
            engine.drainFailures();

            engine.apply(124L, PlaybackState.stopped());
            engine.renderFrames(44, 1.0f);
            engine.apply(124L, state);

            assertEquals(new Request(good, 45L), preparer.requests().get(3));
        }
    }

    @Test
    void fadeOutStopsReadingAtBoundaryInsideALargerRenderBuffer() throws Exception {
        Path song = createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 0, 1, true
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(125L, state);
            preparer.complete(0, constantStream(100, 200));
            engine.renderFrames(1, 1.0f);
            engine.apply(125L, PlaybackState.stopped());

            engine.renderFrames(100, 1.0f);
            engine.apply(125L, state);

            assertEquals(new Request(song, 45L), preparer.requests().get(2));
        }
    }

    @Test
    void rejectedParallelApplyDoesNotInvalidatePlaylistSnapshot() throws Exception {
        Path song = createMusicFile("song.ogg");
        ManualPreparer preparer = new ManualPreparer();
        PlaybackState playlist = PlaybackState.playingPlaylistLoop(
                "area", List.of("song.ogg"), 1.0f, 0, 1, true
        );
        PlaybackState parallel = PlaybackState.playing(
                "parallel-area",
                List.of(new AreaTrackDefinition("song.ogg", 0, 1.0f, false, 0, 0)),
                false
        );
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(131L, playlist);
            preparer.complete(0, constantStream(100, 60));
            engine.renderFrames(1, 1.0f);
            engine.apply(131L, PlaybackState.stopped());
            engine.renderFrames(44, 1.0f);

            assertThrows(IllegalArgumentException.class, () -> engine.apply(132L, parallel));
            engine.apply(131L, playlist);

            assertEquals(new Request(song, 45L), preparer.requests().get(2));
        }
    }

    @Test
    void uncheckedDecoderFailurePreservesPrefixAndIsReported() throws Exception {
        createMusicFile("bad.ogg");
        createMusicFile("good.ogg");
        ManualPreparer preparer = new ManualPreparer();
        try (PlaylistPcmEngine engine = new PlaylistPcmEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root), preparer
        )) {
            engine.apply(141L, playlistState("area", List.of("bad.ogg", "good.ogg")));
            preparer.complete(0, new PrefixThenRuntimeFailureStream(pcmBytes(77, 77)));
            engine.renderFrames(0, 1.0f);
            preparer.complete(1, streamWithSamples(88, 88));

            assertEquals(List.of(77, 88), leftSamples(engine.renderFrames(2, 1.0f)));
            List<AudioFailure> failures = engine.drainFailures();
            assertEquals(1, failures.size());
            assertEquals(AudioFailure.Kind.DECODE, failures.get(0).kind());
            assertEquals("bad.ogg", failures.get(0).musicId());
        }
    }

    private Path createMusicFile(String name) throws Exception {
        return createMusicFile(root, name);
    }

    private static Path createMusicFile(Path directory, String name) throws Exception {
        Path path = directory.resolve(name);
        Files.write(path, new byte[]{1});
        return path.toAbsolutePath().normalize();
    }

    private static PlaybackState playlistState(String areaId, List<String> musicIds) {
        return PlaybackState.playingPlaylistLoop(
                areaId, musicIds, 1.0f, 0, 0, true
        );
    }

    private static AudioInputStream streamWithSamples(int... interleavedSamples) {
        byte[] pcm = pcmBytes(interleavedSamples);
        return new AudioInputStream(
                new ByteArrayInputStream(pcm),
                AudioStreamFactory.MIX_FORMAT,
                interleavedSamples.length / 2L
        );
    }

    private static AudioInputStream constantStream(int sample, int frameCount) {
        int[] samples = new int[frameCount * 2];
        java.util.Arrays.fill(samples, sample);
        return streamWithSamples(samples);
    }

    private static byte[] pcmBytes(int... interleavedSamples) {
        byte[] pcm = new byte[interleavedSamples.length * 2];
        for (int index = 0; index < interleavedSamples.length; index++) {
            PcmMath.writeLittleEndian(pcm, index * 2, interleavedSamples[index]);
        }
        return pcm;
    }

    private static List<Integer> leftSamples(byte[] pcm) {
        List<Integer> samples = new ArrayList<>();
        for (int offset = 0; offset < pcm.length; offset += 4) {
            samples.add((int) PcmMath.readLittleEndian(pcm, offset));
        }
        return samples;
    }

    private record Request(Path path, long frameOffset) {
    }

    private static final class ManualPreparer implements AudioStreamPreparation {
        private final List<Request> requests = new ArrayList<>();
        private final List<CompletableFuture<AudioInputStream>> futures = new ArrayList<>();
        private final List<AudioInputStream> completeOnRequest = new ArrayList<>();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletableFuture<AudioInputStream> prepare(Path path, long frameOffset) {
            requests.add(new Request(path, frameOffset));
            CompletableFuture<AudioInputStream> future = new CompletableFuture<>();
            futures.add(future);
            if (!completeOnRequest.isEmpty()) {
                future.complete(completeOnRequest.remove(0));
            }
            return future;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }

        private List<Request> requests() {
            return List.copyOf(requests);
        }

        private void complete(int requestIndex, AudioInputStream stream) {
            futures.get(requestIndex).complete(stream);
        }

        private void fail(int requestIndex, Throwable failure) {
            futures.get(requestIndex).completeExceptionally(failure);
        }

        private boolean cancelled(int requestIndex) {
            return futures.get(requestIndex).isCancelled();
        }

        private void completeOnRequest(AudioInputStream stream) {
            completeOnRequest.add(stream);
        }
    }

    private static final class LatePreparer implements AudioStreamPreparation {
        private final NonCancellingFuture future = new NonCancellingFuture();
        private final AtomicInteger closeCount = new AtomicInteger();

        @Override
        public CompletableFuture<AudioInputStream> prepare(Path path, long frameOffset) {
            return future;
        }

        @Override
        public void close() {
            closeCount.incrementAndGet();
        }
    }

    private static final class NonCancellingFuture
            extends CompletableFuture<AudioInputStream> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    private static final class PrefixThenFailureStream extends AudioInputStream {
        private final byte[] prefix;
        private final IOException failure;
        private boolean prefixReturned;

        private PrefixThenFailureStream(byte[] prefix, IOException failure) {
            super(
                    new ByteArrayInputStream(new byte[0]),
                    AudioStreamFactory.MIX_FORMAT,
                    AudioSystemNotSpecified.FRAMES
            );
            this.prefix = prefix.clone();
            this.failure = failure;
        }

        @Override
        public int read(byte[] destination, int offset, int length) throws IOException {
            if (!prefixReturned) {
                prefixReturned = true;
                int count = Math.min(length, prefix.length);
                System.arraycopy(prefix, 0, destination, offset, count);
                return count;
            }
            throw failure;
        }
    }

    private static final class PrefixThenRuntimeFailureStream extends AudioInputStream {
        private final byte[] prefix;
        private boolean prefixReturned;

        private PrefixThenRuntimeFailureStream(byte[] prefix) {
            super(
                    new ByteArrayInputStream(new byte[0]),
                    AudioStreamFactory.MIX_FORMAT,
                    AudioSystemNotSpecified.FRAMES
            );
            this.prefix = prefix.clone();
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (!prefixReturned) {
                prefixReturned = true;
                int count = Math.min(length, prefix.length);
                System.arraycopy(prefix, 0, destination, offset, count);
                return count;
            }
            throw new IllegalStateException("unchecked decoder failure");
        }
    }

    private static final class PartialFrameStream extends AudioInputStream {
        private boolean returned;

        private PartialFrameStream() {
            super(
                    new ByteArrayInputStream(new byte[0]),
                    AudioStreamFactory.MIX_FORMAT,
                    AudioSystemNotSpecified.FRAMES
            );
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (returned) {
                return -1;
            }
            returned = true;
            destination[offset] = 1;
            destination[offset + 1] = 2;
            return 2;
        }
    }

    private static final class PersistentZeroStream extends AudioInputStream {
        private PersistentZeroStream() {
            super(
                    new ByteArrayInputStream(new byte[0]),
                    AudioStreamFactory.MIX_FORMAT,
                    AudioSystemNotSpecified.FRAMES
            );
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            return 0;
        }
    }

    private static final class ZeroThenDataStream extends AudioInputStream {
        private final byte[] data;
        private int zeroReadsRemaining;
        private boolean dataReturned;

        private ZeroThenDataStream(int zeroReads, byte[] data) {
            super(
                    new ByteArrayInputStream(new byte[0]),
                    AudioStreamFactory.MIX_FORMAT,
                    AudioSystemNotSpecified.FRAMES
            );
            this.zeroReadsRemaining = zeroReads;
            this.data = data.clone();
        }

        @Override
        public int read(byte[] destination, int offset, int length) {
            if (zeroReadsRemaining > 0) {
                zeroReadsRemaining--;
                return 0;
            }
            if (dataReturned) {
                return -1;
            }
            dataReturned = true;
            int count = Math.min(length, data.length);
            System.arraycopy(data, 0, destination, offset, count);
            return count;
        }
    }

    private static final class AudioSystemNotSpecified {
        private static final long FRAMES = -1L;

        private AudioSystemNotSpecified() {
        }
    }

    private static final class CloseCountingStream extends AudioInputStream {
        private final AtomicInteger closeCount;

        private CloseCountingStream(AudioInputStream delegate, AtomicInteger closeCount) {
            super(delegate, delegate.getFormat(), delegate.getFrameLength());
            this.closeCount = closeCount;
        }

        @Override
        public void close() throws IOException {
            closeCount.incrementAndGet();
            super.close();
        }
    }
}
