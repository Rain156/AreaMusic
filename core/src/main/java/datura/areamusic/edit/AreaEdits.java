package datura.areamusic.edit;

import datura.areamusic.area.AreaBox;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaylistLoopPlayback;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.UnaryOperator;

/**
 * The everyday edits shared by commands and editor panels. Each returns a new area; an edit that
 * cannot be made throws {@link IllegalArgumentException} with a message fit to show the user.
 */
public final class AreaEdits {
    public static final float DEFAULT_VOLUME = 1.0f;
    public static final int DEFAULT_FADE_MS = 2000;

    private AreaEdits() {
    }

    /** How an area plays, in the three terms the editor shows. */
    public enum SimpleMode {
        /** One track on repeat. */
        LOOP("loop"),
        /** Tracks one after another, repeating. */
        PLAYLIST("playlist"),
        /** Several tracks at once. */
        LAYERS("layers");

        private final String id;

        SimpleMode(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        public static SimpleMode fromId(String id) {
            return Arrays.stream(values())
                    .filter(mode -> mode.id.equals(id))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown mode: " + id));
        }
    }

    /** A new area with one looping track and every default. */
    public static AreaDefinition single(
            String id, String dimension, AreaPosition pos1, AreaPosition pos2, String musicId
    ) {
        return AreaDefinition.create(
                id, dimension, pos1, pos2,
                List.of(track(musicId, DEFAULT_VOLUME, DEFAULT_FADE_MS, DEFAULT_FADE_MS)),
                false, 0
        );
    }

    public static SimpleMode modeOf(AreaDefinition area) {
        if (area.playback() instanceof PlaylistLoopPlayback) {
            return SimpleMode.PLAYLIST;
        }
        return area.tracks().size() == 1 ? SimpleMode.LOOP : SimpleMode.LAYERS;
    }

    /** The playlist volume, or the first track's volume. */
    public static float volumeOf(AreaDefinition area) {
        return area.playback() instanceof PlaylistLoopPlayback playlist
                ? playlist.volume()
                : area.tracks().get(0).volume();
    }

    public static int fadeInOf(AreaDefinition area) {
        return area.playback() instanceof PlaylistLoopPlayback playlist
                ? playlist.fadeInMs()
                : area.tracks().get(0).fadeInMs();
    }

    public static int fadeOutOf(AreaDefinition area) {
        return area.playback() instanceof PlaylistLoopPlayback playlist
                ? playlist.fadeOutMs()
                : area.tracks().get(0).fadeOutMs();
    }

    /** Sets the playlist volume, or the volume of every track. */
    public static AreaDefinition withVolume(AreaDefinition area, float volume) {
        if (area.playback() instanceof PlaylistLoopPlayback playlist) {
            return area.withPlayback(playlist.withVolume(volume));
        }
        return mapTracks(area, track -> new AreaTrackDefinition(
                track.musicId(), track.delaySeconds(), volume, track.loop(),
                track.fadeInMs(), track.fadeOutMs()));
    }

    public static AreaDefinition withFadeIn(AreaDefinition area, int fadeInMs) {
        if (area.playback() instanceof PlaylistLoopPlayback playlist) {
            return area.withPlayback(playlist.withFades(fadeInMs, playlist.fadeOutMs()));
        }
        return mapTracks(area, track -> new AreaTrackDefinition(
                track.musicId(), track.delaySeconds(), track.volume(), track.loop(),
                fadeInMs, track.fadeOutMs()));
    }

    public static AreaDefinition withFadeOut(AreaDefinition area, int fadeOutMs) {
        if (area.playback() instanceof PlaylistLoopPlayback playlist) {
            return area.withPlayback(playlist.withFades(playlist.fadeInMs(), fadeOutMs));
        }
        return mapTracks(area, track -> new AreaTrackDefinition(
                track.musicId(), track.delaySeconds(), track.volume(), track.loop(),
                track.fadeInMs(), fadeOutMs));
    }

    /**
     * Converts between the three modes, keeping track order, volume and fades. Converting to
     * {@link SimpleMode#LOOP} keeps only the first track. A one-track area is already LOOP, so
     * converting it to LAYERS changes nothing until a second track is added.
     */
    public static AreaDefinition withMode(AreaDefinition area, SimpleMode mode) {
        Objects.requireNonNull(mode, "mode");
        if (modeOf(area) == mode) {
            return area;
        }
        List<String> musicIds = area.musicIds();
        float volume = volumeOf(area);
        int fadeIn = fadeInOf(area);
        int fadeOut = fadeOutOf(area);
        return switch (mode) {
            case PLAYLIST -> area.withPlayback(
                    new PlaylistLoopPlayback(musicIds, volume, fadeIn, fadeOut));
            case LOOP -> area.withPlayback(new ParallelPlayback(
                    List.of(track(musicIds.get(0), volume, fadeIn, fadeOut))));
            case LAYERS -> {
                if (area.playback() instanceof ParallelPlayback) {
                    yield area;
                }
                requireLayerCount(musicIds.size());
                yield area.withPlayback(new ParallelPlayback(musicIds.stream()
                        .map(musicId -> track(musicId, volume, fadeIn, fadeOut))
                        .toList()));
            }
        };
    }

    /** Replaces everything the area plays with one looping track, keeping volume and fades. */
    public static AreaDefinition withOnlyMusic(AreaDefinition area, String musicId) {
        return area.withPlayback(new ParallelPlayback(
                List.of(track(musicId, volumeOf(area), fadeInOf(area), fadeOutOf(area)))));
    }

    /**
     * Adds a track: appended to a playlist, added as another layer to a layered area. Adding to a
     * single looping track turns the area into a two-entry playlist.
     */
    public static AreaDefinition addMusic(AreaDefinition area, String musicId) {
        Objects.requireNonNull(musicId, "musicId");
        float volume = volumeOf(area);
        int fadeIn = fadeInOf(area);
        int fadeOut = fadeOutOf(area);
        List<String> musicIds = new ArrayList<>(area.musicIds());
        musicIds.add(musicId);
        if (modeOf(area) == SimpleMode.LAYERS) {
            requireLayerCount(musicIds.size());
            List<AreaTrackDefinition> tracks = new ArrayList<>(area.tracks());
            tracks.add(track(musicId, volume, fadeIn, fadeOut));
            return area.withPlayback(new ParallelPlayback(tracks));
        }
        if (musicIds.size() > PlaylistLoopPlayback.MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "A playlist holds at most " + PlaylistLoopPlayback.MAX_ENTRIES + " tracks");
        }
        return area.withPlayback(area.playback() instanceof PlaylistLoopPlayback playlist
                ? playlist.withPlaylist(musicIds)
                : new PlaylistLoopPlayback(musicIds, volume, fadeIn, fadeOut));
    }

    /** Whether a playlist plays in a new random order each round. False for the other modes. */
    public static boolean shuffleOf(AreaDefinition area) {
        return area.playback() instanceof PlaylistLoopPlayback playlist && playlist.shuffle();
    }

    /** The quiet between two playlist tracks in milliseconds. Zero for the other modes. */
    public static int gapOf(AreaDefinition area) {
        return area.playback() instanceof PlaylistLoopPlayback playlist ? playlist.gapMs() : 0;
    }

    public static AreaDefinition withShuffle(AreaDefinition area, boolean shuffle) {
        return area.withPlayback(requirePlaylist(area).withShuffle(shuffle));
    }

    public static AreaDefinition withGap(AreaDefinition area, int gapMs) {
        return area.withPlayback(requirePlaylist(area).withGapMs(gapMs));
    }

    private static PlaylistLoopPlayback requirePlaylist(AreaDefinition area) {
        if (area.playback() instanceof PlaylistLoopPlayback playlist) {
            return playlist;
        }
        throw new IllegalArgumentException("Only a playlist can be shuffled or have gaps");
    }

    /** Adds a box to the area, for a room that is not one cuboid. */
    public static AreaDefinition addBox(AreaDefinition area, AreaPosition pos1, AreaPosition pos2) {
        if (area.wholeDimension()) {
            throw new IllegalArgumentException("This area covers its whole dimension already");
        }
        if (area.extraBoxes().size() >= AreaDefinition.MAX_EXTRA_BOXES) {
            throw new IllegalArgumentException(
                    "An area has at most " + (AreaDefinition.MAX_EXTRA_BOXES + 1) + " boxes");
        }
        List<AreaBox> boxes = new ArrayList<>(area.extraBoxes());
        boxes.add(new AreaBox(pos1, pos2));
        return area.withExtraBoxes(boxes);
    }

    /**
     * Removes a box by its number as shown to the user: 1 is the main box, which goes only when
     * another can take its place.
     */
    public static AreaDefinition removeBox(AreaDefinition area, int number) {
        List<AreaBox> boxes = new ArrayList<>(area.boxes());
        if (number < 1 || number > boxes.size()) {
            throw new IllegalArgumentException("No box number " + number);
        }
        if (boxes.size() == 1) {
            throw new IllegalArgumentException("An area needs at least one box; delete the area instead");
        }
        boxes.remove(number - 1);
        AreaBox main = boxes.remove(0);
        return area.withBounds(main.pos1(), main.pos2()).withExtraBoxes(boxes);
    }

    /** Removes the last occurrence of the track. An area always keeps at least one track. */
    public static AreaDefinition removeMusic(AreaDefinition area, String musicId) {
        Objects.requireNonNull(musicId, "musicId");
        List<String> musicIds = area.musicIds();
        int index = musicIds.lastIndexOf(musicId);
        if (index < 0) {
            throw new IllegalArgumentException("This area does not play " + musicId);
        }
        if (musicIds.size() == 1) {
            throw new IllegalArgumentException(
                    "An area needs at least one track; delete the area instead");
        }
        if (area.playback() instanceof PlaylistLoopPlayback playlist) {
            List<String> remaining = new ArrayList<>(musicIds);
            remaining.remove(index);
            return area.withPlayback(playlist.withPlaylist(remaining));
        }
        List<AreaTrackDefinition> remaining = new ArrayList<>(area.tracks());
        remaining.remove(index);
        return area.withPlayback(new ParallelPlayback(remaining));
    }

    /** Adds a track that plays at the same time as the others, whatever the current mode. */
    public static AreaDefinition addLayer(AreaDefinition area, String musicId) {
        Objects.requireNonNull(musicId, "musicId");
        AreaDefinition layered = area.playback() instanceof ParallelPlayback
                ? area
                : withMode(area, SimpleMode.LAYERS);
        List<AreaTrackDefinition> tracks = new ArrayList<>(layered.tracks());
        requireLayerCount(tracks.size() + 1);
        tracks.add(track(musicId, volumeOf(area), fadeInOf(area), fadeOutOf(area)));
        return area.withPlayback(new ParallelPlayback(tracks));
    }

    /** Removes the track at a position. An area always keeps at least one track. */
    public static AreaDefinition removeMusicAt(AreaDefinition area, int index) {
        List<String> musicIds = area.musicIds();
        if (index < 0 || index >= musicIds.size()) {
            throw new IllegalArgumentException("No track at position " + (index + 1));
        }
        if (musicIds.size() == 1) {
            throw new IllegalArgumentException(
                    "An area needs at least one track; delete the area instead");
        }
        if (area.playback() instanceof PlaylistLoopPlayback playlist) {
            List<String> remaining = new ArrayList<>(musicIds);
            remaining.remove(index);
            return area.withPlayback(playlist.withPlaylist(remaining));
        }
        List<AreaTrackDefinition> remaining = new ArrayList<>(area.tracks());
        remaining.remove(index);
        return area.withPlayback(new ParallelPlayback(remaining));
    }

    /** Replaces one track of a layered area, e.g. to change its own volume or delay. */
    public static AreaDefinition withTrack(AreaDefinition area, int index, AreaTrackDefinition track) {
        List<AreaTrackDefinition> tracks = new ArrayList<>(area.tracks());
        if (index < 0 || index >= tracks.size()) {
            throw new IllegalArgumentException("No track at position " + (index + 1));
        }
        tracks.set(index, Objects.requireNonNull(track, "track"));
        return area.withPlayback(new ParallelPlayback(tracks));
    }

    /**
     * Moves every face outwards by {@code blocks}, or inwards when negative. Shrinking stops at a
     * single block per axis. Which corner is pos1 and which is pos2 does not change.
     */
    public static AreaDefinition grow(AreaDefinition area, int blocks) {
        AreaPosition pos1 = area.pos1();
        AreaPosition pos2 = area.pos2();
        int[] x = grow(pos1.x(), pos2.x(), blocks);
        int[] y = grow(pos1.y(), pos2.y(), blocks);
        int[] z = grow(pos1.z(), pos2.z(), blocks);
        return area.withBounds(
                new AreaPosition(x[0], y[0], z[0]), new AreaPosition(x[1], y[1], z[1]));
    }

    private static int[] grow(int first, int second, int blocks) {
        int shrinkLimit = Math.abs(first - second) / 2;
        int amount = Math.max(blocks, -shrinkLimit);
        return first <= second
                ? new int[]{first - amount, second + amount}
                : new int[]{first + amount, second - amount};
    }

    private static AreaDefinition mapTracks(
            AreaDefinition area, UnaryOperator<AreaTrackDefinition> change
    ) {
        return area.withPlayback(new ParallelPlayback(
                area.tracks().stream().map(change).toList()));
    }

    private static AreaTrackDefinition track(String musicId, float volume, int fadeIn, int fadeOut) {
        return new AreaTrackDefinition(musicId, 0, volume, true, fadeIn, fadeOut);
    }

    private static void requireLayerCount(int count) {
        if (count > AreaDefinition.MAX_TRACKS) {
            throw new IllegalArgumentException(
                    "Layers hold at most " + AreaDefinition.MAX_TRACKS + " tracks");
        }
    }
}
