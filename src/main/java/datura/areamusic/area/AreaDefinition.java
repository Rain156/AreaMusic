package datura.areamusic.area;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record AreaDefinition(
        String id,
        ResourceLocation dimension,
        BlockPos min,
        BlockPos max,
        List<AreaTrackDefinition> tracks,
        boolean resumeOnReenter,
        int priority
) {
    public static final int MAX_TRACKS = 16;
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    public AreaDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(tracks, "tracks");

        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid area ID: " + id);
        }

        tracks = List.copyOf(tracks);
        if (tracks.isEmpty() || tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("Area must contain between 1 and " + MAX_TRACKS + " tracks");
        }

        BlockPos first = min;
        BlockPos second = max;
        min = new BlockPos(
                Math.min(first.getX(), second.getX()),
                Math.min(first.getY(), second.getY()),
                Math.min(first.getZ(), second.getZ())
        );
        max = new BlockPos(
                Math.max(first.getX(), second.getX()),
                Math.max(first.getY(), second.getY()),
                Math.max(first.getZ(), second.getZ())
        );
    }

    public static AreaDefinition create(
            String id,
            ResourceLocation dimension,
            BlockPos pos1,
            BlockPos pos2,
            List<AreaTrackDefinition> tracks,
            boolean resumeOnReenter,
            int priority
    ) {
        return new AreaDefinition(id, dimension, pos1, pos2, tracks, resumeOnReenter, priority);
    }

    public boolean contains(ResourceLocation candidateDimension, BlockPos position) {
        return dimension.equals(candidateDimension)
                && position.getX() >= min.getX() && position.getX() <= max.getX()
                && position.getY() >= min.getY() && position.getY() <= max.getY()
                && position.getZ() >= min.getZ() && position.getZ() <= max.getZ();
    }

    public long volumeInBlocks() {
        long x = (long) max.getX() - min.getX() + 1L;
        long y = (long) max.getY() - min.getY() + 1L;
        long z = (long) max.getZ() - min.getZ() + 1L;
        try {
            return Math.multiplyExact(Math.multiplyExact(x, y), z);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
