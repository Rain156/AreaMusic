package datura.areamusic.area;

import java.util.Objects;

/** One cuboid of an area, given by any two opposite corners. Both corners are inside it. */
public record AreaBox(AreaPosition pos1, AreaPosition pos2) {
    public AreaBox {
        Objects.requireNonNull(pos1, "pos1");
        Objects.requireNonNull(pos2, "pos2");
    }

    public AreaPosition min() {
        return new AreaPosition(
                Math.min(pos1.x(), pos2.x()), Math.min(pos1.y(), pos2.y()), Math.min(pos1.z(), pos2.z()));
    }

    public AreaPosition max() {
        return new AreaPosition(
                Math.max(pos1.x(), pos2.x()), Math.max(pos1.y(), pos2.y()), Math.max(pos1.z(), pos2.z()));
    }

    public boolean contains(AreaPosition position) {
        return position.x() >= Math.min(pos1.x(), pos2.x()) && position.x() <= Math.max(pos1.x(), pos2.x())
                && position.y() >= Math.min(pos1.y(), pos2.y()) && position.y() <= Math.max(pos1.y(), pos2.y())
                && position.z() >= Math.min(pos1.z(), pos2.z()) && position.z() <= Math.max(pos1.z(), pos2.z());
    }

    /** Blocks inside, capped at {@link Long#MAX_VALUE}. */
    public long volumeInBlocks() {
        long x = (long) Math.max(pos1.x(), pos2.x()) - Math.min(pos1.x(), pos2.x()) + 1L;
        long y = (long) Math.max(pos1.y(), pos2.y()) - Math.min(pos1.y(), pos2.y()) + 1L;
        long z = (long) Math.max(pos1.z(), pos2.z()) - Math.min(pos1.z(), pos2.z()) + 1L;
        try {
            return Math.multiplyExact(Math.multiplyExact(x, y), z);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
