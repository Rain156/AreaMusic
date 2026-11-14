package datura.areamusic.ui;

import java.util.List;

/**
 * The handful of widgets the editor panel is made of, in immediate-mode style: call these every
 * frame, top to bottom. {@link EditorPanel} is written once against this interface; one
 * implementation draws with Minecraft's own GUI and another forwards to Axiom's ImGui.
 *
 * <p>Edit widgets return the new value at the moment the user commits it (releasing a slider,
 * pressing Enter or leaving a text field) and {@code null} otherwise.
 */
public interface PanelUi {
    /**
     * The title row, with a few small actions at its right edge.
     *
     * @return the index of the action clicked this frame, or -1
     */
    int header(String title, List<String> actions);

    /** A quiet label that starts a group of controls. */
    void section(String text);

    /** Normal wrapped text. */
    void label(String text);

    /** Quieter text for guidance. */
    void hint(String text);

    /** A message that should be noticed: something went wrong, or something just finished. */
    void notice(String text, boolean error);

    /** Extra vertical room between groups. */
    void space();

    /** Puts the next widget on the same row as the previous one. */
    void sameRow();

    /** The one emphasized action on screen; spans the full width. */
    boolean primaryButton(String id, String label);

    boolean button(String id, String label);

    /** A text-only button for secondary actions. */
    boolean quietButton(String id, String label);

    /** A text-only button for an action that removes something. */
    boolean dangerButton(String id, String label);

    /**
     * A row in a list.
     *
     * @param meta   short secondary text at the right edge, or an empty string
     * @param swatch an RGB color shown as a dot, or -1 for none
     */
    boolean listRow(String id, String label, String meta, boolean selected, int swatch);

    /** Progress through a short sequence of steps; {@code current} is zero-based. */
    void steps(List<String> labels, int current);

    String textInput(String id, String label, String value, int maxLength);

    Integer intInput(String id, String label, int value, int min, int max);

    /** {@code format} is a {@link String#format} pattern for the value, e.g. {@code "%.0f%%"}. */
    Float slider(String id, String label, float value, float min, float max, String format);

    /**
     * A position in a track, dragged like the progress bar of a music player and shown as a
     * time. Returns the new position in milliseconds when the drag ends, otherwise null.
     */
    Integer timeline(String id, String label, int valueMs, int durationMs);

    Boolean checkbox(String id, String label, boolean value);

    /** A few mutually exclusive options shown side by side. Returns the newly chosen index. */
    Integer choice(String id, List<String> options, int selected);

    /** A searchable drop-down. Returns the newly chosen option. */
    String combo(String id, String label, List<String> options, String selected);

    /** A row of color dots; -1 in {@code colors} is shown as "auto". Returns the chosen color. */
    Integer swatches(String id, List<Integer> colors, int selected);

    /** A foldable section. Returns whether its content should be built this frame. */
    boolean collapsing(String id, String label, boolean openByDefault);

    void progress(String label, float fraction);
}
