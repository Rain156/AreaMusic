package datura.areamusic.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import datura.areamusic.ui.CanvasUi;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.UiInput;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

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
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
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
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        // No dimming or blur: the world behind the panel is what is being edited.
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            input.mousePressed((int) event.x(), (int) event.y());
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            input.mouseReleased((int) event.x(), (int) event.y());
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        input.scrolled(scrollY);
        return true;
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        input.charTyped(event.codepoint());
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.isEscape()) {
            if (ui.dismiss()) {
                return true;
            }
            onClose();
            return true;
        }
        if (AreaMusicEditor.TOGGLE_KEY.matches(event) && !ui.editingText()) {
            onClose();
            return true;
        }
        if (ui.editingText()) {
            if (event.key() == InputConstants.KEY_BACKSPACE) {
                input.keyPressed(UiInput.Key.BACKSPACE);
            } else if (event.isConfirmation()) {
                input.keyPressed(UiInput.Key.ENTER);
            } else if (event.key() == InputConstants.KEY_TAB) {
                input.keyPressed(UiInput.Key.TAB);
            } else if (event.isPaste()) {
                input.keyPressed(UiInput.Key.PASTE);
            }
            return true;
        }
        if (event.hasControlDownWithQuirk() && event.key() == InputConstants.KEY_Z) {
            editor.undo();
            return true;
        }
        if (event.hasControlDownWithQuirk() && event.key() == InputConstants.KEY_Y) {
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
