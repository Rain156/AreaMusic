package datura.areamusic.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * {@link PanelUi} drawn with plain rectangles and text, so the panel looks and behaves the same on
 * every Minecraft version without depending on vanilla widget classes. State that must survive
 * between frames (focus, open drop-down, scroll) is keyed by widget ID.
 *
 * <p>The look is deliberately quiet: one floating dark card, neutral greys, white for the single
 * primary action and one accent color for whatever is selected or being dragged.
 */
public final class CanvasUi implements PanelUi {
    static final int PADDING = 10;
    private static final int GAP = 5;
    private static final int ROW = 18;
    private static final int LIST_ROW = 20;
    private static final int COMBO_ROW = 16;
    private static final int COMBO_ROWS = 8;
    private static final int SLIDER_VALUE_WIDTH = 34;
    private static final String CLOSING_PUNCTUATION = "，。、；：！？）」』”’,.;:!?)";

    private static final int CARD = 0xF4101317;
    private static final int BORDER = 0xFF2A3038;
    private static final int SURFACE = 0xFF1D2229;
    private static final int SURFACE_HOVER = 0xFF2A313A;
    private static final int FIELD = 0xFF0B0D10;
    private static final int TEXT = 0xFFF1F3F5;
    private static final int SECONDARY = 0xFFA7B0BA;
    private static final int MUTED = 0xFF6B7480;
    private static final int ACCENT = 0xFF5AA2FF;
    private static final int SELECTED = 0xFF1B2836;
    private static final int PRIMARY = 0xFFF1F3F5;
    private static final int PRIMARY_HOVER = 0xFFFFFFFF;
    private static final int PRIMARY_TEXT = 0xFF101317;
    private static final int DANGER = 0xFFFF7B72;
    private static final int DANGER_SURFACE = 0xFF34191C;
    private static final int NOTICE_ERROR = 0xFF34191C;
    private static final int NOTICE_ERROR_TEXT = 0xFFFFA8A1;
    private static final int NOTICE_OK = 0xFF14251D;
    private static final int NOTICE_OK_TEXT = 0xFF8FE3B8;

    private UiCanvas canvas;
    private UiInput input;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int cursorY;
    private int contentHeight;
    private int scroll;
    private boolean pressConsumed;
    private boolean sameRowNext;
    private int lastX;
    private int lastY;
    private int lastWidth;
    private int lastHeight;

    private String focusId;
    private String buffer = "";
    private boolean replaceOnType;
    private int[] focusRect;
    private int[] caret;
    private String committedId;
    private String committedValue;
    private String activeSlider;
    private float sliderValue;
    private String openCombo;
    private String comboFilter = "";
    private int comboScroll;
    private final Map<String, Boolean> open = new HashMap<>();

    /**
     * Starts a frame. The panel is a card at the given position that is only as tall as its
     * content, up to {@code maxHeight}; taller content scrolls.
     */
    public void begin(UiCanvas canvas, UiInput input, int x, int y, int width, int maxHeight) {
        this.canvas = canvas;
        this.input = input;
        panelX = x;
        panelY = y;
        panelWidth = width;
        panelHeight = contentHeight > 0 ? Math.min(maxHeight, contentHeight) : maxHeight;
        pressConsumed = false;
        sameRowNext = false;
        lastWidth = 0;
        committedId = null;
        caret = null;

        if (focusId != null && input.pressed && focusRect != null && !inside(focusRect)) {
            commitFocus();
        }
        if (focusId != null) {
            handleFocusKeys();
        }
        if (containsMouse() && openCombo == null) {
            scroll -= (int) Math.round(input.scroll * ROW * 2);
        }
        scroll = Math.max(0, Math.min(scroll, Math.max(0, contentHeight - panelHeight)));

        round(x, y, width, panelHeight, BORDER);
        round(x + 1, y + 1, width - 2, panelHeight - 2, CARD);
        canvas.clip(x + 1, y + 1, width - 2, panelHeight - 2);
        cursorY = y + PADDING - scroll;
    }

    public void end() {
        contentHeight = cursorY + scroll - panelY + PADDING - GAP;
        canvas.unclip();
        if (contentHeight > panelHeight) {
            int barHeight = Math.max(12, panelHeight * panelHeight / contentHeight);
            int travel = panelHeight - 4 - barHeight;
            int barY = panelY + 2 + (int) ((long) travel * scroll / (contentHeight - panelHeight));
            canvas.fill(panelX + panelWidth - 4, barY, 2, barHeight, BORDER);
        }
        if (input.released) {
            activeSlider = null;
        }
    }

    /** Whether the pointer is over the panel, so the host can leave other clicks to the world. */
    public boolean containsMouse() {
        return input != null
                && input.mouseX >= panelX && input.mouseX < panelX + panelWidth
                && input.mouseY >= panelY && input.mouseY < panelY + panelHeight;
    }

    /** Whether a text field is being edited, so the host does not treat typing as shortcuts. */
    public boolean editingText() {
        return focusId != null;
    }

    /**
     * Where the text caret was drawn this frame, as {@code {x, y, height}}, or null when nothing
     * is being typed into. Hosts use it to place the input method's candidate window.
     */
    public int[] caret() {
        return caret == null ? null : caret.clone();
    }

    // ---- widgets ----

    @Override
    public int header(String title, List<String> actions) {
        int[] row = fullRow(ROW);
        int clicked = -1;
        int right = row[0] + row[2];
        for (int index = actions.size() - 1; index >= 0; index--) {
            String action = actions.get(index);
            int width = canvas.textWidth(action) + 10;
            int[] rect = {right - width, row[1], width, row[3]};
            boolean hover = inside(rect);
            if (hover) {
                round(rect[0], rect[1], rect[2], rect[3], SURFACE_HOVER);
            }
            canvas.text(action, rect[0] + 5, textY(rect), hover ? TEXT : SECONDARY);
            if (pressed(rect)) {
                clicked = index;
            }
            right = rect[0] - 2;
        }
        canvas.text(fit(title, right - row[0] - 4), row[0], textY(row), TEXT);
        canvas.fill(panelX + 1, row[1] + row[3] + GAP, panelWidth - 2, 1, BORDER);
        cursorY += GAP + 1;
        return clicked;
    }

    @Override
    public void section(String text) {
        cursorY += 7;
        int[] rect = fullRow(canvas.lineHeight() + 2);
        canvas.text(fit(text, rect[2]), rect[0], rect[1], MUTED);
    }

    @Override
    public void label(String text) {
        wrapped(text, SECONDARY, true);
    }

    @Override
    public void hint(String text) {
        wrapped(text, MUTED, false);
    }

    @Override
    public void notice(String text, boolean error) {
        int width = panelWidth - PADDING * 2;
        List<String> lines = wrap(text, width - 12);
        int lineHeight = canvas.lineHeight() + 2;
        int[] rect = fullRow(lines.size() * lineHeight + 10);
        round(rect[0], rect[1], rect[2], rect[3], error ? NOTICE_ERROR : NOTICE_OK);
        for (int index = 0; index < lines.size(); index++) {
            canvas.text(lines.get(index), rect[0] + 6, rect[1] + 6 + index * lineHeight,
                    error ? NOTICE_ERROR_TEXT : NOTICE_OK_TEXT);
        }
    }

    @Override
    public void space() {
        sameRowNext = false;
        cursorY += 6;
    }

    @Override
    public void sameRow() {
        sameRowNext = lastWidth > 0;
    }

    @Override
    public boolean primaryButton(String id, String label) {
        int[] rect = fullRow(ROW + 4);
        round(rect[0], rect[1], rect[2], rect[3], inside(rect) ? PRIMARY_HOVER : PRIMARY);
        String text = fit(label, rect[2] - 8);
        canvas.text(text, rect[0] + (rect[2] - canvas.textWidth(text)) / 2, textY(rect), PRIMARY_TEXT);
        return pressed(rect);
    }

    @Override
    public boolean button(String id, String label) {
        int[] rect = place(Math.min(canvas.textWidth(label) + 14, panelWidth - PADDING * 2), ROW);
        round(rect[0], rect[1], rect[2], rect[3], inside(rect) ? SURFACE_HOVER : SURFACE);
        centered(label, rect, TEXT);
        return pressed(rect);
    }

    @Override
    public boolean quietButton(String id, String label) {
        int[] rect = place(Math.min(canvas.textWidth(label) + 10, panelWidth - PADDING * 2), ROW);
        boolean hover = inside(rect);
        if (hover) {
            round(rect[0], rect[1], rect[2], rect[3], SURFACE);
        }
        centered(label, rect, hover ? TEXT : SECONDARY);
        return pressed(rect);
    }

    @Override
    public boolean dangerButton(String id, String label) {
        int[] rect = place(Math.min(canvas.textWidth(label) + 10, panelWidth - PADDING * 2), ROW);
        if (inside(rect)) {
            round(rect[0], rect[1], rect[2], rect[3], DANGER_SURFACE);
        }
        centered(label, rect, DANGER);
        return pressed(rect);
    }

    @Override
    public boolean listRow(String id, String label, String meta, boolean selected, int swatch) {
        sameRowNext = false;
        int[] rect = {panelX + PADDING - 4, cursorY, panelWidth - PADDING * 2 + 8, LIST_ROW};
        cursorY += LIST_ROW + 1;
        lastWidth = 0;
        boolean hover = inside(rect);
        if (selected) {
            round(rect[0], rect[1], rect[2], rect[3], SELECTED);
            canvas.fill(rect[0], rect[1] + 4, 2, rect[3] - 8, ACCENT);
        } else if (hover) {
            round(rect[0], rect[1], rect[2], rect[3], SURFACE);
        }
        int textX = rect[0] + 8;
        if (swatch >= 0) {
            round(textX, rect[1] + 6, 8, 8, 0xFF000000 | swatch);
            textX += 14;
        }
        int right = rect[0] + rect[2] - 8;
        if (!meta.isEmpty()) {
            int metaWidth = canvas.textWidth(meta);
            canvas.text(meta, right - metaWidth, textY(rect), MUTED);
            right -= metaWidth + 8;
        }
        canvas.text(fit(label, right - textX), textX, textY(rect), selected || hover ? TEXT : SECONDARY);
        return pressed(rect);
    }

    @Override
    public void steps(List<String> labels, int current) {
        int[] rect = fullRow(canvas.lineHeight() + 8);
        int count = labels.size();
        for (int index = 0; index < count; index++) {
            int left = rect[0] + rect[2] * index / count;
            int right = rect[0] + rect[2] * (index + 1) / count - (index == count - 1 ? 0 : 3);
            canvas.fill(left, rect[1], right - left, 2, index < current ? SECONDARY : index == current ? TEXT : BORDER);
            canvas.text(fit(labels.get(index), right - left), left, rect[1] + 6,
                    index < current ? MUTED : index == current ? TEXT : MUTED);
        }
    }

    @Override
    public String textInput(String id, String label, String value, int maxLength) {
        int[] field = labelledRow(label);
        return textField(id, field, value, maxLength, false);
    }

    @Override
    public Integer intInput(String id, String label, int value, int min, int max) {
        int[] field = labelledRow(label);
        String text = textField(id, field, Integer.toString(value), 11, true);
        if (text == null) {
            return null;
        }
        try {
            int parsed = Math.max(min, Math.min(max, Integer.parseInt(text.strip())));
            return parsed == value ? null : parsed;
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    @Override
    public Float slider(String id, String label, float value, float min, float max, String format) {
        int[] control = labelledRow(label);
        int[] track = {control[0], control[1], control[2] - SLIDER_VALUE_WIDTH, control[3]};
        boolean active = id.equals(activeSlider);
        if (pressed(track)) {
            activeSlider = id;
            active = true;
        }
        Float committed = null;
        if (active) {
            float fraction = (input.mouseX - track[0] - 3) / (float) Math.max(1, track[2] - 6);
            sliderValue = min + Math.max(0.0f, Math.min(1.0f, fraction)) * (max - min);
            if (input.released && sliderValue != value) {
                committed = sliderValue;
            }
        }
        float shown = active ? sliderValue : value;
        int travel = track[2] - 6;
        int knob = track[0] + 3 + Math.round(travel * (shown - min) / (max - min));
        int lineY = track[1] + track[3] / 2 - 1;
        canvas.fill(track[0], lineY, track[2], 2, BORDER);
        canvas.fill(track[0], lineY, knob - track[0], 2, ACCENT);
        round(knob - 3, track[1] + 3, 6, track[3] - 6, active || inside(track) ? PRIMARY_HOVER : TEXT);
        String text = String.format(Locale.ROOT, format, shown);
        canvas.text(text, control[0] + control[2] - canvas.textWidth(text), textY(control), SECONDARY);
        return committed;
    }

    @Override
    public Boolean checkbox(String id, String label, boolean value) {
        int[] rect = fullRow(ROW);
        int boxY = rect[1] + 4;
        round(rect[0], boxY, 10, 10, value ? ACCENT : inside(rect) ? SECONDARY : BORDER);
        if (value) {
            canvas.fill(rect[0] + 3, boxY + 3, 4, 4, PRIMARY_TEXT);
        } else {
            round(rect[0] + 1, boxY + 1, 8, 8, FIELD);
        }
        canvas.text(fit(label, rect[2] - 16), rect[0] + 16, textY(rect), SECONDARY);
        return pressed(rect) ? !value : null;
    }

    @Override
    public Integer choice(String id, List<String> options, int selected) {
        int[] rect = fullRow(ROW);
        round(rect[0], rect[1], rect[2], rect[3], FIELD);
        Integer picked = null;
        int count = options.size();
        int inner = rect[2] - 2;
        for (int index = 0; index < count; index++) {
            int left = rect[0] + 1 + inner * index / count;
            int right = rect[0] + 1 + inner * (index + 1) / count;
            int[] segment = {left, rect[1] + 1, right - left, rect[3] - 2};
            boolean hover = inside(segment);
            if (index == selected) {
                round(segment[0], segment[1], segment[2], segment[3], SURFACE_HOVER);
            }
            centered(options.get(index), segment, index == selected || hover ? TEXT : MUTED);
            if (pressed(segment) && index != selected) {
                picked = index;
            }
        }
        return picked;
    }

    @Override
    public String combo(String id, String label, List<String> options, String selected) {
        int[] field = labelledRow(label);
        boolean isOpen = id.equals(openCombo);
        round(field[0], field[1], field[2], field[3], isOpen ? ACCENT : inside(field) ? MUTED : BORDER);
        round(field[0] + 1, field[1] + 1, field[2] - 2, field[3] - 2, FIELD);
        String shown = isOpen && !comboFilter.isEmpty() ? comboFilter : selected == null ? "…" : selected;
        canvas.text(fit(shown, field[2] - 20), field[0] + 5, textY(field), selected == null && !isOpen ? MUTED : TEXT);
        canvas.text(isOpen ? "▴" : "▾", field[0] + field[2] - 11, textY(field), MUTED);
        if (pressed(field)) {
            if (isOpen) {
                openCombo = null;
                focusId = null;
            } else {
                openCombo = id;
                comboFilter = "";
                comboScroll = 0;
                focusId = "combo:" + id;
                buffer = "";
                replaceOnType = false;
                focusRect = null;
            }
            return null;
        }
        if (!isOpen) {
            return null;
        }

        comboFilter = buffer;
        caret = new int[]{field[0] + 6 + canvas.textWidth(fit(comboFilter, field[2] - 20)), field[1] + 4, field[3] - 8};
        String needle = comboFilter.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (needle.isEmpty() || option.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(option);
            }
        }
        int visible = Math.min(COMBO_ROWS, Math.max(1, matches.size()));
        int listTop = cursorY - GAP + 2;
        int[] list = {panelX + PADDING, listTop, panelWidth - PADDING * 2, visible * COMBO_ROW + 2};
        if (inside(list)) {
            comboScroll -= (int) Math.signum(input.scroll);
        }
        comboScroll = Math.max(0, Math.min(comboScroll, Math.max(0, matches.size() - visible)));
        round(list[0], list[1], list[2], list[3], BORDER);
        round(list[0] + 1, list[1] + 1, list[2] - 2, list[3] - 2, FIELD);
        String result = null;
        for (int row = 0; row < visible; row++) {
            int[] rect = {list[0] + 1, listTop + 1 + row * COMBO_ROW, list[2] - 2, COMBO_ROW};
            if (matches.isEmpty()) {
                canvas.text("—", rect[0] + 5, textY(rect), MUTED);
                break;
            }
            String option = matches.get(comboScroll + row);
            boolean hover = inside(rect);
            boolean current = option.equals(selected);
            if (hover) {
                canvas.fill(rect[0], rect[1], rect[2], rect[3], SURFACE_HOVER);
            }
            canvas.text(fit(option, rect[2] - 10), rect[0] + 5, textY(rect), hover || current ? TEXT : SECONDARY);
            if (pressed(rect)) {
                result = option;
            }
        }
        if (matches.size() > visible) {
            int barHeight = Math.max(6, (list[3] - 2) * visible / matches.size());
            int barY = list[1] + 1 + (list[3] - 2 - barHeight) * comboScroll / (matches.size() - visible);
            canvas.fill(list[0] + list[2] - 3, barY, 2, barHeight, MUTED);
        }
        focusRect = new int[]{list[0], field[1], list[2], list[1] + list[3] - field[1]};
        cursorY = list[1] + list[3] + GAP;
        lastWidth = 0;
        if (result != null) {
            openCombo = null;
            focusId = null;
            return result.equals(selected) ? null : result;
        }
        return null;
    }

    @Override
    public Integer swatches(String id, List<Integer> colors, int selected) {
        int[] rect = fullRow(ROW);
        Integer picked = null;
        int x = rect[0];
        for (int color : colors) {
            int[] box = {x, rect[1] + 2, 14, 14};
            if (color == selected) {
                round(box[0], box[1], box[2], box[3], TEXT);
            }
            if (color < 0) {
                round(box[0] + 1, box[1] + 1, 12, 12, SURFACE_HOVER);
                canvas.text("A", box[0] + (14 - canvas.textWidth("A")) / 2, box[1] + 3, SECONDARY);
            } else {
                round(box[0] + 1, box[1] + 1, 12, 12, 0xFF000000 | color);
            }
            if (pressed(box) && color != selected) {
                picked = color;
            }
            x += 17;
        }
        return picked;
    }

    @Override
    public boolean collapsing(String id, String label, boolean openByDefault) {
        boolean isOpen = open.getOrDefault(id, openByDefault);
        int[] rect = fullRow(ROW);
        boolean hover = inside(rect);
        canvas.text(isOpen ? "▾" : "▸", rect[0], textY(rect), hover ? TEXT : MUTED);
        canvas.text(fit(label, rect[2] - 14), rect[0] + 10, textY(rect), hover ? TEXT : SECONDARY);
        if (pressed(rect)) {
            isOpen = !isOpen;
            open.put(id, isOpen);
        }
        return isOpen;
    }

    @Override
    public void progress(String label, float fraction) {
        int[] rect = fullRow(canvas.lineHeight() + 7);
        canvas.text(fit(label, rect[2]), rect[0], rect[1], SECONDARY);
        int barY = rect[1] + rect[3] - 2;
        canvas.fill(rect[0], barY, rect[2], 2, BORDER);
        canvas.fill(rect[0], barY, Math.round(rect[2] * Math.max(0.0f, Math.min(1.0f, fraction))), 2, ACCENT);
    }

    // ---- internals ----

    /** Where a slider's draggable track sits for a panel at {@code panelX} of {@code panelWidth}. */
    static int[] sliderTrack(int panelX, int panelWidth) {
        int rowWidth = panelWidth - PADDING * 2;
        int labelWidth = rowWidth * 34 / 100;
        return new int[]{panelX + PADDING + labelWidth, rowWidth - labelWidth - SLIDER_VALUE_WIDTH};
    }

    private String textField(String id, int[] field, String value, int maxLength, boolean numeric) {
        boolean focused = id.equals(focusId);
        if (pressed(field) && !focused) {
            focusId = id;
            buffer = value;
            replaceOnType = true;
            focused = true;
        }
        if (focused) {
            focusRect = field;
            buffer = sanitize(buffer, maxLength, numeric);
        }
        round(field[0], field[1], field[2], field[3], focused ? ACCENT : inside(field) ? MUTED : BORDER);
        round(field[0] + 1, field[1] + 1, field[2] - 2, field[3] - 2, FIELD);
        String shown = fitEnd(focused ? buffer : value, field[2] - 12);
        canvas.text(shown, field[0] + 5, textY(field), TEXT);
        if (focused) {
            caret = new int[]{field[0] + 6 + canvas.textWidth(shown), field[1] + 4, field[3] - 8};
            canvas.fill(caret[0], caret[1], 1, caret[2], TEXT);
        }

        if (id.equals(committedId)) {
            String result = sanitize(committedValue, maxLength, numeric);
            committedId = null;
            return result.equals(value) ? null : result;
        }
        return null;
    }

    private static String sanitize(String text, int maxLength, boolean numeric) {
        StringBuilder cleaned = new StringBuilder();
        int count = 0;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            boolean allowed = !numeric
                    || Character.isDigit(codePoint)
                    || codePoint == '-' && count == 0;
            if (allowed && count < maxLength) {
                cleaned.appendCodePoint(codePoint);
                count++;
            }
        }
        return cleaned.toString();
    }

    private void handleFocusKeys() {
        if (input.typed.length() > 0) {
            buffer = replaceOnType ? input.typed.toString() : buffer + input.typed;
            replaceOnType = false;
        }
        for (UiInput.Key key : input.keys) {
            switch (key) {
                case BACKSPACE -> {
                    if (replaceOnType) {
                        buffer = "";
                        replaceOnType = false;
                    } else if (!buffer.isEmpty()) {
                        buffer = buffer.substring(0, buffer.offsetByCodePoints(buffer.length(), -1));
                    }
                }
                case PASTE -> {
                    String pasted = canvas.clipboard().replaceAll("\\p{Cntrl}", "");
                    buffer = replaceOnType ? pasted : buffer + pasted;
                    replaceOnType = false;
                }
                case ENTER, TAB -> {
                    if (focusId != null && !focusId.startsWith("combo:")) {
                        commitFocus();
                    }
                }
                case ESCAPE -> {
                    // Leaving a field keeps what was typed; undo is there for second thoughts.
                    commitFocus();
                }
                default -> {
                }
            }
            if (focusId == null) {
                break;
            }
        }
    }

    private void commitFocus() {
        if (focusId == null) {
            return;
        }
        if (focusId.startsWith("combo:")) {
            openCombo = null;
        } else {
            committedId = focusId;
            committedValue = buffer;
        }
        focusId = null;
    }

    /** A full-width row with the label on the left; returns the rectangle of the control. */
    private int[] labelledRow(String label) {
        int[] rect = fullRow(ROW);
        int labelWidth = rect[2] * 34 / 100;
        canvas.text(fit(label, labelWidth - 6), rect[0], textY(rect), SECONDARY);
        return new int[]{rect[0] + labelWidth, rect[1], rect[2] - labelWidth, rect[3]};
    }

    private void wrapped(String text, int color, boolean allowSameRow) {
        int width = panelWidth - PADDING * 2;
        List<String> lines = wrap(text, width);
        if (lines.size() == 1 && allowSameRow) {
            int[] rect = place(Math.min(width, canvas.textWidth(lines.get(0))), ROW);
            canvas.text(lines.get(0), rect[0], textY(rect), color);
            return;
        }
        int lineHeight = canvas.lineHeight() + 2;
        int[] rect = fullRow(lines.size() * lineHeight);
        for (int index = 0; index < lines.size(); index++) {
            canvas.text(lines.get(index), rect[0], rect[1] + index * lineHeight, color);
        }
        lastWidth = 0;
    }

    private void centered(String label, int[] rect, int color) {
        String text = fit(label, rect[2] - 6);
        canvas.text(text, rect[0] + (rect[2] - canvas.textWidth(text)) / 2, textY(rect), color);
    }

    /** A rectangle with its four corner pixels left out, which reads as slightly rounded. */
    private void round(int x, int y, int width, int height, int argb) {
        if (width < 3 || height < 3) {
            canvas.fill(x, y, width, height, argb);
            return;
        }
        canvas.fill(x + 1, y, width - 2, height, argb);
        canvas.fill(x, y + 1, 1, height - 2, argb);
        canvas.fill(x + width - 1, y + 1, 1, height - 2, argb);
    }

    private int[] fullRow(int height) {
        sameRowNext = false;
        int[] rect = place(panelWidth - PADDING * 2, height);
        lastWidth = 0;
        return rect;
    }

    private int[] place(int width, int height) {
        int x = panelX + PADDING;
        int y = cursorY;
        if (sameRowNext && lastWidth > 0 && lastX + lastWidth + GAP + width <= panelX + panelWidth - PADDING) {
            x = lastX + lastWidth + GAP;
            y = lastY;
            cursorY = Math.max(cursorY, y + Math.max(height, lastHeight) + GAP);
        } else {
            cursorY += height + GAP;
        }
        sameRowNext = false;
        lastX = x;
        lastY = y;
        lastWidth = width;
        lastHeight = height;
        return new int[]{x, y, width, height};
    }

    private boolean inside(int[] rect) {
        return input.mouseX >= rect[0] && input.mouseX < rect[0] + rect[2]
                && input.mouseY >= rect[1] && input.mouseY < rect[1] + rect[3]
                && input.mouseY >= panelY && input.mouseY < panelY + panelHeight;
    }

    private boolean pressed(int[] rect) {
        if (input.pressed && !pressConsumed && inside(rect)) {
            pressConsumed = true;
            return true;
        }
        return false;
    }

    private int textY(int[] rect) {
        return rect[1] + (rect[3] - canvas.lineHeight()) / 2 + 1;
    }

    /** Shortens text from the end with an ellipsis so it fits. */
    private String fit(String text, int width) {
        if (canvas.textWidth(text) <= width) {
            return text;
        }
        String ellipsis = "…";
        int end = text.length();
        while (end > 0 && canvas.textWidth(text.substring(0, end) + ellipsis) > width) {
            end = text.offsetByCodePoints(end, -1);
        }
        return text.substring(0, end) + ellipsis;
    }

    /** Keeps the end of the text visible, as a field being typed into should. */
    private String fitEnd(String text, int width) {
        int start = 0;
        while (start < text.length() && canvas.textWidth(text.substring(start)) > width) {
            start = text.offsetByCodePoints(start, 1);
        }
        return text.substring(start);
    }

    private List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int lastSpace = -1;
        for (int offset = 0; offset < text.length(); ) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\n') {
                lines.add(line.toString());
                line.setLength(0);
                lastSpace = -1;
                continue;
            }
            line.appendCodePoint(codePoint);
            if (codePoint == ' ') {
                lastSpace = line.length() - 1;
            }
            if (canvas.textWidth(line.toString()) > width && line.length() > Character.charCount(codePoint)) {
                int breakAt = lastSpace > 0 ? lastSpace : line.length() - Character.charCount(codePoint);
                if (lastSpace <= 0 && CLOSING_PUNCTUATION.indexOf(codePoint) >= 0) {
                    // Punctuation must not start a line: take the character before it along.
                    int previous = line.offsetByCodePoints(breakAt, -1);
                    if (previous > 0) {
                        breakAt = previous;
                    }
                }
                lines.add(line.substring(0, breakAt).stripTrailing());
                String rest = line.substring(breakAt).stripLeading();
                line.setLength(0);
                line.append(rest);
                lastSpace = -1;
            }
        }
        if (line.length() > 0 || lines.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }
}
