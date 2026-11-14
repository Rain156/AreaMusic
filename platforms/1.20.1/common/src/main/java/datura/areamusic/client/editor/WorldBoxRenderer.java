package datura.areamusic.client.editor;

import com.mojang.blaze3d.vertex.PoseStack;
import datura.areamusic.editor.EditorOverlay;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.debug.DebugRenderer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/** Draws area boxes in the world: tinted faces, an outline and the area's name on top. */
final class WorldBoxRenderer {
    private WorldBoxRenderer() {
    }

    static void render(
            PoseStack poseStack, MultiBufferSource buffers, Vec3 camera, List<EditorOverlay.Box> boxes
    ) {
        for (EditorOverlay.Box box : boxes) {
            float red = (box.rgb() >> 16 & 0xFF) / 255.0f;
            float green = (box.rgb() >> 8 & 0xFF) / 255.0f;
            float blue = (box.rgb() & 0xFF) / 255.0f;
            double minX = box.minX() - camera.x;
            double minY = box.minY() - camera.y;
            double minZ = box.minZ() - camera.z;
            double maxX = box.maxX() - camera.x;
            double maxY = box.maxY() - camera.y;
            double maxZ = box.maxZ() - camera.z;
            DebugRenderer.renderFilledBox(
                    poseStack, buffers, minX, minY, minZ, maxX, maxY, maxZ,
                    red, green, blue, box.selected() ? 0.18f : 0.07f);
            LevelRenderer.renderLineBox(
                    poseStack, buffers.getBuffer(RenderType.lines()),
                    minX, minY, minZ, maxX, maxY, maxZ,
                    box.selected() ? 1.0f : red, box.selected() ? 1.0f : green, box.selected() ? 1.0f : blue, 1.0f);
        }
        for (EditorOverlay.Box box : boxes) {
            double centerX = (box.minX() + box.maxX()) / 2.0;
            double centerZ = (box.minZ() + box.maxZ()) / 2.0;
            double labelY = box.contains(camera.x, camera.y, camera.z)
                    ? Math.min(box.maxY() - 0.6, camera.y + 2.0)
                    : box.maxY() + 0.4;
            double distance = Math.sqrt(camera.distanceToSqr(centerX, labelY, centerZ));
            float scale = (float) Math.max(0.02, Math.min(0.4, distance * 0.0028));
            DebugRenderer.renderFloatingText(
                    poseStack, buffers, box.label(), centerX, labelY, centerZ,
                    0xFF000000 | (box.selected() ? 0xFFFFFF : box.rgb()), scale, true, 0.0f, true);
        }
    }
}
