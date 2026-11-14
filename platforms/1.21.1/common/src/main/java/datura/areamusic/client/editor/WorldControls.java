package datura.areamusic.client.editor;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.Input;
import net.minecraft.client.player.LocalPlayer;

import java.util.Arrays;

/**
 * Keeps the player in control while the editor panel is open: the movement keys still walk, jump
 * and fly, and dragging the mouse anywhere off the panel turns the view. Without this an open
 * screen would freeze the player in place, which makes it hard to look at the area being edited.
 *
 * <p>The game ignores its own movement key bindings while a screen is open (Forge and NeoForge
 * switch them off outright), so for as long as the panel is open the player's input is replaced
 * by one that follows the keys this class has seen pressed.
 */
final class WorldControls {
    private static final int FORWARD = 0;
    private static final int BACK = 1;
    private static final int LEFT = 2;
    private static final int RIGHT = 3;
    private static final int JUMP = 4;
    private static final int SNEAK = 5;
    private static final int SPRINT = 6;
    private static final int NOT_LOOKING = -1;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final boolean[] held = new boolean[7];
    private final Input heldInput = new HeldKeys();
    private LocalPlayer owner;
    private Input original;
    private int lookButton = NOT_LOOKING;
    private boolean windowWasActive;
    private boolean used;

    /** Notes a key press if the key is bound to moving around. */
    void keyPressed(int keyCode, int scanCode) {
        KeyMapping[] keys = movementKeys();
        for (int index = 0; index < keys.length; index++) {
            if (keys[index].matches(keyCode, scanCode)) {
                held[index] = true;
                used = true;
            }
        }
    }

    void keyReleased(int keyCode, int scanCode) {
        KeyMapping[] keys = movementKeys();
        for (int index = 0; index < keys.length; index++) {
            if (keys[index].matches(keyCode, scanCode)) {
                held[index] = false;
            }
        }
    }

    /**
     * Picks up the movement keys that are held right now. Call just before the panel opens, while
     * the game still knows them: opening a screen makes it forget, and the player would stop dead
     * every time the panel came back after picking corners.
     */
    void adoptHeldKeys() {
        KeyMapping[] keys = movementKeys();
        for (int index = 0; index < keys.length; index++) {
            held[index] = keys[index].isDown();
        }
    }

    boolean looking() {
        return lookButton != NOT_LOOKING;
    }

    /** A mouse button went down off the panel: dragging it turns the view until it is released. */
    void startLooking(int button) {
        if (!looking()) {
            lookButton = button;
        }
    }

    void stopLooking(int button) {
        if (button == lookButton) {
            lookButton = NOT_LOOKING;
        }
    }

    /**
     * Turns the view like the game does for a grabbed mouse.
     *
     * @param dragX how far the pointer moved, in GUI pixels
     */
    void dragged(int button, double dragX, double dragY) {
        LocalPlayer player = minecraft.player;
        if (button != lookButton || player == null) {
            return;
        }
        Window window = minecraft.getWindow();
        double sensitivity = minecraft.options.sensitivity().get() * 0.6 + 0.2;
        double scale = sensitivity * sensitivity * sensitivity * 8.0;
        double turnX = dragX * window.getScreenWidth() / window.getGuiScaledWidth() * scale;
        double turnY = dragY * window.getScreenHeight() / window.getGuiScaledHeight() * scale;
        player.turn(turnX, minecraft.options.invertYMouse().get() ? -turnY : turnY);
        used = true;
    }

    /** Call every tick while the panel is open, before the player is ticked. */
    void tick(boolean typing) {
        boolean active = minecraft.isWindowActive();
        if (typing || windowWasActive && !active) {
            // A key released while typing or in another window would never be seen as released.
            Arrays.fill(held, false);
        }
        if (!active) {
            lookButton = NOT_LOOKING;
        }
        windowWasActive = active;

        LocalPlayer player = minecraft.player;
        if (player == null) {
            return;
        }
        if (player.input != heldInput) {
            owner = player;
            original = player.input;
            player.input = heldInput;
        }
        if (held[SPRINT] && held[FORWARD] && !held[BACK] && !player.isSprinting()) {
            // The game reads the sprint key binding directly, which is off while a screen is open.
            player.setSprinting(true);
        }
    }

    boolean attached() {
        return owner != null;
    }

    /**
     * Hands the keyboard back to the game. Keys still held carry over, so walking continues
     * through closing the panel until the key is let go.
     */
    void release() {
        lookButton = NOT_LOOKING;
        if (owner != null && owner.input == heldInput) {
            owner.input = original;
        }
        owner = null;
        original = null;
        KeyMapping[] keys = movementKeys();
        for (int index = 0; index < keys.length; index++) {
            if (held[index]) {
                keys[index].setDown(true);
            }
            held[index] = false;
        }
    }

    /** Whether the player has moved or looked around with the panel open, so no hint is needed. */
    boolean used() {
        return used;
    }

    private KeyMapping[] movementKeys() {
        Options options = minecraft.options;
        return new KeyMapping[]{
                options.keyUp, options.keyDown, options.keyLeft, options.keyRight,
                options.keyJump, options.keyShift, options.keySprint
        };
    }

    /** The same arithmetic as the game's keyboard input, fed from {@link #held}. */
    private final class HeldKeys extends Input {
        @Override
        public void tick(boolean slow, float slowFactor) {
            up = held[FORWARD];
            down = held[BACK];
            left = held[LEFT];
            right = held[RIGHT];
            forwardImpulse = up == down ? 0.0f : up ? 1.0f : -1.0f;
            leftImpulse = left == right ? 0.0f : left ? 1.0f : -1.0f;
            jumping = held[JUMP];
            shiftKeyDown = held[SNEAK];
            if (slow) {
                leftImpulse *= slowFactor;
                forwardImpulse *= slowFactor;
            }
        }
    }
}
