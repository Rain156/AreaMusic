package datura.areamusic.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * Input collected between two frames. The hosting screen forwards raw events here; {@link CanvasUi}
 * reads them while the panel is built and {@link #endFrame()} clears the one-shot parts.
 */
public final class UiInput {
    public enum Key {
        BACKSPACE, ENTER, ESCAPE, TAB, PASTE, UNDO, REDO
    }

    int mouseX;
    int mouseY;
    boolean mouseDown;
    boolean pressed;
    boolean released;
    double scroll;
    final StringBuilder typed = new StringBuilder();
    final List<Key> keys = new ArrayList<>();

    public void mouseMoved(int x, int y) {
        mouseX = x;
        mouseY = y;
    }

    public void mousePressed(int x, int y) {
        mouseMoved(x, y);
        mouseDown = true;
        pressed = true;
    }

    public void mouseReleased(int x, int y) {
        mouseMoved(x, y);
        mouseDown = false;
        released = true;
    }

    public void scrolled(double amount) {
        scroll += amount;
    }

    public void charTyped(int codePoint) {
        if (!Character.isISOControl(codePoint)) {
            typed.appendCodePoint(codePoint);
        }
    }

    public void keyPressed(Key key) {
        keys.add(key);
    }

    public void endFrame() {
        pressed = false;
        released = false;
        scroll = 0;
        typed.setLength(0);
        keys.clear();
    }
}
