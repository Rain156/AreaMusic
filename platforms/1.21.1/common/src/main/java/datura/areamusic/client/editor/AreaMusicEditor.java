package datura.areamusic.client.editor;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.vertex.PoseStack;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.client.ClientAreaMusic;
import datura.areamusic.editor.EditorModel;
import datura.areamusic.editor.EditorOverlay;
import datura.areamusic.net.ClientSync;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.PanelHost;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

/**
 * The built-in editor: a key opens the panel, and while edit mode is on, area boxes are drawn in
 * the world and clicks on blocks place corners instead of breaking or placing anything.
 *
 * <p>Loaders register {@link #TOGGLE_KEY} and call the three static hooks; everything else is
 * the same on every loader.
 */
public final class AreaMusicEditor implements PanelHost {
    public static final String KEY_CATEGORY = "key.categories.areamusic";
    public static final KeyMapping TOGGLE_KEY = new KeyMapping(
            "key.areamusic.editor", InputConstants.KEY_F7, KEY_CATEGORY);
    private static final double PICK_RANGE = 96.0;

    private static AreaMusicEditor instance;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ClientSync sync;
    private final EditorModel model;
    private final EditorPanel panel;
    private boolean active;
    private int ticks;

    private AreaMusicEditor(ClientSync sync) {
        this.sync = sync;
        model = sync.editor();
        panel = new EditorPanel(sync, this);
    }

    /** Connects the editor to the client session. Call once the client is set up. */
    public static synchronized void install(ClientAreaMusic client) {
        instance = new AreaMusicEditor(client.sync());
    }

    public static synchronized void uninstall() {
        instance = null;
    }

    /** Call at the start of every client tick, before the game handles its own key presses. */
    public static void onClientTickStart() {
        AreaMusicEditor editor = instance;
        if (editor != null) {
            editor.handleWorldClicks();
        }
    }

    /** Call at the end of every client tick. */
    public static void onClientTickEnd() {
        AreaMusicEditor editor = instance;
        if (editor != null) {
            editor.tick();
        }
    }

    /**
     * Call while the world is being drawn, after translucent blocks.
     *
     * @param poseStack the view transform, not translated to the camera position
     */
    public static void onRenderWorld(PoseStack poseStack, MultiBufferSource buffers, Vec3 camera) {
        AreaMusicEditor editor = instance;
        if (editor != null && editor.active && editor.minecraft.level != null) {
            WorldBoxRenderer.render(
                    poseStack, buffers, camera,
                    EditorOverlay.boxes(editor.model, editor.playerDimension()));
        }
    }

    /** Draws boxes for another front end, such as the Axiom tool. Same contract as above. */
    public static void renderBoxes(
            PoseStack poseStack, MultiBufferSource buffers, Vec3 camera, java.util.List<EditorOverlay.Box> boxes
    ) {
        WorldBoxRenderer.render(poseStack, buffers, camera, boxes);
    }

    void undo() {
        model.undo();
    }

    void redo() {
        model.redo();
    }

    private void tick() {
        if (minecraft.player == null || !sync.serverPresent()) {
            active = false;
            while (TOGGLE_KEY.consumeClick()) {
                // Nothing to open outside a world with the mod on the server.
            }
            return;
        }
        while (TOGGLE_KEY.consumeClick()) {
            if (!sync.canEdit()) {
                hud(Component.translatable("areamusic.editor.no_permission"));
            } else if (minecraft.screen == null) {
                openPanel();
            }
        }
        if (active && minecraft.screen == null && ++ticks % 40 == 1) {
            hud(hint());
        }
    }

    private void openPanel() {
        if (!active) {
            active = true;
            model.open();
        }
        minecraft.setScreen(new AreaMusicEditorScreen(this, panel));
    }

    private Component hint() {
        String key = TOGGLE_KEY.getTranslatedKeyMessage().getString();
        EditorModel.Draft draft = model.draft();
        if (draft != null && !draft.complete()) {
            return Component.translatable("areamusic.hud.draft", key);
        }
        Optional<String> selected = model.selected().map(area -> area.label());
        return selected.isPresent()
                ? Component.translatable("areamusic.hud.selected", selected.get(), key)
                : Component.translatable("areamusic.hud.idle", key);
    }

    /** Turns left, right and middle clicks into editor actions before the game can use them. */
    private void handleWorldClicks() {
        LocalPlayer player = minecraft.player;
        if (!active || minecraft.screen != null || player == null || !model.ready()) {
            return;
        }
        Options options = minecraft.options;
        boolean attack = drain(options.keyAttack);
        boolean use = drain(options.keyUse);
        boolean pick = drain(options.keyPickItem);
        if (!attack && !use && !pick) {
            return;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        if (pick) {
            Optional<String> hit = EditorOverlay.pick(
                    model, playerDimension(), eye.x, eye.y, eye.z, look.x, look.y, look.z, PICK_RANGE);
            model.select(hit.orElse(null));
            hud(hit.flatMap(model::find)
                    .map(area -> Component.translatable("areamusic.hud.picked", area.label()))
                    .orElseGet(() -> Component.translatable("areamusic.hud.picked_none")));
            return;
        }
        HitResult hit = player.pick(PICK_RANGE, 1.0f, false);
        if (!(hit instanceof BlockHitResult blockHit) || hit.getType() != HitResult.Type.BLOCK) {
            hud(Component.translatable("areamusic.hud.no_block"));
            return;
        }
        BlockPos pos = blockHit.getBlockPos();
        int corner = attack ? 1 : 2;
        EditorOverlay.CornerResult result = EditorOverlay.cornerClicked(
                model, corner, playerDimension(), new AreaPosition(pos.getX(), pos.getY(), pos.getZ()));
        switch (result) {
            case DRAFT_COMPLETE -> openPanel();
            case DRAFT_STARTED -> hud(Component.translatable("areamusic.hud.corner_set", corner));
            case RESIZED -> hud(Component.translatable("areamusic.hud.resized", corner));
            case WRONG_DIMENSION -> hud(Component.translatable("areamusic.hud.wrong_dimension"));
        }
    }

    /** Swallows queued clicks and releases the key so holding it does nothing either. */
    private static boolean drain(KeyMapping key) {
        boolean clicked = false;
        while (key.consumeClick()) {
            clicked = true;
        }
        key.setDown(false);
        return clicked;
    }

    private void hud(Component message) {
        minecraft.gui.setOverlayMessage(message, false);
    }

    // ---- PanelHost ----

    @Override
    public String text(String key, Object... args) {
        return I18n.get(key, args);
    }

    @Override
    public String playerDimension() {
        return minecraft.level == null ? "" : minecraft.level.dimension().location().toString();
    }

    @Override
    public AreaPosition playerPosition() {
        BlockPos pos = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
        return new AreaPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public void teleportTo(String areaId) {
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand("areamusic tp " + areaId);
        }
    }

    @Override
    public void pickCornersInWorld() {
        minecraft.setScreen(null);
        ticks = 0;
    }

    @Override
    public void finishEditing() {
        active = false;
        model.close();
        minecraft.setScreen(null);
    }

    @Override
    public boolean crosshairPicking() {
        return true;
    }
}
