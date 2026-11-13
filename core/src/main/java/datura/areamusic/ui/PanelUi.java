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
    void heading(String text);

    /** Normal wrapped text. */
    void label(String text);

    /** Quieter, smaller-feeling text for guidance. */
    void hint(String text);

    void error(String text);

    void separator();

    /** Puts the next widget on the same row as the previous one. */
    void sameRow();

    boolean button(String id, String label);

    /** The one emphasized action on screen. */
    boolean primaryButton(String id, String label);

    /** A list row. {@code swatch} is an RGB color shown as a dot, or -1 for none. */
    boolean selectable(String id, String label, boolean selected, int swatch);

    String textInput(String id, String label, String value, int maxLength);

    Integer intInput(String id, String label, int value, int min, int max);

    /** {@code format} is a {@link String#format} pattern for the value, e.g. {@code "%.0f%%"}. */
    Float slider(String id, String label, float value, float min, float max, String format);

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
