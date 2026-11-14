package datura.areamusic.area;

import java.util.Collection;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;

public final class AreaResolver {
    private static final Comparator<AreaDefinition> PRECEDENCE =
            Comparator.comparingInt(AreaDefinition::priority).reversed()
                    .thenComparing(
                            Comparator.comparingInt((AreaDefinition area) -> area.condition().specificity())
                                    .reversed())
                    .thenComparingLong(AreaDefinition::volumeInBlocks)
                    .thenComparing(AreaDefinition::id);

    private AreaResolver() {
    }

    /** The area that plays at this position on a clear day. */
    public static Optional<AreaDefinition> resolve(
            Collection<AreaDefinition> areas,
            String dimension,
            AreaPosition position
    ) {
        return resolve(areas, dimension, position, WorldMoment.CLEAR_DAY);
    }

    /**
     * The area that plays at this position right now. Of several that would, the highest
     * priority wins, then the pickiest condition, then the smallest.
     */
    public static Optional<AreaDefinition> resolve(
            Collection<AreaDefinition> areas,
            String dimension,
            AreaPosition position,
            WorldMoment moment
    ) {
        Objects.requireNonNull(areas, "areas");
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(moment, "moment");
        String canonicalDimension = AreaDefinition.canonicalizeDimension(dimension);

        return areas.stream()
                .filter(AreaDefinition::enabled)
                .filter(area -> area.condition().matches(moment))
                .filter(area -> area.containsCanonical(canonicalDimension, position))
                .min(PRECEDENCE);
    }
}
