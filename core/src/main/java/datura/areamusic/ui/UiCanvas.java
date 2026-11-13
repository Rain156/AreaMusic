package datura.areamusic.ui;

/** The few drawing calls {@link CanvasUi} needs; each Minecraft version implements them once. */
public interface UiCanvas {
    void fill(int x, int y, int width, int height, int argb);

    void text(String text, int x, int y, int argb);

    int textWidth(String text);

    /** Height of one line of text in the same units as everything else. */
    int lineHeight();

    /** Restricts drawing to a rectangle until {@link #unclip()}. Not nested. */
    void clip(int x, int y, int width, int height);

    void unclip();

    /** Clipboard text, or an empty string. */
    String clipboard();
}
