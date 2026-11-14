package datura.areamusic.area;

import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaybackDefinition;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaylistLoopPlayback;

import java.util.ArrayList;
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
        int startOffsetMs,
        AreaCondition condition,
        boolean wholeDimension,
        boolean announce,
        List<AreaBox> extraBoxes
) {
    public static final int MAX_TRACKS = 16;
    /** Six hours: far longer than any track, and small enough that frame counts stay exact. */
    public static final int MAX_START_OFFSET_MS = 6 * 60 * 60 * 1000;
    /** An area is its main box plus up to this many more. */
    public static final int MAX_EXTRA_BOXES = 15;
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
        Objects.requireNonNull(condition, "condition");
        extraBoxes = List.copyOf(Objects.requireNonNull(extraBoxes, "extraBoxes"));

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
        if (extraBoxes.size() > MAX_EXTRA_BOXES) {
            throw new IllegalArgumentException(
                    "An area has at most " + (MAX_EXTRA_BOXES + 1) + " boxes"
            );
        }
        if (wholeDimension && !extraBoxes.isEmpty()) {
            throw new IllegalArgumentException("An area that covers its whole dimension has no boxes");
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
            boolean enabled,
            int startOffsetMs
    ) {
        this(
                id, dimension, pos1, pos2, playback, resumeOnReenter, priority, displayName, color,
                enabled, startOffsetMs, AreaCondition.ALWAYS, false, true, List.of()
        );
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

    /** Every box of the area, the main one first. Empty for an area that covers its dimension. */
    public List<AreaBox> boxes() {
        if (wholeDimension) {
            return List.of();
        }
        List<AreaBox> boxes = new ArrayList<>(extraBoxes.size() + 1);
        boxes.add(new AreaBox(pos1, pos2));
        boxes.addAll(extraBoxes);
        return List.copyOf(boxes);
    }

    public AreaDefinition withId(String newId) {
        return edit(draft -> draft.id = newId);
    }

    public AreaDefinition withBounds(AreaPosition newPos1, AreaPosition newPos2) {
        return edit(draft -> {
            draft.pos1 = newPos1;
            draft.pos2 = newPos2;
        });
    }

    public AreaDefinition withPlayback(PlaybackDefinition newPlayback) {
        return edit(draft -> draft.playback = newPlayback);
    }

    public AreaDefinition withDisplayName(String newDisplayName) {
        return edit(draft -> draft.displayName = newDisplayName);
    }

    public AreaDefinition withColor(int newColor) {
        return edit(draft -> draft.color = newColor);
    }

    public AreaDefinition withEnabled(boolean newEnabled) {
        return edit(draft -> draft.enabled = newEnabled);
    }

    public AreaDefinition withPriority(int newPriority) {
        return edit(draft -> draft.priority = newPriority);
    }

    /**
     * Where in the music playback begins when a player walks in, in milliseconds. After that the
     * music loops from its beginning as usual. With {@link #resumeOnReenter} a player coming back
     * continues where they left off instead.
     */
    public AreaDefinition withStartOffsetMs(int newStartOffsetMs) {
        return edit(draft -> draft.startOffsetMs = newStartOffsetMs);
    }

    public AreaDefinition withResumeOnReenter(boolean newResumeOnReenter) {
        return edit(draft -> draft.resumeOnReenter = newResumeOnReenter);
    }

    /** At what time of day and in what weather the area plays. */
    public AreaDefinition withCondition(AreaCondition newCondition) {
        return edit(draft -> draft.condition = newCondition);
    }

    /**
     * Makes the area cover its whole dimension, as background music wherever no ordinary area
     * plays. Such an area has no boxes; its corners are kept but mean nothing.
     */
    public AreaDefinition withWholeDimension(boolean newWholeDimension) {
        return edit(draft -> {
            draft.wholeDimension = newWholeDimension;
            if (newWholeDimension) {
                draft.extraBoxes = List.of();
            }
        });
    }

    /** Whether the area's name is shown to a player who walks in. Only named areas say anything. */
    public AreaDefinition withAnnounce(boolean newAnnounce) {
        return edit(draft -> draft.announce = newAnnounce);
    }

    /** Further boxes that belong to the area, for rooms that are not one cuboid. */
    public AreaDefinition withExtraBoxes(List<AreaBox> newExtraBoxes) {
        return edit(draft -> draft.extraBoxes = newExtraBoxes);
    }

    private AreaDefinition edit(java.util.function.Consumer<Draft> change) {
        Draft draft = new Draft(this);
        change.accept(draft);
        return new AreaDefinition(
                draft.id, dimension, draft.pos1, draft.pos2, draft.playback, draft.resumeOnReenter,
                draft.priority, draft.displayName, draft.color, draft.enabled, draft.startOffsetMs,
                draft.condition, draft.wholeDimension, draft.announce, draft.extraBoxes
        );
    }

    /** The fields that can be changed one at a time, so each change is written once. */
    private static final class Draft {
        private String id;
        private AreaPosition pos1;
        private AreaPosition pos2;
        private PlaybackDefinition playback;
        private boolean resumeOnReenter;
        private int priority;
        private String displayName;
        private int color;
        private boolean enabled;
        private int startOffsetMs;
        private AreaCondition condition;
        private boolean wholeDimension;
        private boolean announce;
        private List<AreaBox> extraBoxes;

        private Draft(AreaDefinition area) {
            id = area.id;
            pos1 = area.pos1;
            pos2 = area.pos2;
            playback = area.playback;
            resumeOnReenter = area.resumeOnReenter;
            priority = area.priority;
            displayName = area.displayName;
            color = area.color;
            enabled = area.enabled;
            startOffsetMs = area.startOffsetMs;
            condition = area.condition;
            wholeDimension = area.wholeDimension;
            announce = area.announce;
            extraBoxes = area.extraBoxes;
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
        if (wholeDimension) {
            return true;
        }
        if (new AreaBox(pos1, pos2).contains(position)) {
            return true;
        }
        for (AreaBox box : extraBoxes) {
            if (box.contains(position)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Blocks in all boxes together, counting overlaps twice. Among areas of equal priority the
     * smaller one plays, so an area that covers its dimension counts as the largest there is.
     */
    public long volumeInBlocks() {
        if (wholeDimension) {
            return Long.MAX_VALUE;
        }
        long total = new AreaBox(pos1, pos2).volumeInBlocks();
        for (AreaBox box : extraBoxes) {
            long volume = box.volumeInBlocks();
            total = Long.MAX_VALUE - total < volume ? Long.MAX_VALUE : total + volume;
        }
        return total;
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
