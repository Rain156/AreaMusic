package datura.areamusic.client.audio;

import datura.areamusic.playback.PlaylistLoopPlayback;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Random;

public final class PlaylistPlaybackTimeline {
    private final int entryCount;
    private final BitSet failedEntries;
    /** Null for a playlist that plays in its written order. */
    private final Random shuffler;

    /** The order of the round being played, and where in it playback is. */
    private int[] order;
    private int orderPosition;
    /** The round after this one, drawn as soon as something needs to look that far ahead. */
    private int[] nextOrder;
    private long framePosition;

    private PlaylistPlaybackTimeline(
            int entryCount,
            int[] order,
            int orderPosition,
            long framePosition,
            BitSet failedEntries,
            Random shuffler
    ) {
        this.entryCount = entryCount;
        this.order = order;
        this.orderPosition = orderPosition;
        this.framePosition = framePosition;
        this.failedEntries = failedEntries;
        this.shuffler = shuffler;
    }

    public static PlaylistPlaybackTimeline fresh(int entryCount) {
        return fresh(entryCount, 0L);
    }

    /** @param startFrames where in the first entry playback begins */
    public static PlaylistPlaybackTimeline fresh(int entryCount, long startFrames) {
        return fresh(entryCount, startFrames, false, 0L);
    }

    /**
     * @param shuffle whether every round, the first included, plays in a new random order
     * @param seed    what the random orders are drawn from; the same seed gives the same orders
     */
    public static PlaylistPlaybackTimeline fresh(
            int entryCount, long startFrames, boolean shuffle, long seed
    ) {
        validateEntryCount(entryCount);
        if (startFrames < 0) {
            throw new IllegalArgumentException("startFrames must not be negative");
        }
        Random shuffler = shuffle ? new Random(seed) : null;
        int[] order = shuffler == null ? inOrder(entryCount) : shuffled(entryCount, shuffler, -1);
        return new PlaylistPlaybackTimeline(
                entryCount, order, 0, startFrames, new BitSet(entryCount), shuffler);
    }

    public static PlaylistPlaybackTimeline restore(Snapshot snapshot, int expectedEntryCount) {
        return restore(snapshot, expectedEntryCount, false, 0L);
    }

    public static PlaylistPlaybackTimeline restore(
            Snapshot snapshot, int expectedEntryCount, boolean shuffle, long seed
    ) {
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

        int[] order = restoredOrder(snapshot);
        int orderPosition = 0;
        while (order[orderPosition] != snapshot.currentIndex()) {
            orderPosition++;
        }
        return new PlaylistPlaybackTimeline(
                snapshot.entryCount(),
                order,
                orderPosition,
                snapshot.framePosition(),
                failedEntries,
                shuffle ? new Random(seed) : null
        );
    }

    /** The round a snapshot was taken in; a snapshot without one played in written order. */
    private static int[] restoredOrder(Snapshot snapshot) {
        List<Integer> saved = snapshot.order();
        if (saved == null || saved.isEmpty()) {
            return inOrder(snapshot.entryCount());
        }
        if (saved.size() != snapshot.entryCount()) {
            throw new IllegalArgumentException("snapshot order does not list every entry once");
        }
        BitSet seen = new BitSet(snapshot.entryCount());
        int[] order = new int[saved.size()];
        for (int position = 0; position < order.length; position++) {
            Integer index = saved.get(position);
            if (index == null || index < 0 || index >= order.length || seen.get(index)) {
                throw new IllegalArgumentException("snapshot order does not list every entry once");
            }
            seen.set(index);
            order[position] = index;
        }
        return order;
    }

    private static int[] inOrder(int entryCount) {
        int[] order = new int[entryCount];
        for (int index = 0; index < entryCount; index++) {
            order[index] = index;
        }
        return order;
    }

    /**
     * A random order of all entries.
     *
     * @param notFirst the entry that just played, which is not put first so that no track is
     *                 heard twice in a row across two rounds; -1 for none
     */
    private static int[] shuffled(int entryCount, Random random, int notFirst) {
        int[] order = inOrder(entryCount);
        for (int position = entryCount - 1; position > 0; position--) {
            int other = random.nextInt(position + 1);
            int held = order[position];
            order[position] = order[other];
            order[other] = held;
        }
        if (entryCount > 1 && order[0] == notFirst) {
            int swapWith = 1 + random.nextInt(entryCount - 1);
            order[0] = order[swapWith];
            order[swapWith] = notFirst;
        }
        return order;
    }

    public int entryCount() {
        return entryCount;
    }

    public int currentIndex() {
        return order[orderPosition];
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

    /** The entry that plays after the current one, looking into the next round if need be. */
    public OptionalInt nextPlayableIndex() {
        int[] next = nextPlayable();
        return next == null ? OptionalInt.empty() : OptionalInt.of(roundOf(next[0])[next[1]]);
    }

    public OptionalInt advanceToNextPlayable() {
        int[] next = nextPlayable();
        if (next == null) {
            return OptionalInt.empty();
        }
        if (next[0] == 1) {
            order = nextOrder;
            nextOrder = null;
        }
        orderPosition = next[1];
        framePosition = 0;
        return OptionalInt.of(order[orderPosition]);
    }

    /** Which round (0 this one, 1 the next) and position holds the next entry that can play. */
    private int[] nextPlayable() {
        for (int position = orderPosition + 1; position < entryCount; position++) {
            if (!failedEntries.get(order[position])) {
                return new int[]{0, position};
            }
        }
        if (nextOrder == null) {
            nextOrder = shuffler == null
                    ? order
                    : shuffled(entryCount, shuffler, order[orderPosition]);
        }
        for (int position = 0; position < entryCount; position++) {
            if (!failedEntries.get(nextOrder[position])) {
                return new int[]{1, position};
            }
        }
        return null;
    }

    private int[] roundOf(int round) {
        return round == 0 ? order : nextOrder;
    }

    public Snapshot snapshot() {
        List<Integer> saved = new ArrayList<>(entryCount);
        for (int index : order) {
            saved.add(index);
        }
        return new Snapshot(entryCount, currentIndex(), framePosition, failedIndices(), saved);
    }

    public record Snapshot(
            int entryCount,
            int currentIndex,
            long framePosition,
            List<Integer> failedIndices,
            List<Integer> order
    ) {
        public Snapshot {
            if (failedIndices != null) {
                failedIndices = Collections.unmodifiableList(new ArrayList<>(failedIndices));
            }
            if (order != null) {
                order = Collections.unmodifiableList(new ArrayList<>(order));
            }
        }

        /** A snapshot of a playlist that plays in its written order. */
        public Snapshot(int entryCount, int currentIndex, long framePosition, List<Integer> failedIndices) {
            this(entryCount, currentIndex, framePosition, failedIndices, List.of());
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
