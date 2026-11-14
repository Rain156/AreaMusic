package datura.areamusic.client.audio;

import datura.areamusic.playback.PlaylistLoopPlayback;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaylistPlaybackTimelineTest {
    @Test
    void freshTimelineStartsAtTheFirstEntryAndWrapsForward() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(3);

        assertEquals(3, timeline.entryCount());
        assertEquals(0, timeline.currentIndex());
        assertEquals(0L, timeline.framePosition());
        assertEquals(List.of(), timeline.failedIndices());

        assertEquals(OptionalInt.of(1), timeline.nextPlayableIndex());
        assertEquals(0, timeline.currentIndex());

        assertEquals(OptionalInt.of(1), timeline.advanceToNextPlayable());
        assertEquals(OptionalInt.of(2), timeline.advanceToNextPlayable());
        assertEquals(OptionalInt.of(0), timeline.advanceToNextPlayable());
    }

    @Test
    void shuffledRoundsPlayEveryEntryOnceAndNeverTheSameTrackTwiceInARow() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(5, 0L, true, 42L);
        java.util.List<Integer> played = new java.util.ArrayList<>();
        played.add(timeline.currentIndex());
        for (int step = 0; step < 49; step++) {
            // What is announced as next is what plays next: the engine prepares it in advance.
            int announced = timeline.nextPlayableIndex().orElseThrow();
            assertEquals(announced, timeline.advanceToNextPlayable().orElseThrow());
            assertEquals(0L, timeline.framePosition());
            played.add(timeline.currentIndex());
        }

        boolean everInWrittenOrder = true;
        for (int round = 0; round < 10; round++) {
            java.util.List<Integer> entries = played.subList(round * 5, round * 5 + 5);
            assertEquals(java.util.Set.of(0, 1, 2, 3, 4), new java.util.HashSet<>(entries), "round " + round);
            everInWrittenOrder &= entries.equals(java.util.List.of(0, 1, 2, 3, 4));
        }
        assertFalse(everInWrittenOrder, "ten rounds in written order is not a shuffle");
        for (int index = 1; index < played.size(); index++) {
            assertFalse(played.get(index).equals(played.get(index - 1)), "repeat at " + index);
        }

        // The same seed draws the same orders, a different one does not.
        PlaylistPlaybackTimeline again = PlaylistPlaybackTimeline.fresh(5, 0L, true, 42L);
        java.util.List<Integer> replay = new java.util.ArrayList<>();
        replay.add(again.currentIndex());
        for (int step = 0; step < 49; step++) {
            replay.add(again.advanceToNextPlayable().orElseThrow());
        }
        assertEquals(played, replay);
    }

    @Test
    void aShuffledRoundSurvivesASnapshotAndSkipsFailedEntries() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(4, 100L, true, 7L);
        timeline.advanceToNextPlayable();
        timeline.recordFramesRead(30L);
        int current = timeline.currentIndex();
        int upcoming = timeline.nextPlayableIndex().orElseThrow();

        PlaylistPlaybackTimeline restored = PlaylistPlaybackTimeline.restore(
                timeline.snapshot(), 4, true, 99L);

        assertEquals(current, restored.currentIndex());
        assertEquals(30L, restored.framePosition());
        assertEquals(upcoming, restored.nextPlayableIndex().orElseThrow(), "same round as before");

        restored.markFailed(upcoming);
        assertFalse(restored.nextPlayableIndex().orElseThrow() == upcoming);
        assertThrows(IllegalArgumentException.class, () -> PlaylistPlaybackTimeline.restore(
                new PlaylistPlaybackTimeline.Snapshot(3, 0, 0L, java.util.List.of(), java.util.List.of(0, 0, 2)), 3));
    }

    @Test
    void aShuffledSingleEntryJustRepeats() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(1, 0L, true, 1L);
        assertEquals(0, timeline.currentIndex());
        assertEquals(0, timeline.advanceToNextPlayable().orElseThrow());
    }

    @Test
    void skipsFailedEntriesInCyclicOrder() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(4);
        timeline.markFailed(1);
        timeline.markFailed(3);

        assertEquals(OptionalInt.of(2), timeline.nextPlayableIndex());
        assertEquals(OptionalInt.of(2), timeline.advanceToNextPlayable());
        assertEquals(OptionalInt.of(0), timeline.nextPlayableIndex());
        assertEquals(2, timeline.currentIndex());
    }

    @Test
    void aSingleHealthyEntryLoopsToItselfAfterAFullWrap() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(3);
        timeline.markFailed(1);
        timeline.markFailed(2);
        timeline.recordFramesRead(19);

        assertEquals(OptionalInt.of(0), timeline.nextPlayableIndex());
        assertEquals(OptionalInt.of(0), timeline.advanceToNextPlayable());
        assertEquals(0, timeline.currentIndex());
        assertEquals(0L, timeline.framePosition());
    }

    @Test
    void allFailedTimelineTerminatesLookupWithoutChangingTheCursor() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(2);
        timeline.recordFramesRead(31);
        timeline.markFailed(0);
        timeline.markFailed(1);

        assertTrue(timeline.allFailed());
        assertEquals(OptionalInt.empty(), timeline.nextPlayableIndex());
        assertEquals(OptionalInt.empty(), timeline.advanceToNextPlayable());
        assertEquals(0, timeline.currentIndex());
        assertEquals(31L, timeline.framePosition());
    }

    @Test
    void markingFailuresIsIdempotentAndExposesSortedImmutableIndices() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(4);

        assertTrue(timeline.markFailed(2));
        assertTrue(timeline.markFailed(0));
        assertFalse(timeline.markFailed(2));

        assertTrue(timeline.failed(0));
        assertFalse(timeline.failed(1));
        assertTrue(timeline.failed(2));
        assertEquals(List.of(0, 2), timeline.failedIndices());
        assertThrows(UnsupportedOperationException.class, () -> timeline.failedIndices().add(3));
        assertFalse(timeline.allFailed());
    }

    @Test
    void duplicateCallerMusicIdsRemainIndependentByPlaylistPosition() {
        List<String> callerPlaylist = List.of("duplicate.ogg", "duplicate.ogg");
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(callerPlaylist.size());

        assertEquals(callerPlaylist.get(0), callerPlaylist.get(1));
        timeline.markFailed(0);

        assertTrue(timeline.failed(0));
        assertFalse(timeline.failed(1));
        assertEquals(OptionalInt.of(1), timeline.nextPlayableIndex());
    }

    @Test
    void recordsExactFramePositionsDetectsOverflowAndResetsOnlyOnAdvance() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(2);

        assertThrows(IllegalArgumentException.class, () -> timeline.recordFramesRead(-1));
        assertEquals(0L, timeline.framePosition());

        timeline.recordFramesRead(Long.MAX_VALUE);
        assertEquals(Long.MAX_VALUE, timeline.framePosition());
        assertThrows(ArithmeticException.class, () -> timeline.recordFramesRead(1));
        assertEquals(Long.MAX_VALUE, timeline.framePosition());

        assertEquals(OptionalInt.of(1), timeline.advanceToNextPlayable());
        assertEquals(0L, timeline.framePosition());
    }

    @Test
    void snapshotRoundTripsAllTimelineStateAndIsDetached() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(4);
        timeline.advanceToNextPlayable();
        timeline.recordFramesRead(9_007_199_254_740_993L);
        timeline.markFailed(1);
        timeline.markFailed(3);

        PlaylistPlaybackTimeline.Snapshot snapshot = timeline.snapshot();
        timeline.recordFramesRead(7);
        timeline.markFailed(2);

        PlaylistPlaybackTimeline restored = PlaylistPlaybackTimeline.restore(snapshot, 4);

        assertEquals(4, restored.entryCount());
        assertEquals(1, restored.currentIndex());
        assertEquals(9_007_199_254_740_993L, restored.framePosition());
        assertEquals(List.of(1, 3), restored.failedIndices());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.failedIndices().clear());
    }

    @Test
    void snapshotConstructorDefensivelyCopiesFailureIndices() {
        List<Integer> source = new ArrayList<>(List.of(2));

        PlaylistPlaybackTimeline.Snapshot snapshot =
                new PlaylistPlaybackTimeline.Snapshot(3, 1, 47L, source);
        source.clear();

        assertEquals(List.of(2), snapshot.failedIndices());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.failedIndices().add(0));
    }

    @Test
    void restoresAllFailedAndCurrentFailedSnapshotsExactly() {
        PlaylistPlaybackTimeline allFailed = PlaylistPlaybackTimeline.restore(
                new PlaylistPlaybackTimeline.Snapshot(2, 1, 88L, List.of(0, 1)),
                2
        );
        PlaylistPlaybackTimeline currentFailed = PlaylistPlaybackTimeline.restore(
                new PlaylistPlaybackTimeline.Snapshot(3, 1, 47L, List.of(1)),
                3
        );

        assertEquals(1, allFailed.currentIndex());
        assertEquals(88L, allFailed.framePosition());
        assertEquals(List.of(0, 1), allFailed.failedIndices());
        assertTrue(allFailed.allFailed());

        assertEquals(1, currentFailed.currentIndex());
        assertEquals(47L, currentFailed.framePosition());
        assertTrue(currentFailed.failed(1));
        assertEquals(OptionalInt.of(2), currentFailed.nextPlayableIndex());
    }

    @Test
    void acceptsOnlySupportedFreshEntryCounts() {
        assertEquals(1, PlaylistPlaybackTimeline.fresh(1).entryCount());
        assertEquals(
                PlaylistLoopPlayback.MAX_ENTRIES,
                PlaylistPlaybackTimeline.fresh(PlaylistLoopPlayback.MAX_ENTRIES).entryCount()
        );

        assertThrows(IllegalArgumentException.class, () -> PlaylistPlaybackTimeline.fresh(-1));
        assertThrows(IllegalArgumentException.class, () -> PlaylistPlaybackTimeline.fresh(0));
        assertThrows(IllegalArgumentException.class,
                () -> PlaylistPlaybackTimeline.fresh(PlaylistLoopPlayback.MAX_ENTRIES + 1));
    }

    @Test
    void rejectsMalformedSnapshotsAndCountMismatches() {
        PlaylistPlaybackTimeline.Snapshot valid = snapshot(2, 0, 0L, List.of());

        assertThrows(NullPointerException.class, () -> PlaylistPlaybackTimeline.restore(null, 2));
        assertRestoreRejected(valid, 0);
        assertRestoreRejected(valid, PlaylistLoopPlayback.MAX_ENTRIES + 1);
        assertRestoreRejected(valid, 3);

        assertRestoreRejected(snapshot(0, 0, 0L, List.of()), 2);
        assertRestoreRejected(snapshot(PlaylistLoopPlayback.MAX_ENTRIES + 1, 0, 0L, List.of()), 2);
        assertRestoreRejected(snapshot(2, -1, 0L, List.of()), 2);
        assertRestoreRejected(snapshot(2, 2, 0L, List.of()), 2);
        assertRestoreRejected(snapshot(2, 0, -1L, List.of()), 2);
        assertRestoreRejected(snapshot(2, 0, 0L, null), 2);
        assertRestoreRejected(snapshot(2, 0, 0L, Arrays.asList((Integer) null)), 2);
        assertRestoreRejected(snapshot(2, 0, 0L, List.of(1, 1)), 2);
        assertRestoreRejected(snapshot(2, 0, 0L, List.of(-1)), 2);
        assertRestoreRejected(snapshot(2, 0, 0L, List.of(2)), 2);
    }

    @Test
    void rejectsOutOfRangeEntryIndicesConsistently() {
        PlaylistPlaybackTimeline timeline = PlaylistPlaybackTimeline.fresh(2);

        assertThrows(IndexOutOfBoundsException.class, () -> timeline.markFailed(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> timeline.markFailed(2));
        assertThrows(IndexOutOfBoundsException.class, () -> timeline.failed(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> timeline.failed(2));
    }

    private static PlaylistPlaybackTimeline.Snapshot snapshot(
            int entryCount,
            int currentIndex,
            long framePosition,
            List<Integer> failedIndices
    ) {
        return new PlaylistPlaybackTimeline.Snapshot(
                entryCount,
                currentIndex,
                framePosition,
                failedIndices
        );
    }

    private static void assertRestoreRejected(
            PlaylistPlaybackTimeline.Snapshot snapshot,
            int expectedEntryCount
    ) {
        assertThrows(
                IllegalArgumentException.class,
                () -> PlaylistPlaybackTimeline.restore(snapshot, expectedEntryCount)
        );
    }
}
