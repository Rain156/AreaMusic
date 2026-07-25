package datura.areamusic.client.audio;

import datura.areamusic.playback.PlaylistLoopPlayback;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

public final class PlaylistPlaybackTimeline {
    private final int entryCount;
    private final BitSet failedEntries;

    private int currentIndex;
    private long framePosition;

    private PlaylistPlaybackTimeline(
            int entryCount,
            int currentIndex,
            long framePosition,
            BitSet failedEntries
    ) {
        this.entryCount = entryCount;
        this.currentIndex = currentIndex;
        this.framePosition = framePosition;
        this.failedEntries = failedEntries;
    }

    public static PlaylistPlaybackTimeline fresh(int entryCount) {
        validateEntryCount(entryCount);
        return new PlaylistPlaybackTimeline(entryCount, 0, 0, new BitSet(entryCount));
    }

    public static PlaylistPlaybackTimeline restore(Snapshot snapshot, int expectedEntryCount) {
        Objects.requireNonNull(snapshot, "snapshot");
        validateEntryCount(expectedEntryCount);
        validateEntryCount(snapshot.entryCount());
        if (snapshot.entryCount() != expectedEntryCount) {
            throw new IllegalArgumentException(
                    "snapshot entry count " + snapshot.entryCount()
                            + " does not match expected entry count " + expectedEntryCount
            );
        }
        if (snapshot.currentIndex() < 0 || snapshot.currentIndex() >= snapshot.entryCount()) {
            throw new IllegalArgumentException("snapshot current index is out of range");
        }
        if (snapshot.framePosition() < 0) {
            throw new IllegalArgumentException("snapshot frame position must not be negative");
        }

        List<Integer> failedIndices = snapshot.failedIndices();
        if (failedIndices == null) {
            throw new IllegalArgumentException("snapshot failed indices must not be null");
        }
        BitSet failedEntries = new BitSet(snapshot.entryCount());
        for (Integer failedIndex : failedIndices) {
            if (failedIndex == null) {
                throw new IllegalArgumentException("snapshot failed index must not be null");
            }
            if (failedIndex < 0 || failedIndex >= snapshot.entryCount()) {
                throw new IllegalArgumentException(
                        "snapshot failed index " + failedIndex + " is out of range"
                );
            }
            if (failedEntries.get(failedIndex)) {
                throw new IllegalArgumentException(
                        "snapshot failed index " + failedIndex + " is duplicated"
                );
            }
            failedEntries.set(failedIndex);
        }

        return new PlaylistPlaybackTimeline(
                snapshot.entryCount(),
                snapshot.currentIndex(),
                snapshot.framePosition(),
                failedEntries
        );
    }

    public int entryCount() {
        return entryCount;
    }

    public int currentIndex() {
        return currentIndex;
    }

    public long framePosition() {
        return framePosition;
    }

    public void recordFramesRead(long frames) {
        if (frames < 0) {
            throw new IllegalArgumentException("frames must not be negative");
        }
        long updatedPosition = Math.addExact(framePosition, frames);
        framePosition = updatedPosition;
    }

    public boolean markFailed(int index) {
        checkIndex(index);
        if (failedEntries.get(index)) {
            return false;
        }
        failedEntries.set(index);
        return true;
    }

    public boolean failed(int index) {
        checkIndex(index);
        return failedEntries.get(index);
    }

    public List<Integer> failedIndices() {
        return failedEntries.stream().boxed().toList();
    }

    public boolean allFailed() {
        return failedEntries.cardinality() == entryCount;
    }

    public OptionalInt nextPlayableIndex() {
        for (int offset = 1; offset <= entryCount; offset++) {
            int candidate = (currentIndex + offset) % entryCount;
            if (!failedEntries.get(candidate)) {
                return OptionalInt.of(candidate);
            }
        }
        return OptionalInt.empty();
    }

    public OptionalInt advanceToNextPlayable() {
        OptionalInt nextIndex = nextPlayableIndex();
        if (nextIndex.isPresent()) {
            currentIndex = nextIndex.getAsInt();
            framePosition = 0;
        }
        return nextIndex;
    }

    public Snapshot snapshot() {
        return new Snapshot(entryCount, currentIndex, framePosition, failedIndices());
    }

    public record Snapshot(
            int entryCount,
            int currentIndex,
            long framePosition,
            List<Integer> failedIndices
    ) {
        public Snapshot {
            if (failedIndices != null) {
                failedIndices = Collections.unmodifiableList(new ArrayList<>(failedIndices));
            }
        }
    }

    private static void validateEntryCount(int entryCount) {
        if (entryCount < 1 || entryCount > PlaylistLoopPlayback.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "entry count must be between 1 and " + PlaylistLoopPlayback.MAX_ENTRIES
            );
        }
    }

    private void checkIndex(int index) {
        Objects.checkIndex(index, entryCount);
    }
}
