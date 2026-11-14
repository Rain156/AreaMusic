package datura.areamusic.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import datura.areamusic.ui.UiCanvas;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * Draws the editor panel with the vanilla GUI renderer, at the panel's own scale so that it stays
 * compact however large the game's GUI scale is.
 */
final class GuiCanvas implements UiCanvas {
    private final Minecraft minecraft = Minecraft.getInstance();
    private final Font font = minecraft.font;
    private GuiGraphics graphics;
    private float factor = 1.0f;

    /** @param factor how many GUI pixels one panel pixel covers */
    void begin(GuiGraphics graphics, float factor) {
        this.graphics = graphics;
        this.factor = factor;
        graphics.pose().pushPose();
        graphics.pose().scale(factor, factor, 1.0f);
    }

    void end() {
        graphics.pose().popPose();
    }

    @Override
    public void fill(int x, int y, int width, int height, int argb) {
        graphics.fill(x, y, x + width, y + height, argb);
    }

    @Override
    public void text(String text, int x, int y, int argb) {
        graphics.drawString(font, text, x, y, argb, false);
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
        // Scissor rectangles are given in unscaled GUI pixels, whatever the current transform is.
        PoseStack pose = graphics.pose();
        pose.pushPose();
        pose.setIdentity();
        graphics.enableScissor(
                (int) Math.floor(x * factor), (int) Math.floor(y * factor),
                (int) Math.ceil((x + width) * factor), (int) Math.ceil((y + height) * factor));
        pose.popPose();
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
