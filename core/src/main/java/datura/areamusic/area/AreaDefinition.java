package datura.areamusic.area;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record AreaDefinition(
        String id,
        String dimension,
        AreaPosition pos1,
        AreaPosition pos2,
        List<AreaTrackDefinition> tracks,
        boolean resumeOnReenter,
        int priority
) {
    public static final int MAX_TRACKS = 16;
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern DIMENSION_NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern DIMENSION_PATH_PATTERN = Pattern.compile("[a-z0-9/._-]+");

    public AreaDefinition {
        Objects.requireNonNull(id, "id");
        dimension = canonicalizeDimension(Objects.requireNonNull(dimension, "dimension"));
        Objects.requireNonNull(pos1, "pos1");
        Objects.requireNonNull(pos2, "pos2");
        Objects.requireNonNull(tracks, "tracks");

        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid area ID: " + id);
        }

        tracks = List.copyOf(tracks);
        if (tracks.isEmpty() || tracks.size() > MAX_TRACKS) {
            throw new IllegalArgumentException("Area must contain between 1 and " + MAX_TRACKS + " tracks");
        }
    }

    public static AreaDefinition create(
            String id,
            String dimension,
            AreaPosition pos1,
            AreaPosition pos2,
            List<AreaTrackDefinition> tracks,
            boolean resumeOnReenter,
            int priority
    ) {
        return new AreaDefinition(id, dimension, pos1, pos2, tracks, resumeOnReenter, priority);
    }

    public AreaPosition min() {
        return new AreaPosition(
                Math.min(pos1.x(), pos2.x()),
                Math.min(pos1.y(), pos2.y()),
                Math.min(pos1.z(), pos2.z())
        );
    }

    public AreaPosition max() {
        return new AreaPosition(
                Math.max(pos1.x(), pos2.x()),
                Math.max(pos1.y(), pos2.y()),
                Math.max(pos1.z(), pos2.z())
        );
    }

    public boolean contains(String candidateDimension, AreaPosition position) {
        String canonicalCandidateDimension = canonicalizeDimension(
                Objects.requireNonNull(candidateDimension, "candidateDimension")
        );
        Objects.requireNonNull(position, "position");
        return containsCanonical(canonicalCandidateDimension, position);
    }

    boolean containsCanonical(String canonicalDimension, AreaPosition position) {
        if (!dimension.equals(canonicalDimension)) {
            return false;
        }
        return position.x() >= Math.min(pos1.x(), pos2.x())
                && position.x() <= Math.max(pos1.x(), pos2.x())
                && position.y() >= Math.min(pos1.y(), pos2.y())
                && position.y() <= Math.max(pos1.y(), pos2.y())
                && position.z() >= Math.min(pos1.z(), pos2.z())
                && position.z() <= Math.max(pos1.z(), pos2.z());
    }

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

    static String canonicalizeDimension(String dimension) {
        int separator = dimension.indexOf(':');
        String namespace = separator <= 0 ? "minecraft" : dimension.substring(0, separator);
        String path = separator < 0 ? dimension : dimension.substring(separator + 1);
        if (separator != dimension.lastIndexOf(':')
                || !DIMENSION_NAMESPACE_PATTERN.matcher(namespace).matches()
                || !DIMENSION_PATH_PATTERN.matcher(path).matches()) {
            throw new IllegalArgumentException("Invalid dimension: " + dimension);
        }
        return namespace + ":" + path;
    }
}
