package datura.areamusic.ui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasUiTest {
    private static final int X = 300;
    private static final int WIDTH = 200;
    private static final int HEIGHT = 240;

    private final FakeCanvas canvas = new FakeCanvas();
    private final UiInput input = new UiInput();
    private final CanvasUi ui = new CanvasUi();

    @BeforeEach
    void pointerStartsOutsideThePanel() {
        input.mouseMoved(10, 10);
    }

    @Test
    void buttonReportsAClickOnlyOnTheFrameItIsPressed() {
        List<Boolean> clicks = new ArrayList<>();
        Consumer<PanelUi> panel = p -> clicks.add(p.button("b", "Click"));

        frame(panel);
        int[] rect = canvas.rectOf("Click");
        input.mousePressed(rect[0] + 2, rect[1] + 2);
        frame(panel);
        input.mouseReleased(rect[0] + 2, rect[1] + 2);
        frame(panel);

        assertEquals(List.of(false, true, false), clicks);
    }

    @Test
    void sameRowPlacesButtonsSideBySideAndWrapsWhenFull() {
        frame(p -> {
            p.button("a", "One");
            p.sameRow();
            p.button("b", "Two");
            p.sameRow();
            p.button("c", "A very long button label that cannot fit beside");
            p.button("d", "Below");
        });

        int[] one = canvas.rectOf("One");
        int[] two = canvas.rectOf("Two");
        int[] below = canvas.rectOf("Below");
        assertEquals(one[1], two[1]);
        assertTrue(two[0] > one[0] + canvas.textWidth("One"));
        assertTrue(canvas.yOfTextStartingWith("A very long") > one[1]);
        assertTrue(below[1] > canvas.yOfTextStartingWith("A very long"));
    }

    @Test
    void overlappingClickGoesToOneWidgetOnly() {
        List<String> hits = new ArrayList<>();
        Consumer<PanelUi> panel = p -> {
            if (p.listRow("row", "Row", "", false, 0x123456)) {
                hits.add("row");
            }
            if (p.button("b", "Button")) {
                hits.add("button");
            }
        };
        frame(panel);
        int[] row = canvas.rectOf("Row");
        input.mousePressed(row[0] + 1, row[1] + 1);
        frame(panel);

        assertEquals(List.of("row"), hits);
    }

    @Test
    void textInputCommitsOnEnterAndReplacesTheOldValueWhenTypingStarts() {
        List<String> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> results.add(p.textInput("name", "Name", "old", 8));
        frame(panel);
        click(panel, canvas.rectOf("old"));
        assertTrue(ui.editingText());

        for (int codePoint : "村庄 square!".codePoints().toArray()) {
            input.charTyped(codePoint);
        }
        frame(panel);
        input.keyPressed(UiInput.Key.BACKSPACE);
        frame(panel);
        input.keyPressed(UiInput.Key.ENTER);
        frame(panel);

        assertEquals("村庄 squa", results.get(results.size() - 1));
        assertFalse(ui.editingText());
        assertEquals(1, results.stream().filter(result -> result != null).count());
    }

    @Test
    void textInputKeepsWhatWasTypedWhenClickingElsewhereOrPressingEscape() {
        List<String> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> {
            results.add(p.textInput("name", "Name", "old", 20));
            p.button("other", "Other");
        };
        frame(panel);
        click(panel, canvas.rectOf("old"));
        input.charTyped('x');
        frame(panel);
        int[] other = canvas.rectOf("Other");
        input.mousePressed(other[0] + 1, other[1] + 1);
        frame(panel);
        assertEquals("x", results.get(results.size() - 1));

        input.mouseReleased(other[0] + 1, other[1] + 1);
        frame(panel);
        click(panel, canvas.rectOf("old"));
        input.charTyped('y');
        input.keyPressed(UiInput.Key.ESCAPE);
        frame(panel);
        assertEquals("y", results.get(results.size() - 1));
        assertFalse(ui.editingText());
        frame(panel);
        assertNull(results.get(results.size() - 1));
    }

    @Test
    void pasteUsesTheClipboardWithoutControlCharacters() {
        List<String> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> results.add(p.textInput("name", "Name", "", 20));
        canvas.clipboard = "day\ttheme\n";
        frame(panel);
        click(panel, fieldBeside("Name"));
        input.keyPressed(UiInput.Key.PASTE);
        input.keyPressed(UiInput.Key.ENTER);
        frame(panel);

        assertEquals("daytheme", results.get(results.size() - 1));
    }

    @Test
    void intInputAcceptsOnlyNumbersAndClamps() {
        List<Integer> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> results.add(p.intInput("n", "Priority", 5, -10, 10));
        frame(panel);
        click(panel, canvas.rectOf("5"));
        for (char character : "-9x9".toCharArray()) {
            input.charTyped(character);
        }
        input.keyPressed(UiInput.Key.ENTER);
        frame(panel);
        assertEquals(-10, results.get(results.size() - 1));

        click(panel, canvas.rectOf("5"));
        input.charTyped('-');
        input.keyPressed(UiInput.Key.ENTER);
        frame(panel);
        assertNull(results.get(results.size() - 1));
    }

    @Test
    void sliderShowsTheDraggedValueAndCommitsOnRelease() {
        List<Float> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> results.add(p.slider("v", "Volume", 80.0f, 0.0f, 100.0f, "%.0f%%"));
        frame(panel);
        int[] value = canvas.rectOf("80%");
        int trackLeft = CanvasUi.sliderTrack(X, WIDTH)[0];
        int travel = CanvasUi.sliderTrack(X, WIDTH)[1] - 6;

        int offset = travel / 4;
        input.mousePressed(trackLeft + 3 + offset, value[1]);
        frame(panel);
        assertNull(results.get(results.size() - 1));
        String expected = String.format(java.util.Locale.ROOT, "%.0f%%", 100.0f * offset / travel);
        assertTrue(canvas.texts().contains(expected), canvas.texts().toString());
        assertFalse(expected.equals("80%"));

        input.mouseMoved(trackLeft + 5000, value[1]);
        frame(panel);
        assertTrue(canvas.texts().contains("100%"));
        input.mouseReleased(trackLeft + 5000, value[1]);
        frame(panel);
        assertEquals(100.0f, results.get(results.size() - 1));
        frame(panel);
        assertNull(results.get(results.size() - 1));
    }

    @Test
    void timelineIsDraggedLikeAProgressBarAndCommitsWholeSecondsOnRelease() {
        List<Integer> results = new ArrayList<>();
        // A three-minute track, starting at 0:30.
        Consumer<PanelUi> panel = p -> results.add(p.timeline("start", "Start", 30_000, 180_000));
        frame(panel);
        int[] value = canvas.rectOf("0:30");
        int trackLeft = CanvasUi.sliderTrack(X, WIDTH)[0];
        int travel = CanvasUi.sliderTrack(X, WIDTH)[1] - 6;

        // Wherever the knob lands, the position is a whole number of seconds.
        int halfway = Math.round(180.0f * (travel / 2) / travel) * 1000;
        String halfwayText = datura.areamusic.edit.TrackTime.format(halfway);
        input.mousePressed(trackLeft + 3 + travel / 2, value[1]);
        frame(panel);
        assertNull(results.get(results.size() - 1), "nothing is committed while dragging");
        assertTrue(canvas.texts().contains(halfwayText), canvas.texts().toString());
        assertTrue(halfway >= 88_000 && halfway <= 92_000, halfwayText);

        input.mouseMoved(trackLeft + 5000, value[1]);
        frame(panel);
        assertTrue(canvas.texts().contains("2:59"), "the last second, not the very end: " + canvas.texts());
        input.mouseMoved(trackLeft + 3 + travel / 2, value[1]);
        input.mouseReleased(trackLeft + 3 + travel / 2, value[1]);
        frame(panel);
        assertEquals(halfway, results.get(results.size() - 1));
        frame(panel);
        assertNull(results.get(results.size() - 1));
    }

    @Test
    void timelineOfAShortTrackMovesInTenthsOfASecond() {
        List<Integer> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> results.add(p.timeline("start", "Start", 0, 4_000));
        frame(panel);
        int[] value = canvas.rectOf("0:00");
        int trackLeft = CanvasUi.sliderTrack(X, WIDTH)[0];
        int travel = CanvasUi.sliderTrack(X, WIDTH)[1] - 6;

        input.mousePressed(trackLeft + 3 + travel / 8, value[1]);
        frame(panel);
        input.mouseReleased(trackLeft + 3 + travel / 8, value[1]);
        frame(panel);

        int picked = results.get(results.size() - 1);
        assertEquals(0, picked % 100);
        assertTrue(picked >= 400 && picked <= 600, Integer.toString(picked));
    }

    @Test
    void checkboxChoiceSwatchesAndCollapsingRespondToClicks() {
        List<Object> results = new ArrayList<>();
        boolean[] sectionOpen = {false};
        Consumer<PanelUi> panel = p -> {
            results.clear();
            results.add(p.checkbox("c", "Enabled", true));
            results.add(p.choice("m", List.of("Loop", "Playlist", "Layers"), 0));
            results.add(p.swatches("s", List.of(-1, 0xFF0000, 0x00FF00), -1));
            sectionOpen[0] = p.collapsing("adv", "Advanced", false);
            if (sectionOpen[0]) {
                p.label("inside");
            }
        };
        frame(panel);
        assertFalse(sectionOpen[0]);
        assertFalse(canvas.texts().contains("inside"));

        press(panel, canvas.rectOf("Enabled"));
        assertEquals(false, results.get(0));
        release(panel);
        press(panel, canvas.rectOf("Layers"));
        assertEquals(2, results.get(1));
        release(panel);
        press(panel, canvas.rectOf("Loop"));
        assertNull(results.get(1));
        release(panel);
        int[] auto = canvas.rectOf("A");
        press(panel, new int[]{auto[0] + 17 * 2 - 1, auto[1] - 1});
        assertEquals(0x00FF00, results.get(2));
        release(panel);
        press(panel, canvas.rectOf("Advanced"));
        assertTrue(sectionOpen[0]);
        release(panel);
        frame(panel);
        assertTrue(canvas.texts().contains("inside"));
    }

    @Test
    void comboFiltersByTypingAndReturnsTheClickedOption() {
        List<String> options = new ArrayList<>();
        for (int index = 0; index < 30; index++) {
            options.add("track-" + index + ".ogg");
        }
        options.add("village/day.ogg");
        List<String> results = new ArrayList<>();
        Consumer<PanelUi> panel = p -> {
            results.add(p.combo("music", "Music", options, "track-0.ogg"));
            p.button("after", "After");
        };
        frame(panel);
        int closedButtonY = canvas.rectOf("After")[1];

        click(panel, canvas.rectOf("track-0.ogg"));
        frame(panel);
        assertTrue(canvas.rectOf("After")[1] > closedButtonY, "the list opens inline and pushes content down");
        assertTrue(canvas.texts().contains("track-7.ogg"));
        assertFalse(canvas.texts().contains("track-8.ogg"));

        for (char character : "DAY".toCharArray()) {
            input.charTyped(character);
        }
        frame(panel);
        frame(panel);
        assertTrue(canvas.texts().contains("village/day.ogg"));
        assertFalse(canvas.texts().contains("track-1.ogg"));

        press(panel, canvas.rectOf("village/day.ogg"));
        assertEquals("village/day.ogg", results.get(results.size() - 1));
        release(panel);
        frame(panel);
        assertEquals(closedButtonY, canvas.rectOf("After")[1]);
        assertFalse(ui.editingText());
    }

    @Test
    void comboClosesWhenClickingOutsideOrPressingEscape() {
        Consumer<PanelUi> panel = p -> {
            p.combo("music", "Music", List.of("a.ogg", "b.ogg"), null);
            p.button("after", "After");
        };
        frame(panel);
        click(panel, canvas.rectOf("…"));
        frame(panel);
        assertTrue(canvas.texts().contains("b.ogg"));
        assertTrue(ui.editingText());
        input.keyPressed(UiInput.Key.ESCAPE);
        frame(panel);
        assertFalse(canvas.texts().contains("b.ogg"));
        assertFalse(ui.editingText());

        click(panel, canvas.rectOf("…"));
        frame(panel);
        click(panel, canvas.rectOf("After"));
        frame(panel);
        assertFalse(canvas.texts().contains("b.ogg"));
    }

    @Test
    void tallContentScrollsWithTheWheelAndStopsAtTheEnds() {
        Consumer<PanelUi> panel = p -> {
            for (int index = 0; index < 40; index++) {
                p.button("b" + index, "Row " + index);
            }
        };
        frame(panel);
        frame(panel);
        int top = canvas.rectOf("Row 0")[1];

        input.mouseMoved(X + 20, 100);
        input.scrolled(-2);
        frame(panel);
        assertTrue(canvas.rectOf("Row 0")[1] < top);

        input.scrolled(-1000);
        frame(panel);
        frame(panel);
        int lastBottom = canvas.rectOf("Row 39")[1] + 16;
        assertTrue(lastBottom <= HEIGHT && lastBottom > HEIGHT - 40, "ends flush with the bottom: " + lastBottom);

        input.scrolled(1000);
        frame(panel);
        assertEquals(top, canvas.rectOf("Row 0")[1]);

        input.mouseMoved(10, 10);
        input.scrolled(-5);
        frame(panel);
        assertEquals(top, canvas.rectOf("Row 0")[1], "wheel outside the panel does not scroll it");
    }

    @Test
    void longTextWrapsInsideThePanelAndLongLabelsAreShortened() {
        frame(p -> {
            p.hint("这是一段很长的提示文字用来测试在面板里面自动换行不会画到面板外面去也不会被截断丢失任何内容");
            p.listRow("row", "an-extremely-long-area-name-that-cannot-possibly-fit-in-the-row", "3 tracks", false, -1);
        });

        for (FakeCanvas.Text text : canvas.drawn) {
            assertTrue(text.x() >= X && text.x() + canvas.textWidth(text.text()) <= X + WIDTH, text.toString());
        }
        assertTrue(canvas.drawn.size() >= 3);
        assertTrue(canvas.texts().stream().anyMatch(text -> text.endsWith("…")));
    }

    @Test
    void wrappingNeverStartsALineWithClosingPunctuation() {
        // 30 characters fill the row exactly, so the comma is the one that overflows.
        frame(p -> p.hint("一".repeat(30) + "，" + "二".repeat(5)));

        assertEquals(List.of("一".repeat(29), "一，" + "二".repeat(5)), canvas.texts());
    }

    @Test
    void caretIsReportedOnlyWhileTypingAndFollowsTheText() {
        Consumer<PanelUi> panel = p -> p.textInput("name", "Name", "old", 20);
        frame(panel);
        assertNull(ui.caret());

        click(panel, canvas.rectOf("old"));
        int[] atStart = ui.caret();
        input.charTyped('a');
        input.charTyped('b');
        frame(panel);
        int[] afterTyping = ui.caret();
        // "old" is replaced by "ab": one character narrower on the six-pixel test font.
        assertEquals(atStart[0] - 6, afterTyping[0]);
        assertEquals(atStart[1], afterTyping[1]);
        assertTrue(afterTyping[2] > 0);

        input.keyPressed(UiInput.Key.ENTER);
        frame(panel);
        frame(panel);
        assertNull(ui.caret());
    }

    @Test
    void headerReportsWhichActionWasClicked() {
        List<Integer> clicks = new ArrayList<>();
        Consumer<PanelUi> panel = p -> clicks.add(p.header("AreaMusic", List.of("Undo", "Redo", "×")));
        frame(panel);
        int[] title = canvas.rectOf("AreaMusic");
        int[] close = canvas.rectOf("×");
        assertEquals(title[1], close[1]);
        assertTrue(close[0] > canvas.rectOf("Redo")[0] && canvas.rectOf("Redo")[0] > canvas.rectOf("Undo")[0]);

        press(panel, canvas.rectOf("Redo"));
        release(panel);
        press(panel, title);

        assertEquals(List.of(-1, 1, -1, -1), clicks);
    }

    @Test
    void panelIsOnlyAsTallAsItsContent() {
        Consumer<PanelUi> panel = p -> p.button("b", "Only");
        frame(panel);
        frame(panel);
        int bottom = canvas.rectOf("Only")[1] + 13 + CanvasUi.PADDING;

        input.mouseMoved(X + 20, bottom - 1);
        frame(panel);
        assertTrue(ui.containsMouse());
        input.mouseMoved(X + 20, bottom);
        frame(panel);
        assertFalse(ui.containsMouse(), "below the card the pointer is over the world");
    }

    @Test
    void listRowShowsItsMetaOnTheRightAndStepsShowEveryLabel() {
        List<Boolean> clicks = new ArrayList<>();
        Consumer<PanelUi> panel = p -> {
            p.steps(List.of("Corner 1", "Corner 2", "Music"), 1);
            clicks.add(p.listRow("row", "Village", "day", true, 0x33AA55));
        };
        frame(panel);
        int[] label = canvas.rectOf("Village");
        int[] meta = canvas.rectOf("day");
        assertEquals(label[1], meta[1]);
        assertTrue(meta[0] > label[0] + label[2]);
        assertTrue(meta[0] + meta[2] <= X + WIDTH);
        assertTrue(canvas.rectOf("Corner 1")[0] < canvas.rectOf("Corner 2")[0]);
        assertTrue(canvas.rectOf("Corner 2")[0] < canvas.rectOf("Music")[0]);

        press(panel, meta);
        assertEquals(List.of(false, true), clicks);
    }

    private void click(Consumer<PanelUi> panel, int[] rect) {
        press(panel, rect);
        release(panel);
    }

    private void press(Consumer<PanelUi> panel, int[] rect) {
        input.mousePressed(rect[0] + 1, rect[1] + 1);
        frame(panel);
    }

    private void release(Consumer<PanelUi> panel) {
        input.mouseReleased(10, 10);
        frame(panel);
    }

    /** A point inside the control that sits to the right of a row label. */
    private int[] fieldBeside(String label) {
        int[] labelRect = canvas.rectOf(label);
        return new int[]{CanvasUi.sliderTrack(X, WIDTH)[0] + 4, labelRect[1]};
    }

    private void frame(Consumer<PanelUi> panel) {
        canvas.drawn.clear();
        ui.begin(canvas, input, X, 0, WIDTH, HEIGHT);
        panel.accept(ui);
        ui.end();
        input.endFrame();
    }

    /** Monospace canvas: every character is 6 wide, lines are 9 high. */
    private static final class FakeCanvas implements UiCanvas {
        private final List<Text> drawn = new ArrayList<>();
        private String clipboard = "";

        private record Text(String text, int x, int y) {
        }

        @Override
        public void fill(int x, int y, int width, int height, int argb) {
        }

        @Override
        public void text(String text, int x, int y, int argb) {
            drawn.add(new Text(text, x, y));
        }

        @Override
        public int textWidth(String text) {
            return text.codePointCount(0, text.length()) * 6;
        }

        @Override
        public int lineHeight() {
            return 9;
        }

        @Override
        public void clip(int x, int y, int width, int height) {
        }

        @Override
        public void unclip() {
        }

        @Override
        public String clipboard() {
            return clipboard;
        }

        private List<String> texts() {
            return drawn.stream().map(Text::text).toList();
        }

        private int[] rectOf(String text) {
            return drawn.stream().filter(candidate -> candidate.text.equals(text)).findFirst()
                    .map(found -> new int[]{found.x, found.y, textWidth(found.text), 9})
                    .orElseThrow(() -> new AssertionError("not drawn: " + text + " in " + texts()));
        }

        /** The text drawn on the same row after the given label. */
        private int[] rectOf(String text, String rowLabel) {
            int y = rectOf(rowLabel)[1];
            return drawn.stream().filter(candidate -> candidate.text.equals(text) && candidate.y == y).findFirst()
                    .map(found -> new int[]{found.x, found.y, textWidth(found.text), 9})
                    .orElseThrow(() -> new AssertionError("not drawn on row " + rowLabel + ": " + texts()));
        }

        private int yOfTextStartingWith(String prefix) {
            return drawn.stream().filter(candidate -> candidate.text.startsWith(prefix)).findFirst()
                    .orElseThrow(() -> new AssertionError("not drawn: " + prefix + " in " + texts())).y;
        }
    }
}
