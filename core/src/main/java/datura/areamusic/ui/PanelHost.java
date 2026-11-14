package datura.areamusic.ui;

import datura.areamusic.area.AreaPosition;

/** What the editor panel needs from the game it runs in. */
public interface PanelHost {
    /** Looks up a translation; {@code args} fill {@code %s} placeholders. */
    String text(String key, Object... args);

    /** Dimension the player is in, e.g. {@code minecraft:overworld}. */
    String playerDimension();

    AreaPosition playerPosition();

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

    /** A line of help shown at the bottom of the panel, or an empty string for none. */
    default String footerHint() {
        return "";
    }

    /** Whether corners are picked with the crosshair (true) or a free cursor such as Axiom's. */
    boolean crosshairPicking();
}
