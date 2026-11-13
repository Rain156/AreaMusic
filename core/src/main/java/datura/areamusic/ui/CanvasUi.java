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
 */
public final class CanvasUi implements PanelUi {
    private static final int PADDING = 8;
    private static final int GAP = 4;
    private static final int ROW = 16;
    private static final int COMBO_ROWS = 8;

    private static final int BACKGROUND = 0xE6101418;
    private static final int TEXT = 0xFFE8EAED;
    private static final int SECONDARY = 0xFFB0B8C0;
    private static final int MUTED = 0xFF7F8A94;
    private static final int ERROR = 0xFFFF8A80;
    private static final int ACCENT = 0xFF4C9BE8;
    private static final int ACCENT_DARK = 0xFF2F6CAB;
    private static final int WIDGET = 0xFF262D34;
    private static final int WIDGET_HOVER = 0xFF323B44;
    private static final int BORDER = 0xFF3D4750;
    private static final int SELECTED = 0xFF27425E;

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
    private String committedId;
    private String committedValue;
    private String activeSlider;
    private float sliderValue;
    private String openCombo;
    private String comboFilter = "";
    private int comboScroll;
    private final Map<String, Boolean> open = new HashMap<>();

    /** Starts a frame. The panel occupies the given rectangle and scrolls when its content is taller. */
    public void begin(UiCanvas canvas, UiInput input, int x, int y, int width, int height) {
        this.canvas = canvas;
        this.input = input;
        panelX = x;
        panelY = y;
        panelWidth = width;
        panelHeight = height;
        pressConsumed = false;
        sameRowNext = false;
        lastWidth = 0;
        committedId = null;

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

        canvas.fill(x, y, width, height, BACKGROUND);
        canvas.clip(x, y, width, height);
        cursorY = y + PADDING - scroll;
    }

    public void end() {
        contentHeight = cursorY + scroll - panelY + PADDING;
        canvas.unclip();
        if (contentHeight > panelHeight) {
            int barHeight = Math.max(12, panelHeight * panelHeight / contentHeight);
            int travel = panelHeight - barHeight;
            int barY = panelY + (int) ((long) travel * scroll / (contentHeight - panelHeight));
            canvas.fill(panelX + panelWidth - 3, barY, 2, barHeight, BORDER);
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

    /** Closes the open drop-down or text field. Returns false when there was nothing to close. */
    public boolean dismiss() {
        if (openCombo != null) {
            openCombo = null;
            focusId = null;
            return true;
        }
        if (focusId != null) {
            focusId = null;
            return true;
        }
        return false;
    }

    // ---- widgets ----

    @Override
    public void heading(String text) {
        int[] rect = fullRow(canvas.lineHeight() + 4);
        canvas.text(fit(text, rect[2]), rect[0], rect[1] + 2, TEXT);
        canvas.fill(rect[0], rect[1] + rect[3] - 1, rect[2], 1, BORDER);
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
    public void error(String text) {
        wrapped(text, ERROR, false);
    }

    @Override
    public void separator() {
        sameRowNext = false;
        cursorY += GAP;
    }

    @Override
    public void sameRow() {
        sameRowNext = lastWidth > 0;
    }

    @Override
    public boolean button(String id, String label) {
        return button(label, WIDGET, WIDGET_HOVER);
    }

    @Override
    public boolean primaryButton(String id, String label) {
        return button(label, ACCENT_DARK, ACCENT);
    }

    @Override
    public boolean selectable(String id, String label, boolean selected, int swatch) {
        int[] rect = fullRow(ROW);
        boolean hover = inside(rect);
        if (selected || hover) {
            canvas.fill(rect[0], rect[1], rect[2], rect[3], selected ? SELECTED : WIDGET_HOVER);
        }
        int textX = rect[0] + 4;
        if (swatch >= 0) {
            canvas.fill(textX, rect[1] + 4, 8, 8, 0xFF000000 | swatch);
            textX += 12;
        }
        canvas.text(fit(label, rect[0] + rect[2] - textX - 2), textX, textY(rect), TEXT);
        return pressed(rect);
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
        int[] track = labelledRow(label);
        boolean active = id.equals(activeSlider);
        if (pressed(track)) {
            activeSlider = id;
            active = true;
        }
        Float committed = null;
        if (active) {
            float fraction = (input.mouseX - track[0] - 2) / (float) Math.max(1, track[2] - 4);
            sliderValue = min + Math.max(0.0f, Math.min(1.0f, fraction)) * (max - min);
            if (input.released && sliderValue != value) {
                committed = sliderValue;
            }
        }
        float shown = active ? sliderValue : value;
        int filled = Math.round((track[2] - 2) * (shown - min) / (max - min));
        canvas.fill(track[0], track[1], track[2], track[3], BORDER);
        canvas.fill(track[0] + 1, track[1] + 1, track[2] - 2, track[3] - 2, WIDGET);
        canvas.fill(track[0] + 1, track[1] + 1, Math.max(0, filled), track[3] - 2, active ? ACCENT : ACCENT_DARK);
        String text = String.format(Locale.ROOT, format, shown);
        canvas.text(text, track[0] + (track[2] - canvas.textWidth(text)) / 2, textY(track), TEXT);
        return committed;
    }

    @Override
    public Boolean checkbox(String id, String label, boolean value) {
        int[] rect = fullRow(ROW);
        int boxY = rect[1] + 3;
        canvas.fill(rect[0], boxY, 10, 10, BORDER);
        canvas.fill(rect[0] + 1, boxY + 1, 8, 8, inside(rect) ? WIDGET_HOVER : WIDGET);
        if (value) {
            canvas.fill(rect[0] + 3, boxY + 3, 4, 4, ACCENT);
        }
        canvas.text(fit(label, rect[2] - 16), rect[0] + 16, textY(rect), SECONDARY);
        return pressed(rect) ? !value : null;
    }

    @Override
    public Integer choice(String id, List<String> options, int selected) {
        int[] rect = fullRow(ROW);
        Integer picked = null;
        int count = options.size();
        for (int index = 0; index < count; index++) {
            int left = rect[0] + rect[2] * index / count;
            int right = rect[0] + rect[2] * (index + 1) / count;
            int[] segment = {left, rect[1], right - left - 1, rect[3]};
            boolean hover = inside(segment);
            canvas.fill(segment[0], segment[1], segment[2], segment[3],
                    index == selected ? ACCENT_DARK : hover ? WIDGET_HOVER : WIDGET);
            String text = fit(options.get(index), segment[2] - 4);
            canvas.text(text, segment[0] + (segment[2] - canvas.textWidth(text)) / 2, textY(segment),
                    index == selected ? TEXT : SECONDARY);
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
        canvas.fill(field[0], field[1], field[2], field[3], BORDER);
        canvas.fill(field[0] + 1, field[1] + 1, field[2] - 2, field[3] - 2,
                isOpen || inside(field) ? WIDGET_HOVER : WIDGET);
        String shown = isOpen && !comboFilter.isEmpty() ? comboFilter + "_" : selected == null ? "…" : selected;
        canvas.text(fit(shown, field[2] - 16), field[0] + 4, textY(field), selected == null && !isOpen ? MUTED : TEXT);
        canvas.text(isOpen ? "▴" : "▾", field[0] + field[2] - 10, textY(field), SECONDARY);
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
        String needle = comboFilter.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (needle.isEmpty() || option.toLowerCase(Locale.ROOT).contains(needle)) {
                matches.add(option);
            }
        }
        int visible = Math.min(COMBO_ROWS, Math.max(1, matches.size()));
        int listTop = cursorY;
        int[] list = {panelX + PADDING, listTop, panelWidth - PADDING * 2, visible * ROW};
        if (inside(list)) {
            comboScroll -= (int) Math.signum(input.scroll);
        }
        comboScroll = Math.max(0, Math.min(comboScroll, Math.max(0, matches.size() - visible)));
        canvas.fill(list[0], list[1], list[2], list[3], BORDER);
        String result = null;
        for (int row = 0; row < visible; row++) {
            int[] rect = {list[0] + 1, listTop + row * ROW, list[2] - 2, ROW};
            if (matches.isEmpty()) {
                canvas.fill(rect[0], rect[1], rect[2], rect[3], WIDGET);
                canvas.text("—", rect[0] + 4, textY(rect), MUTED);
                break;
            }
            String option = matches.get(comboScroll + row);
            boolean hover = inside(rect);
            canvas.fill(rect[0], rect[1], rect[2], rect[3],
                    option.equals(selected) ? SELECTED : hover ? WIDGET_HOVER : WIDGET);
            canvas.text(fit(option, rect[2] - 8), rect[0] + 4, textY(rect), TEXT);
            if (pressed(rect)) {
                result = option;
            }
        }
        if (matches.size() > visible) {
            int barHeight = Math.max(6, list[3] * visible / matches.size());
            int barY = list[1] + (list[3] - barHeight) * comboScroll / (matches.size() - visible);
            canvas.fill(list[0] + list[2] - 3, barY, 2, barHeight, MUTED);
        }
        focusRect = new int[]{list[0], field[1], list[2], list[3] + ROW + GAP};
        cursorY += list[3] + GAP;
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
            int[] box = {x, rect[1] + 1, 14, 14};
            canvas.fill(box[0], box[1], box[2], box[3], color == selected ? TEXT : BORDER);
            if (color < 0) {
                canvas.fill(box[0] + 1, box[1] + 1, 12, 12, WIDGET);
                canvas.text("A", box[0] + (14 - canvas.textWidth("A")) / 2, box[1] + 3, SECONDARY);
            } else {
                canvas.fill(box[0] + 1, box[1] + 1, 12, 12, 0xFF000000 | color);
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
        canvas.fill(rect[0], rect[1], rect[2], rect[3], inside(rect) ? WIDGET_HOVER : WIDGET);
        canvas.text(isOpen ? "▾" : "▸", rect[0] + 4, textY(rect), SECONDARY);
        canvas.text(fit(label, rect[2] - 18), rect[0] + 14, textY(rect), TEXT);
        if (pressed(rect)) {
            isOpen = !isOpen;
            open.put(id, isOpen);
        }
        return isOpen;
    }

    @Override
    public void progress(String label, float fraction) {
        int[] rect = fullRow(ROW);
        canvas.fill(rect[0], rect[1], rect[2], rect[3], WIDGET);
        canvas.fill(rect[0], rect[1], Math.round(rect[2] * Math.max(0.0f, Math.min(1.0f, fraction))), rect[3], ACCENT_DARK);
        canvas.text(fit(label, rect[2] - 8), rect[0] + 4, textY(rect), TEXT);
    }

    // ---- internals ----

    private boolean button(String label, int color, int hoverColor) {
        int width = Math.min(canvas.textWidth(label) + 12, panelWidth - PADDING * 2);
        int[] rect = place(width, ROW);
        canvas.fill(rect[0], rect[1], rect[2], rect[3], inside(rect) ? hoverColor : color);
        canvas.text(fit(label, rect[2] - 6), rect[0] + (rect[2] - Math.min(canvas.textWidth(label), rect[2] - 6)) / 2, textY(rect), TEXT);
        return pressed(rect);
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
        canvas.fill(field[0], field[1], field[2], field[3], focused ? ACCENT : BORDER);
        canvas.fill(field[0] + 1, field[1] + 1, field[2] - 2, field[3] - 2, WIDGET);
        String shown = focused ? buffer + "_" : value;
        canvas.text(fitEnd(shown, field[2] - 8), field[0] + 4, textY(field), TEXT);

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
                    focusId = null;
                    openCombo = null;
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
        int labelWidth = rect[2] * 2 / 5;
        canvas.text(fit(label, labelWidth - 4), rect[0], textY(rect), SECONDARY);
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
        } else {
            cursorY += height + GAP;
        }
        if (y == lastY && sameRowNext) {
            cursorY = Math.max(cursorY, y + Math.max(height, lastHeight) + GAP);
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
