package datura.areamusic.fabric.axiom;

import com.moulberry.axiomclientapi.CustomTool;
import com.moulberry.axiomclientapi.service.ToolRegistryService;
import com.moulberry.axiomclientapi.service.ToolService;
import com.mojang.blaze3d.vertex.PoseStack;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.client.ClientAreaMusic;
import datura.areamusic.client.editor.AreaMusicEditor;
import datura.areamusic.editor.EditorModel;
import datura.areamusic.editor.EditorOverlay;
import datura.areamusic.net.ClientSync;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.PanelHost;
import datura.areamusic.ui.imgui.ImGuiPanelUi;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;
import org.lwjgl.glfw.GLFWDropCallbackI;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.ServiceLoader;

/**
 * The AreaMusic tool inside Axiom. It shows the same panel as the built-in editor in Axiom's tool
 * options, draws the area boxes, and uses Axiom's right click to place corners.
 */
public final class AreaMusicAxiomTool implements CustomTool, PanelHost {
    private static final double PICK_RANGE = 256.0;
    private static final long ACTIVE_WINDOW_NANOS = 500_000_000L;

    private final Minecraft minecraft = Minecraft.getInstance();
    private final ToolService tools;
    private final ClientSync sync;
    private final EditorModel model;
    private final EditorPanel panel;
    private final ImGuiPanelUi ui = new ImGuiPanelUi();
    private int nextCorner = 1;
    private long lastShown;
    private boolean acceptingDrops;

    private AreaMusicAxiomTool(ClientAreaMusic client, ToolService tools) {
        this.tools = tools;
        sync = client.sync();
        model = sync.editor();
        panel = new EditorPanel(sync, this);
    }

    /** Called reflectively by {@link AxiomIntegration} once Axiom is known to be present. */
    public static void register(ClientAreaMusic client) {
        ToolRegistryService registry = ServiceLoader.load(ToolRegistryService.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("Axiom did not provide its tool registry"));
        ToolService tools = ServiceLoader.load(ToolService.class).findFirst()
                .orElseThrow(() -> new IllegalStateException("Axiom did not provide its tool service"));
        registry.register(new AreaMusicAxiomTool(client, tools));
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
        lastShown = System.nanoTime();
        if (!acceptingDrops) {
            // The window does not exist yet when the tool is registered, so hook it up here.
            acceptingDrops = true;
            acceptDroppedFiles();
        }
        if (sync.canEdit() && !model.isOpen()) {
            model.open();
        }
        panel.build(ui);
    }

    @Override
    public void render(Camera camera, float tickDelta, long time, PoseStack poseStack, Matrix4f projection) {
        if (!model.ready() || minecraft.level == null) {
            return;
        }
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        AreaMusicEditor.renderBoxes(
                poseStack, buffers, camera.getPosition(), EditorOverlay.boxes(model, playerDimension()));
        buffers.endBatch();
    }

    @Override
    public boolean callUseTool() {
        if (!model.ready()) {
            return false;
        }
        BlockHitResult hit = tools.raycastBlock();
        if (hit == null) {
            Vec3 eye = minecraft.gameRenderer.getMainCamera().getPosition();
            Vec3 look = tools.getLookDirection();
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

    /**
     * Axiom's editor is not a vanilla screen, so files dropped on the window never reach one. This
     * chains onto the window's drop handler and takes the files while the tool is on screen.
     */
    private void acceptDroppedFiles() {
        long window = minecraft.getWindow().getWindow();
        GLFWDropCallbackI[] previous = new GLFWDropCallbackI[1];
        previous[0] = GLFW.glfwSetDropCallback(window, (handle, count, names) -> {
            if (previous[0] != null) {
                previous[0].invoke(handle, count, names);
            }
            if (minecraft.screen != null || System.nanoTime() - lastShown > ACTIVE_WINDOW_NANOS) {
                return;
            }
            List<Path> files = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                files.add(Path.of(GLFWDropCallback.getName(names, index)));
            }
            minecraft.execute(() -> panel.filesDropped(files));
        });
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
}
