package datura.areamusic.ui;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;

import java.util.OptionalInt;

/** What the editor panel needs from the game it runs in. */
public interface PanelHost {
    /** Looks up a translation; {@code args} fill {@code %s} placeholders. */
    String text(String key, Object... args);

    /** Dimension the player is in, e.g. {@code minecraft:overworld}. */
    String playerDimension();

    AreaPosition playerPosition();

    /** The lowest block of the player's dimension that can be built on. */
    default int minBuildY() {
        return Integer.MIN_VALUE;
    }

    /** The highest block of the player's dimension that can be built on. */
    default int maxBuildY() {
        return Integer.MAX_VALUE;
    }

    void teleportTo(String areaId);

    /** Leaves the panel so the two corners of a new area can be clicked in the world. */
    void pickCornersInWorld();

    /** Ends editing: closes the panel and hides the area boxes. */
    void finishEditing();

    /**
     * Whether the panel has a close button of its own. False when it lives inside someone else's
     * window, such as Axiom's tool options, which the panel has no business closing.
     */
    default boolean closable() {
        return true;
    }

    /** How long a track is in milliseconds, or empty while that is not known (yet). */
    default OptionalInt trackDurationMs(String musicId) {
        return OptionalInt.empty();
    }

    /** Whether the area can be auditioned right now: its music is here and there is sound. */
    default boolean canPreview(AreaDefinition area) {
        return false;
    }

    /** Plays the area as a player walking in would hear it, wherever the player is standing. */
    default void startPreview(AreaDefinition area) {
    }

    default void stopPreview() {
    }

    default boolean previewing() {
        return false;
    }

    /** A line of help shown at the bottom of the panel, or an empty string for none. */
    default String footerHint() {
        return "";
    }

    /** Whether corners are picked with the crosshair (true) or a free cursor such as Axiom's. */
    boolean crosshairPicking();
}
