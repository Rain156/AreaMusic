package datura.areamusic.area;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

public final class AreaResolver {
    private static final Comparator<AreaDefinition> PRECEDENCE =
            Comparator.comparingInt(AreaDefinition::priority).reversed()
                    .thenComparingLong(AreaDefinition::volumeInBlocks)
                    .thenComparing(AreaDefinition::id);

    private AreaResolver() {
    }

    public static Optional<AreaDefinition> resolve(
            Collection<AreaDefinition> areas,
            ResourceLocation dimension,
            BlockPos position
    ) {
        Objects.requireNonNull(areas, "areas");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");

        return areas.stream()
                .filter(area -> area.contains(dimension, position))
                .min(PRECEDENCE);
    }
}
