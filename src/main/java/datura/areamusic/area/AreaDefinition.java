package datura.areamusic.area;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;
import java.util.regex.Pattern;

public record AreaDefinition(
        String id,
        ResourceLocation dimension,
        BlockPos min,
        BlockPos max,
        String musicId,
        int priority,
        float volume,
        boolean loop,
        int fadeInMs,
        int fadeOutMs
) {
    public static final int MAX_FADE_MS = 60_000;
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    public AreaDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(min, "min");
        Objects.requireNonNull(max, "max");
        Objects.requireNonNull(musicId, "musicId");

        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid area ID: " + id);
        }
        if (musicId.isBlank()) {
            throw new IllegalArgumentException("Music ID must not be blank");
        }
        if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
            throw new IllegalArgumentException("Volume must be finite and between 0 and 1");
        }
        validateFade("fadeInMs", fadeInMs);
        validateFade("fadeOutMs", fadeOutMs);

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
            String musicId,
            int priority,
            float volume,
            boolean loop,
            int fadeInMs,
            int fadeOutMs
    ) {
        return new AreaDefinition(id, dimension, pos1, pos2, musicId, priority, volume, loop, fadeInMs, fadeOutMs);
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

    private static void validateFade(String name, int value) {
        if (value < 0 || value > MAX_FADE_MS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_FADE_MS);
        }
    }
}
