package datura.areamusic.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import datura.areamusic.ui.CanvasUi;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.UiInput;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.IMEPreeditOverlay;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.input.PreeditEvent;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Hosts the editor panel as a floating card at the right edge. The world stays visible and keeps
 * running behind it, with the game's own HUD out of the way, and the player can still walk and
 * look around: see {@link WorldControls}.
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
    private final WorldControls controls;
    private GuiCanvas canvas;
    private boolean textInputOn;
    private IMEPreeditOverlay preedit;

    AreaMusicEditorScreen(AreaMusicEditor editor, EditorPanel panel) {
        super(Component.translatable("areamusic.editor.title"));
        this.editor = editor;
        this.panel = panel;
        controls = editor.controls();
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
        if (!controls.looking()) {
            // While the view is being dragged the pointer is not pointing at anything.
            input.mouseMoved((int) (mouseX / factor), (int) (mouseY / factor));
        }
        ui.begin(canvas, input, uiWidth - panelWidth - MARGIN, MARGIN, panelWidth, uiHeight - MARGIN * 2);
        panel.build(ui);
        ui.end();
        input.endFrame();
        canvas.end();
        updateTextInput(graphics, factor);
    }

    /**
     * The game only delivers typed characters while text input is switched on, which it leaves to
     * whoever owns the focused field. Without this nothing can be typed into the panel at all.
     * The input method is also told where the caret is, and its composition is shown there.
     */
    private void updateTextInput(GuiGraphicsExtractor graphics, float factor) {
        boolean typing = ui.editingText();
        if (typing != textInputOn) {
            textInputOn = typing;
            minecraft.onTextInputFocusChange(this, typing);
        }
        int[] caret = ui.caret();
        if (!typing || caret == null) {
            return;
        }
        int x = Math.round(caret[0] * factor);
        int y = Math.round(caret[1] * factor);
        int height = Math.max(1, Math.round(caret[2] * factor));
        if (preedit == null) {
            minecraft.textInputManager().setTextInputArea(x, y, x + 1, y + height);
        } else {
            preedit.updateInputPosition(x, y);
            graphics.setPreeditOverlay(preedit);
        }
    }

    @Override
    public boolean preeditUpdated(PreeditEvent event) {
        preedit = event == null ? null : new IMEPreeditOverlay(event, font, font.lineHeight + 1);
        return true;
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
    public void tick() {
        controls.tick(ui.editingText());
    }

    @Override
    public void removed() {
        controls.release();
        if (textInputOn) {
            textInputOn = false;
            minecraft.onTextInputFocusChange(this, false);
        }
        editor.panelRemoved();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        float factor = factor();
        int x = (int) (event.x() / factor);
        int y = (int) (event.y() / factor);
        boolean left = event.button() == InputConstants.MOUSE_BUTTON_LEFT;
        input.mouseMoved(x, y);
        boolean onPanel = ui.containsMouse();
        if (left) {
            // Also when it lands off the panel: that is what closes an open field or drop-down.
            input.mousePressed(x, y);
        }
        if (!onPanel && (left
                || event.button() == InputConstants.MOUSE_BUTTON_RIGHT
                || event.button() == InputConstants.MOUSE_BUTTON_MIDDLE)) {
            // Any of the three buttons, so it works whichever one a 3D tool has taught the hand.
            controls.startLooking(event.button());
        }
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        controls.dragged(event.button(), dragX, dragY);
        return true;
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        controls.stopLooking(event.button());
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
            if (ui.editingText()) {
                // Leaves the field or drop-down first; a second press closes the panel.
                input.keyPressed(UiInput.Key.ESCAPE);
            } else {
                onClose();
            }
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
        controls.keyPressed(event);
        return true;
    }

    @Override
    public boolean keyReleased(KeyEvent event) {
        controls.keyReleased(event);
        return true;
    }

    @Override
    public void onFilesDrop(List<Path> files) {
        panel.filesDropped(files);
    }
}
