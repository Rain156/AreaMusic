package datura.areamusic.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
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
 * Hosts the editor panel as a floating card at the right edge. The world stays visible and keeps
 * running behind it, with the game's own HUD out of the way.
 */
final class AreaMusicEditorScreen extends Screen {
    /** The panel is laid out as if the screen were at least this many panel pixels wide. */
    private static final int REFERENCE_WIDTH = 720;
    private static final int PANEL_WIDTH = 216;
    private static final int MARGIN = 8;

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
        float factor = factor();
        int uiWidth = Math.round(width / factor);
        int uiHeight = Math.round(height / factor);
        int panelWidth = Math.min(PANEL_WIDTH, uiWidth - MARGIN * 2);
        canvas.begin(graphics, factor);
        input.mouseMoved((int) (mouseX / factor), (int) (mouseY / factor));
        ui.begin(canvas, input, uiWidth - panelWidth - MARGIN, MARGIN, panelWidth, uiHeight - MARGIN * 2);
        panel.build(ui);
        ui.end();
        input.endFrame();
        canvas.end();
    }

    /**
     * GUI pixels per panel pixel. The panel uses whole device pixels, and fewer of them than the
     * game's GUI scale when that keeps it from taking over a wide screen.
     */
    private float factor() {
        Window window = minecraft.getWindow();
        double guiScale = window.getGuiScale();
        double devicePixels = Math.max(1.0, Math.min(guiScale, Math.floor(window.getWidth() / (double) REFERENCE_WIDTH)));
        return (float) (devicePixels / guiScale);
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
    public void removed() {
        editor.panelRemoved();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            float factor = factor();
            input.mousePressed((int) (event.x() / factor), (int) (event.y() / factor));
        }
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            float factor = factor();
            input.mouseReleased((int) (event.x() / factor), (int) (event.y() / factor));
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
