package datura.areamusic.sync;

/** A token bucket in bytes, refilled once per tick, that caps how fast one player is served. */
public final class TransferBudget {
    private final long bytesPerTick;
    private final long burstBytes;
    private long available;

    public TransferBudget(long bytesPerTick, long burstBytes) {
        if (bytesPerTick <= 0 || burstBytes < bytesPerTick) {
            throw new IllegalArgumentException("burstBytes must be at least bytesPerTick, both positive");
        }
        this.bytesPerTick = bytesPerTick;
        this.burstBytes = burstBytes;
        available = burstBytes;
    }

    public void refill() {
        available = Math.min(burstBytes, available + bytesPerTick);
    }

    public boolean tryTake(int bytes) {
        if (bytes < 0) {
            throw new IllegalArgumentException("bytes must not be negative");
        }
        if (bytes > available) {
            return false;
        }
        available -= bytes;
        return true;
    }

    public long available() {
        return available;
    }
}
