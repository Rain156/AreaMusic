package datura.areamusic.area;

import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaybackDefinition;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaylistLoopPlayback;

import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record AreaDefinition(
        String id,
        String dimension,
        AreaPosition pos1,
        AreaPosition pos2,
        PlaybackDefinition playback,
        boolean resumeOnReenter,
        int priority,
        String displayName,
        int color,
        boolean enabled,
        int startOffsetMs
) {
    public static final int MAX_TRACKS = 16;
    /** Six hours: far longer than any track, and small enough that frame counts stay exact. */
    public static final int MAX_START_OFFSET_MS = 6 * 60 * 60 * 1000;
    public static final int AUTO_COLOR = -1;
    public static final int MAX_DISPLAY_NAME_LENGTH = 64;
    public static final Pattern ID_PATTERN = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern DIMENSION_NAMESPACE_PATTERN = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern DIMENSION_PATH_PATTERN = Pattern.compile("[a-z0-9/._-]+");

    public AreaDefinition {
        Objects.requireNonNull(id, "id");
        dimension = canonicalizeDimension(Objects.requireNonNull(dimension, "dimension"));
        Objects.requireNonNull(pos1, "pos1");
        Objects.requireNonNull(pos2, "pos2");
        Objects.requireNonNull(playback, "playback");

        if (!ID_PATTERN.matcher(id).matches()) {
            throw new IllegalArgumentException("Invalid area ID: " + id);
        }
        displayName = Objects.requireNonNull(displayName, "displayName").strip();
        if (displayName.codePointCount(0, displayName.length()) > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters"
            );
        }
        if (displayName.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("displayName must not contain control characters");
        }
        if (color != AUTO_COLOR && (color < 0 || color > 0xFFFFFF)) {
            throw new IllegalArgumentException("color must be AUTO_COLOR or an RGB value");
        }
        if (startOffsetMs < 0 || startOffsetMs > MAX_START_OFFSET_MS) {
            throw new IllegalArgumentException(
                    "startOffsetMs must be between 0 and " + MAX_START_OFFSET_MS
            );
        }
    }

    public AreaDefinition(
            String id,
            String dimension,
            AreaPosition pos1,
            AreaPosition pos2,
            PlaybackDefinition playback,
            boolean resumeOnReenter,
            int priority,
            String displayName,
            int color,
            boolean enabled
    ) {
        this(id, dimension, pos1, pos2, playback, resumeOnReenter, priority, displayName, color, enabled, 0);
    }

    public AreaDefinition(
            String id,
            String dimension,
            AreaPosition pos1,
            AreaPosition pos2,
            PlaybackDefinition playback,
            boolean resumeOnReenter,
            int priority
    ) {
        this(id, dimension, pos1, pos2, playback, resumeOnReenter, priority, "", AUTO_COLOR, true, 0);
    }

    public String label() {
        return displayName.isEmpty() ? id : displayName;
    }

    public int effectiveColor() {
        return color == AUTO_COLOR ? AreaPalette.colorFor(id) : color;
    }

    public AreaDefinition withBounds(AreaPosition newPos1, AreaPosition newPos2) {
        return new AreaDefinition(
                id, dimension, newPos1, newPos2, playback, resumeOnReenter, priority,
                displayName, color, enabled, startOffsetMs
        );
    }

    public AreaDefinition withPlayback(PlaybackDefinition newPlayback) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, newPlayback, resumeOnReenter, priority,
                displayName, color, enabled, startOffsetMs
        );
    }

    public AreaDefinition withDisplayName(String newDisplayName) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, playback, resumeOnReenter, priority,
                newDisplayName, color, enabled, startOffsetMs
        );
    }

    public AreaDefinition withColor(int newColor) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, playback, resumeOnReenter, priority,
                displayName, newColor, enabled, startOffsetMs
        );
    }

    public AreaDefinition withEnabled(boolean newEnabled) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, playback, resumeOnReenter, priority,
                displayName, color, newEnabled, startOffsetMs
        );
    }

    public AreaDefinition withPriority(int newPriority) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, playback, resumeOnReenter, newPriority,
                displayName, color, enabled, startOffsetMs
        );
    }

    /**
     * Where in the music playback begins when a player walks in, in milliseconds. After that the
     * music loops from its beginning as usual. With {@link #resumeOnReenter} a player coming back
     * continues where they left off instead.
     */
    public AreaDefinition withStartOffsetMs(int newStartOffsetMs) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, playback, resumeOnReenter, priority,
                displayName, color, enabled, newStartOffsetMs
        );
    }

    public AreaDefinition withResumeOnReenter(boolean newResumeOnReenter) {
        return new AreaDefinition(
                id, dimension, pos1, pos2, playback, newResumeOnReenter, priority,
                displayName, color, enabled, startOffsetMs
        );
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
        return new AreaDefinition(
                id, dimension, pos1, pos2,
                new ParallelPlayback(tracks), resumeOnReenter, priority
        );
    }

    public static AreaDefinition createPlaylistLoop(
            String id,
            String dimension,
            AreaPosition pos1,
            AreaPosition pos2,
            List<String> playlist,
            float volume,
            int fadeInMs,
            int fadeOutMs,
            boolean resumeOnReenter,
            int priority
    ) {
        return new AreaDefinition(
                id, dimension, pos1, pos2,
                new PlaylistLoopPlayback(playlist, volume, fadeInMs, fadeOutMs),
                resumeOnReenter, priority
        );
    }

    public PlaybackMode playbackMode() {
        return playback.mode();
    }

    public List<AreaTrackDefinition> tracks() {
        return playback instanceof ParallelPlayback parallel ? parallel.tracks() : List.of();
    }

    public List<String> musicIds() {
        return playback.musicIds();
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
