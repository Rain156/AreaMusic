package datura.areamusic.fabric.axiom;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.IAxiomWorldRenderContext;
import com.moulberry.axiomclientapi.service.ToolRegistryService;
import com.moulberry.axiomclientapi.service.ToolService;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.client.ClientAreaMusic;
import datura.areamusic.client.editor.AreaMusicEditor;
import datura.areamusic.editor.EditorModel;
import datura.areamusic.editor.EditorOverlay;
import datura.areamusic.net.ClientSync;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.PanelHost;
import datura.areamusic.ui.PanelUi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The AreaMusic tool inside Axiom. It shows the same panel as the built-in editor in Axiom's tool
 * options, keeps the area boxes on screen, and uses Axiom's right click to place corners.
 *
 * <p>Unlike on older game versions, audio files cannot be dropped onto the window while Axiom's
 * editor is open; open the built-in panel to upload.
 */
public final class AreaMusicAxiomTool implements CustomTool, PanelHost {
    private static final double PICK_RANGE = 256.0;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ToolService tools;
    private final ClientAreaMusic client;
    private final ClientSync sync;
    private final EditorModel model;
    private final EditorPanel panel;
    private final PanelUi ui;
    private int nextCorner = 1;

    private AreaMusicAxiomTool(ClientAreaMusic client, ToolService tools, PanelUi ui) {
        this.tools = tools;
        this.ui = ui;
        this.client = client;
        sync = client.sync();
        model = sync.editor();
        panel = new EditorPanel(sync, this);
    }

    /** Called reflectively by {@link AxiomIntegration} once Axiom is known to be present. */
    public static void register(ClientAreaMusic client) throws ReflectiveOperationException, java.io.IOException {
        ToolRegistryService registry = ServiceLoader.load(ToolRegistryService.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("Axiom did not provide its tool registry"));
        ToolService tools = ServiceLoader.load(ToolService.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("Axiom did not provide its tool service"));
        registry.register(new AreaMusicAxiomTool(client, tools, AxiomImGui.createPanelUi()));
    }

    @Override
    public String name() {
        return "AreaMusic";
    }

    @Override
    public void reset() {
        nextCorner = 1;
    }

    @Override
    public void displayImguiOptions() {
        if (sync.canEdit() && !model.isOpen()) {
            model.open();
        }
        panel.build(ui);
    }

    @Override
    public void render(IAxiomWorldRenderContext context) {
        AreaMusicEditor.keepBoxesVisible();
    }

    @Override
    public boolean callUseTool() {
        if (!model.ready()) {
            return false;
        }
        BlockHitResult hit = tools.raycastBlock();
        if (hit == null) {
            Vec3 eye = minecraft.gameRenderer.mainCamera().position();
            Vec3 look = tools.getLookDirection();
            if (look == null) {
                // Axiom has no pointer in the world right now, so there is nothing to pick.
                return false;
            }
            Optional<String> area = EditorOverlay.pick(
                    model, playerDimension(), eye.x, eye.y, eye.z, look.x, look.y, look.z, PICK_RANGE);
            model.select(area.orElse(null));
            return true;
        }
        BlockPos pos = hit.getBlockPos();
        EditorOverlay.cornerClicked(
                model, nextCorner, playerDimension(), new AreaPosition(pos.getX(), pos.getY(), pos.getZ()));
        nextCorner = nextCorner == 1 ? 2 : 1;
        return true;
    }

    @Override
    public boolean callDelete() {
        if (model.selected().isEmpty()) {
            return false;
        }
        model.deleteSelected();
        return true;
    }

    // ---- PanelHost ----

    @Override
    public String text(String key, Object... args) {
        return I18n.get(key, args);
    }

    @Override
    public String playerDimension() {
        return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    }

    @Override
    public AreaPosition playerPosition() {
        BlockPos pos = minecraft.player == null ? BlockPos.ZERO : minecraft.player.blockPosition();
        return new AreaPosition(pos.getX(), pos.getY(), pos.getZ());
    }

    @Override
    public int minBuildY() {
        return minecraft.level == null ? Integer.MIN_VALUE : minecraft.level.getMinY();
    }

    @Override
    public int maxBuildY() {
        return minecraft.level == null ? Integer.MAX_VALUE : minecraft.level.getMaxY();
    }

    @Override
    public void teleportTo(String areaId) {
        if (minecraft.player != null) {
            minecraft.player.connection.sendCommand("areamusic tp " + areaId);
        }
    }

    @Override
    public void pickCornersInWorld() {
        nextCorner = 1;
    }

    @Override
    public void finishEditing() {
        model.close();
    }

    @Override
    public boolean crosshairPicking() {
        return false;
    }

    @Override
    public boolean closable() {
        return false;
    }

    @Override
    public java.util.OptionalInt trackDurationMs(String musicId) {
        return client.trackDurationMs(musicId);
    }
}
