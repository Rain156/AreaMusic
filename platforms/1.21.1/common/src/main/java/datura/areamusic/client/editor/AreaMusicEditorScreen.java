package datura.areamusic.client.editor;

import datura.areamusic.ui.CanvasUi;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.UiInput;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Path;
import java.util.List;

/**
 * Hosts the editor panel on the right edge of the screen. The world stays visible and keeps
 * running; closing the screen leaves edit mode on so corners can be clicked in the world.
 */
final class AreaMusicEditorScreen extends Screen {
    private final AreaMusicEditor editor;
    private final EditorPanel panel;
    private final CanvasUi ui = new CanvasUi();
    private final UiInput input = new UiInput();
    private GuiCanvas canvas;

    AreaMusicEditorScreen(AreaMusicEditor editor, EditorPanel panel) {
        super(Component.translatable("areamusic.editor.title"));
        this.editor = editor;
        this.panel = panel;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (canvas == null) {
            canvas = new GuiCanvas();
        }
        canvas.target(graphics);
        input.mouseMoved(mouseX, mouseY);
        int panelWidth = Math.max(170, Math.min(250, width * 2 / 5));
        ui.begin(canvas, input, width - panelWidth, 0, panelWidth, height);
        panel.build(ui);
        ui.end();
        input.endFrame();
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // No dimming or blur: the world behind the panel is what is being edited.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            input.mousePressed((int) mouseX, (int) mouseY);
        }
        return true;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            input.mouseReleased((int) mouseX, (int) mouseY);
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        input.scrolled(scrollY);
        return true;
    }

    @Override
    public boolean charTyped(char character, int modifiers) {
        input.charTyped(character);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            if (ui.dismiss()) {
                return true;
            }
            onClose();
            return true;
        }
        if (AreaMusicEditor.TOGGLE_KEY.matches(keyCode, scanCode) && !ui.editingText()) {
            onClose();
            return true;
        }
        if (ui.editingText()) {
            if (keyCode == GLFW.GLFW_KEY_BACKSPACE) {
                input.keyPressed(UiInput.Key.BACKSPACE);
            } else if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                input.keyPressed(UiInput.Key.ENTER);
            } else if (keyCode == GLFW.GLFW_KEY_TAB) {
                input.keyPressed(UiInput.Key.TAB);
            } else if (Screen.isPaste(keyCode)) {
                input.keyPressed(UiInput.Key.PASTE);
            }
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_Z) {
            editor.undo();
            return true;
        }
        if (Screen.hasControlDown() && keyCode == GLFW.GLFW_KEY_Y) {
            editor.redo();
            return true;
        }
        return true;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        panel.filesDropped(files);
    }
}
