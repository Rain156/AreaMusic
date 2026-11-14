package datura.areamusic.client.editor;

import datura.areamusic.editor.EditorOverlay;
import net.minecraft.gizmos.GizmoStyle;
import net.minecraft.gizmos.Gizmos;
import net.minecraft.gizmos.TextGizmo;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Draws area boxes in the world as gizmos: tinted faces, an outline and the area's name on top.
 * Gizmos emitted during a client tick stay on screen until the next tick, so this is called once
 * per tick rather than once per frame.
 */
final class WorldBoxRenderer {
    private WorldBoxRenderer() {
    }

    static void emit(Vec3 camera, List<EditorOverlay.Box> boxes) {
        for (EditorOverlay.Box box : boxes) {
            int stroke = 0xFF000000 | (box.selected() ? 0xFFFFFF : box.rgb());
            int fill = (box.selected() ? 0x2E000000 : 0x12000000) | box.rgb();
            Gizmos.cuboid(
                    new AABB(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()),
                    GizmoStyle.strokeAndFill(stroke, box.selected() ? 3.0f : 2.0f, fill));

            double centerX = (box.minX() + box.maxX()) / 2.0;
            double centerZ = (box.minZ() + box.maxZ()) / 2.0;
            double labelY = box.contains(camera.x, camera.y, camera.z)
                    ? Math.min(box.maxY() - 0.6, camera.y + 2.0)
                    : box.maxY() + 0.4;
            double distance = Math.sqrt(camera.distanceToSqr(centerX, labelY, centerZ));
            float scale = (float) Math.max(0.5, Math.min(10.0, distance * 0.072));
            Gizmos.billboardText(
                    box.label(), new Vec3(centerX, labelY, centerZ),
                    TextGizmo.Style.forColorAndCentered(stroke).withScale(scale)
            ).setAlwaysOnTop();
        }
    }
}
