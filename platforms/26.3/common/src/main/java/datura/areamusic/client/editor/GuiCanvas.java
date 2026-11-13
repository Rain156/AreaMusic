package datura.areamusic.client.editor;

import datura.areamusic.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws the editor panel with the vanilla GUI renderer. */
final class GuiCanvas implements UiCanvas {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Font font = minecraft.font;
    private GuiGraphicsExtractor graphics;

    void target(GuiGraphicsExtractor graphics) {
        this.graphics = graphics;
    }

    @Override
    public void fill(int x, int y, int width, int height, int argb) {
        graphics.fill(x, y, x + width, y + height, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        graphics.text(font, text, x, y, argb, false);
    }

    @Override
    public int textWidth(String text) {
        return font.width(text);
    }

    @Override
    public int lineHeight() {
        return font.lineHeight;
    }

    @Override
    public void clip(int x, int y, int width, int height) {
        graphics.enableScissor(x, y, x + width, y + height);
    }

    @Override
    public void unclip() {
        graphics.disableScissor();
    }

    @Override
    public String clipboard() {
        String text = minecraft.keyboardHandler.getClipboard();
        return text == null ? "" : text;
    }
}
