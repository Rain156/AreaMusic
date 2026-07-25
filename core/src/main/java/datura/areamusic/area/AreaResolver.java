package datura.areamusic.area;

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
            String dimension,
            AreaPosition position
    ) {
        Objects.requireNonNull(areas, "areas");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        String canonicalDimension = AreaDefinition.canonicalizeDimension(dimension);

        return areas.stream()
                .filter(area -> area.containsCanonical(canonicalDimension, position))
                .min(PRECEDENCE);
    }
}
