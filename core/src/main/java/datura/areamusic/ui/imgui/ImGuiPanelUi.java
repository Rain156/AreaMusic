package datura.areamusic.ui.imgui;

import datura.areamusic.edit.TrackTime;
import datura.areamusic.ui.PanelUi;
import imgui.ImGui;
import imgui.flag.ImGuiCol;
import imgui.flag.ImGuiTreeNodeFlags;
import imgui.type.ImBoolean;
import imgui.type.ImInt;
import imgui.type.ImString;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * {@link PanelUi} on Dear ImGui, for the tool options window of Axiom. Only the oldest, most
 * stable ImGui calls are used, because the ImGui build is whichever one Axiom ships.
 *
 * <p>This class must only be loaded when ImGui is present, i.e. when Axiom is installed.
 */
public final class ImGuiPanelUi implements PanelUi {
    private static final int TEXT_MUTED = 0xFF9A9A9A;
    private static final int TEXT_ERROR = 0xFF8080FF;
    private static final int TEXT_OK = 0xFFB8E38F;
    private static final int BUTTON_PRIMARY = 0xFFAB6C2F;

    private final Map<String, ImString> texts = new HashMap<>();
    private final Map<String, ImInt> ints = new HashMap<>();
    private final Map<String, float[]> floats = new HashMap<>();
    private final ImBoolean flag = new ImBoolean();

    @Override
    public int header(String title, List<String> actions) {
        // Axiom's own window already shows the tool name, so only the actions are drawn.
        int clicked = -1;
        for (int index = 0; index < actions.size(); index++) {
            if (index > 0) {
                ImGui.sameLine();
            }
            if (ImGui.smallButton(actions.get(index) + "##header" + index)) {
                clicked = index;
            }
        }
        ImGui.separator();
        return clicked;
    }

    @Override
    public void section(String text) {
        ImGui.spacing();
        ImGui.pushStyleColor(ImGuiCol.Text, TEXT_MUTED);
        ImGui.text(text);
        ImGui.popStyleColor();
    }

    @Override
    public void label(String text) {
        ImGui.textWrapped(text);
    }

    @Override
    public void hint(String text) {
        ImGui.pushStyleColor(ImGuiCol.Text, TEXT_MUTED);
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }

    @Override
    public void notice(String text, boolean error) {
        ImGui.pushStyleColor(ImGuiCol.Text, error ? TEXT_ERROR : TEXT_OK);
        ImGui.textWrapped(text);
        ImGui.popStyleColor();
    }

    @Override
    public void space() {
        ImGui.spacing();
    }

    @Override
    public void sameRow() {
        ImGui.sameLine();
    }

    @Override
    public boolean primaryButton(String id, String label) {
        ImGui.pushStyleColor(ImGuiCol.Button, BUTTON_PRIMARY);
        boolean clicked = ImGui.button(label + "##" + id);
        ImGui.popStyleColor();
        return clicked;
    }

    @Override
    public boolean button(String id, String label) {
        return ImGui.button(label + "##" + id);
    }

    @Override
    public boolean quietButton(String id, String label) {
        return ImGui.smallButton(label + "##" + id);
    }

    @Override
    public boolean dangerButton(String id, String label) {
        ImGui.pushStyleColor(ImGuiCol.Text, TEXT_ERROR);
        boolean clicked = ImGui.smallButton(label + "##" + id);
        ImGui.popStyleColor();
        return clicked;
    }

    @Override
    public boolean listRow(String id, String label, String meta, boolean selected, int swatch) {
        if (swatch >= 0) {
            swatch("##swatch_" + id, swatch);
            ImGui.sameLine();
        }
        String text = meta.isEmpty() ? label : label + "    " + meta;
        return ImGui.selectable(text + "##" + id, selected);
    }

    @Override
    public void steps(List<String> labels, int current) {
        StringBuilder line = new StringBuilder();
        for (int index = 0; index < labels.size(); index++) {
            if (index > 0) {
                line.append("  >  ");
            }
            line.append(index == current ? "[" + labels.get(index) + "]" : labels.get(index));
        }
        ImGui.text(line.toString());
    }

    @Override
    public String textInput(String id, String label, String value, int maxLength) {
        ImString buffer = texts.computeIfAbsent(id, ignored -> new ImString(maxLength * 4 + 8));
        boolean changed = ImGui.inputText(label + "##" + id, buffer);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            String typed = buffer.get();
            int end = typed.offsetByCodePoints(0, Math.min(maxLength, typed.codePointCount(0, typed.length())));
            String result = typed.substring(0, end);
            return result.equals(value) ? null : result;
        }
        if (!changed && !ImGui.isItemActive() && !buffer.get().equals(value)) {
            buffer.set(value);
        }
        return null;
    }

    @Override
    public Integer intInput(String id, String label, int value, int min, int max) {
        ImInt buffer = ints.computeIfAbsent(id, ignored -> new ImInt(value));
        boolean changed = ImGui.inputInt(label + "##" + id, buffer);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            int result = Math.max(min, Math.min(max, buffer.get()));
            buffer.set(result);
            return result == value ? null : result;
        }
        if (!changed && !ImGui.isItemActive() && buffer.get() != value) {
            buffer.set(value);
        }
        return null;
    }

    @Override
    public Float slider(String id, String label, float value, float min, float max, String format) {
        float[] buffer = floats.computeIfAbsent(id, ignored -> new float[]{value});
        boolean changed = ImGui.sliderFloat(label + "##" + id, buffer, min, max, format);
        if (ImGui.isItemDeactivatedAfterEdit()) {
            return buffer[0] == value ? null : buffer[0];
        }
        if (!changed && !ImGui.isItemActive()) {
            buffer[0] = value;
        }
        return null;
    }

    @Override
    public Integer timeline(String id, String label, int valueMs, int durationMs) {
        float[] buffer = floats.computeIfAbsent(id, ignored -> new float[]{valueMs / 1000.0f});
        float seconds = Math.max(0.1f, durationMs / 1000.0f);
        boolean changed = ImGui.sliderFloat(
                label + "##" + id, buffer, 0.0f, seconds,
                TrackTime.format(Math.round(buffer[0]) * 1000));
        if (ImGui.isItemDeactivatedAfterEdit()) {
            int picked = Math.max(0, Math.min(durationMs - 1, Math.round(buffer[0]) * 1000));
            return picked == valueMs ? null : picked;
        }
        if (!changed && !ImGui.isItemActive()) {
            buffer[0] = valueMs / 1000.0f;
        }
        return null;
    }

    @Override
    public Boolean checkbox(String id, String label, boolean value) {
        flag.set(value);
        return ImGui.checkbox(label + "##" + id, flag) ? flag.get() : null;
    }

    @Override
    public Integer choice(String id, List<String> options, int selected) {
        Integer picked = null;
        for (int index = 0; index < options.size(); index++) {
            if (index > 0) {
                ImGui.sameLine();
            }
            if (ImGui.radioButton(options.get(index) + "##" + id + index, index == selected) && index != selected) {
                picked = index;
            }
        }
        return picked;
    }

    @Override
    public String combo(String id, String label, List<String> options, String selected) {
        String result = null;
        if (ImGui.beginCombo(label + "##" + id, selected == null ? "…" : selected)) {
            for (int index = 0; index < options.size(); index++) {
                String option = options.get(index);
                if (ImGui.selectable(option + "##" + id + index, option.equals(selected)) && !option.equals(selected)) {
                    result = option;
                }
            }
            ImGui.endCombo();
        }
        return result;
    }

    @Override
    public Integer swatches(String id, List<Integer> colors, int selected) {
        Integer picked = null;
        for (int index = 0; index < colors.size(); index++) {
            int color = colors.get(index);
            if (index > 0) {
                ImGui.sameLine();
            }
            boolean clicked = color < 0
                    ? ImGui.button((selected < 0 ? "[A]" : "A") + "##" + id + index)
                    : swatch((color == selected ? "*" : "") + "##" + id + index, color);
            if (clicked && color != selected) {
                picked = color;
            }
        }
        return picked;
    }

    @Override
    public boolean collapsing(String id, String label, boolean openByDefault) {
        return ImGui.collapsingHeader(label + "##" + id, openByDefault ? ImGuiTreeNodeFlags.DefaultOpen : 0);
    }

    @Override
    public void progress(String label, float fraction) {
        ImGui.progressBar(Math.max(0.0f, Math.min(1.0f, fraction)), -1.0f, 0.0f, label);
    }

    /** A small button filled with an RGB color. ImGui packs colors as ABGR. */
    private static boolean swatch(String label, int rgb) {
        int abgr = 0xFF000000 | (rgb & 0xFF) << 16 | rgb & 0xFF00 | rgb >> 16 & 0xFF;
        ImGui.pushStyleColor(ImGuiCol.Button, abgr);
        ImGui.pushStyleColor(ImGuiCol.ButtonHovered, abgr);
        ImGui.pushStyleColor(ImGuiCol.ButtonActive, abgr);
        boolean clicked = ImGui.button(label, 18.0f, 18.0f);
        ImGui.popStyleColor(3);
        return clicked;
    }
}
