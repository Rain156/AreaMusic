package datura.areamusic.client.editor;

import datura.areamusic.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Draws the editor panel with the vanilla GUI renderer, at the panel's own scale so that it stays
 * compact however large the game's GUI scale is.
 */
final class GuiCanvas implements UiCanvas {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Font font = minecraft.font;
    private GuiGraphicsExtractor graphics;

    /** @param factor how many GUI pixels one panel pixel covers */
    void begin(GuiGraphicsExtractor graphics, float factor) {
        this.graphics = graphics;
        graphics.pose().pushMatrix();
        graphics.pose().scale(factor, factor);
    }

    void end() {
        graphics.pose().popMatrix();
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
        // The scissor rectangle goes through the current transform, so panel pixels are right.
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
