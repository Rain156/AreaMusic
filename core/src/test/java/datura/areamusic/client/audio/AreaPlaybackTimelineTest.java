package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaPlaybackTimelineTest {
    private static final int TEN_HZ = 10;

    @Test
    void startsTracksOnlyWhenTheirFrameDelaysAreDue() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("immediate", 0), track("delayed", 5)),
                TEN_HZ
        );

        assertEquals(List.of(0), timeline.dueTrackIndices());
        assertEquals(0L, timeline.framesUntilNextStart());

        timeline.markStarted(0);
        assertEquals(50L, timeline.framesUntilNextStart());

        timeline.advancePending(49);
        assertEquals(List.of(), timeline.dueTrackIndices());
        assertEquals(1L, timeline.framesUntilNextStart());

        timeline.advancePending(1);
        assertEquals(List.of(1), timeline.dueTrackIndices());
        assertEquals(0L, timeline.framesUntilNextStart());
    }

    @Test
    void restoresRemainingDelayPositionAndCompletion() {
        List<AreaTrackDefinition> definitions = List.of(
                track("loop", 0),
                track("pending", 10)
        );
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(definitions, TEN_HZ);

        timeline.markStarted(0);
        timeline.recordFramesRead(0, 37);
        timeline.advancePending(40);
        timeline.markCompleted(0);

        AreaPlaybackTimeline restored = AreaPlaybackTimeline.restore(
                timeline.snapshot(),
                definitions,
                TEN_HZ
        );

        assertEquals(60L, restored.remainingDelayFrames(1));
        assertEquals(37L, restored.positionInLoopFrames(0));
        assertTrue(restored.started(0));
        assertTrue(restored.completed(0));
        assertFalse(restored.failed(0));
    }

    @Test
    void restoresFailedTracksWithoutMakingThemPending() {
        List<AreaTrackDefinition> definitions = List.of(
                track("failed-before-start", 10),
                track("failed-after-start", 0),
                track("pending", 12)
        );
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(definitions, TEN_HZ);

        timeline.advancePending(30);
        timeline.markFailed(0);
        timeline.markStarted(1);
        timeline.recordFramesRead(1, 37);
        timeline.markFailed(1);

        AreaPlaybackTimeline restored = AreaPlaybackTimeline.restore(
                timeline.snapshot(),
                definitions,
                TEN_HZ
        );

        assertEquals(70L, restored.remainingDelayFrames(0));
        assertEquals(0L, restored.positionInLoopFrames(0));
        assertFalse(restored.started(0));
        assertFalse(restored.completed(0));
        assertTrue(restored.failed(0));

        assertEquals(0L, restored.remainingDelayFrames(1));
        assertEquals(37L, restored.positionInLoopFrames(1));
        assertTrue(restored.started(1));
        assertFalse(restored.completed(1));
        assertTrue(restored.failed(1));

        assertEquals(List.of(), restored.dueTrackIndices());
        assertEquals(90L, restored.framesUntilNextStart());

        restored.advancePending(90);
        assertEquals(List.of(2), restored.dueTrackIndices());
        assertEquals(70L, restored.remainingDelayFrames(0));
        assertEquals(37L, restored.positionInLoopFrames(1));
    }

    @Test
    void keepsTrackLifecycleStateIndependent() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("first", 0), track("second", 0)),
                TEN_HZ
        );

        assertEquals(List.of(0, 1), timeline.dueTrackIndices());

        timeline.markStarted(0);
        timeline.recordFramesRead(0, 12);
        timeline.markLoopRestarted(0);
        timeline.markFailed(1);

        assertEquals(0L, timeline.positionInLoopFrames(0));
        assertTrue(timeline.failed(1));
        assertFalse(timeline.failed(0));
        assertEquals(List.of(), timeline.dueTrackIndices());
        assertEquals(Long.MAX_VALUE, timeline.framesUntilNextStart());

        timeline.markCompleted(0);
        assertTrue(timeline.completed(0));
        assertFalse(timeline.completed(1));
        assertTrue(timeline.failed(1));
    }

    @Test
    void handlesMaximumDelayAndRejectsInvalidAdvanceAndRestoreSize() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("maximum", Integer.MAX_VALUE)),
                44_100
        );
        long expectedFrames = (long) Integer.MAX_VALUE * 44_100L;

        assertEquals(expectedFrames, timeline.remainingDelayFrames(0));
        assertEquals(expectedFrames, timeline.framesUntilNextStart());
        assertThrows(IllegalArgumentException.class, () -> timeline.advancePending(-1));
        assertEquals(expectedFrames, timeline.remainingDelayFrames(0));

        AreaPlaybackTimeline.Snapshot oneTrack = timeline.snapshot();
        assertThrows(IllegalArgumentException.class, () -> AreaPlaybackTimeline.restore(
                oneTrack,
                List.of(track("first", Integer.MAX_VALUE), track("second", 0)),
                44_100
        ));
    }

    @Test
    void requiresTrackToBeDueAndUnterminatedBeforeStarting() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("early", 1), track("due", 0), track("failed", 0)),
                TEN_HZ
        );

        assertThrows(IllegalStateException.class, () -> timeline.markStarted(0));
        assertThrows(IllegalStateException.class, () -> timeline.markCompleted(0));

        timeline.markStarted(1);
        assertThrows(IllegalStateException.class, () -> timeline.markStarted(1));

        timeline.markFailed(2);
        assertThrows(IllegalStateException.class, () -> timeline.markStarted(2));
    }

    @Test
    void validatesFreshInputsAndDefensivelyCopiesDefinitions() {
        assertThrows(NullPointerException.class, () -> AreaPlaybackTimeline.fresh(null, TEN_HZ));
        assertThrows(IllegalArgumentException.class,
                () -> AreaPlaybackTimeline.fresh(List.of(track("one", 0)), 0));
        assertThrows(IllegalArgumentException.class,
                () -> AreaPlaybackTimeline.fresh(List.of(), TEN_HZ));

        List<AreaTrackDefinition> tooMany = IntStream.range(0, 17)
                .mapToObj(index -> track("track-" + index, 0))
                .toList();
        assertThrows(IllegalArgumentException.class,
                () -> AreaPlaybackTimeline.fresh(tooMany, TEN_HZ));

        List<AreaTrackDefinition> containingNull = new ArrayList<>();
        containingNull.add(null);
        assertThrows(NullPointerException.class,
                () -> AreaPlaybackTimeline.fresh(containingNull, TEN_HZ));

        List<AreaTrackDefinition> source = new ArrayList<>(List.of(track("copied", 0)));
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(source, TEN_HZ);
        source.clear();

        assertEquals(List.of(0), timeline.dueTrackIndices());
    }

    @Test
    void acceptsSixteenTracksAndKeepsDueIndicesStable() {
        List<AreaTrackDefinition> definitions = IntStream.range(0, 16)
                .mapToObj(index -> track("track-" + index, 0))
                .toList();
        List<Integer> allIndices = IntStream.range(0, 16).boxed().toList();
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(definitions, TEN_HZ);

        assertEquals(allIndices, timeline.dueTrackIndices());
        assertEquals(allIndices, timeline.dueTrackIndices());

        timeline.markStarted(0);
        timeline.markFailed(15);

        assertEquals(IntStream.range(1, 15).boxed().toList(), timeline.dueTrackIndices());
        assertTrue(timeline.started(0));
        assertTrue(timeline.failed(15));
    }

    @Test
    void recordsFramesOnlyForActiveTracksAndDetectsOverflow() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("loop", 0)),
                TEN_HZ
        );

        assertThrows(IllegalStateException.class, () -> timeline.recordFramesRead(0, 1));
        assertThrows(IllegalStateException.class, () -> timeline.markLoopRestarted(0));

        timeline.markStarted(0);
        assertThrows(IllegalArgumentException.class, () -> timeline.recordFramesRead(0, -1));

        timeline.recordFramesRead(0, Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> timeline.recordFramesRead(0, 1));
        assertEquals(Long.MAX_VALUE, timeline.positionInLoopFrames(0));

        timeline.markLoopRestarted(0);
        timeline.recordFramesRead(0, 4);
        timeline.markFailed(0);

        assertTrue(timeline.failed(0));
        assertEquals(4L, timeline.positionInLoopFrames(0));
        assertThrows(IllegalStateException.class, () -> timeline.recordFramesRead(0, 0));
        assertThrows(IllegalStateException.class, () -> timeline.markLoopRestarted(0));
        assertThrows(IllegalStateException.class, () -> timeline.markCompleted(0));
    }

    @Test
    void advancesOnlyPendingTracksAndClampsAtZero() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("pending", 10), track("failed", 10)),
                TEN_HZ
        );

        timeline.markFailed(1);
        timeline.advancePending(Long.MAX_VALUE);

        assertEquals(0L, timeline.remainingDelayFrames(0));
        assertEquals(100L, timeline.remainingDelayFrames(1));
        assertEquals(List.of(0), timeline.dueTrackIndices());

        timeline.markStarted(0);
        timeline.recordFramesRead(0, 5);
        timeline.advancePending(10);
        assertEquals(5L, timeline.positionInLoopFrames(0));

        timeline.markCompleted(0);
        timeline.advancePending(10);
        assertEquals(5L, timeline.positionInLoopFrames(0));
    }

    @Test
    void returnsImmutableDueIndicesAndDetachedSnapshots() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("delayed", 1)),
                TEN_HZ
        );
        AreaPlaybackTimeline.Snapshot beforeAdvance = timeline.snapshot();

        timeline.advancePending(10);
        List<Integer> due = timeline.dueTrackIndices();

        assertEquals(List.of(0), due);
        assertThrows(UnsupportedOperationException.class, () -> due.add(1));
        assertEquals(10L, beforeAdvance.tracks().get(0).remainingDelayFrames());
        assertThrows(UnsupportedOperationException.class, () -> beforeAdvance.tracks().clear());

        List<AreaPlaybackTimeline.TrackSnapshot> source = new ArrayList<>(List.of(
                new AreaPlaybackTimeline.TrackSnapshot(0, 0, false, false, false)
        ));
        AreaPlaybackTimeline.Snapshot copied = new AreaPlaybackTimeline.Snapshot(source);
        source.clear();

        assertEquals(1, copied.tracks().size());
    }

    @Test
    void rejectsInvalidRestoredTrackStateAndInputs() {
        List<AreaTrackDefinition> definitions = List.of(track("delayed", 10));

        assertThrows(NullPointerException.class,
                () -> AreaPlaybackTimeline.restore(null, definitions, TEN_HZ));
        assertThrows(NullPointerException.class,
                () -> AreaPlaybackTimeline.restore(validSnapshot(), null, TEN_HZ));
        assertThrows(IllegalArgumentException.class,
                () -> AreaPlaybackTimeline.restore(validSnapshot(), definitions, 0));
        assertThrows(IllegalArgumentException.class, () -> AreaPlaybackTimeline.restore(
                new AreaPlaybackTimeline.Snapshot(List.of()),
                definitions,
                TEN_HZ
        ));

        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(-1, 0, false, false, false));
        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(101, 0, false, false, false));
        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(0, -1, true, false, false));
        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(1, 0, true, false, false));
        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(0, 1, false, false, false));
        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(0, 0, false, true, false));
        assertRestoreRejected(definitions,
                new AreaPlaybackTimeline.TrackSnapshot(0, 0, true, true, true));
    }

    @Test
    void rejectsOutOfRangeTrackIndices() {
        AreaPlaybackTimeline timeline = AreaPlaybackTimeline.fresh(
                List.of(track("only", 0)),
                TEN_HZ
        );

        assertThrows(IndexOutOfBoundsException.class, () -> timeline.remainingDelayFrames(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> timeline.markStarted(1));
        assertThrows(IndexOutOfBoundsException.class, () -> timeline.failed(1));
    }

    private static AreaPlaybackTimeline.Snapshot validSnapshot() {
        return new AreaPlaybackTimeline.Snapshot(List.of(
                new AreaPlaybackTimeline.TrackSnapshot(0, 0, false, false, false)
        ));
    }

    private static void assertRestoreRejected(
            List<AreaTrackDefinition> definitions,
            AreaPlaybackTimeline.TrackSnapshot trackSnapshot
    ) {
        AreaPlaybackTimeline.Snapshot snapshot = new AreaPlaybackTimeline.Snapshot(List.of(trackSnapshot));
        assertThrows(IllegalArgumentException.class,
                () -> AreaPlaybackTimeline.restore(snapshot, definitions, TEN_HZ));
    }

    private static AreaTrackDefinition track(String id, int delaySeconds) {
        return new AreaTrackDefinition(id, delaySeconds, 1.0f, true, 0, 0);
    }
}
