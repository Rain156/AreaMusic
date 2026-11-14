package datura.areamusic.editor;

import datura.areamusic.area.AreaBox;
import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * What the editor shows and reacts to inside the world, as plain numbers: boxes to draw, which
 * area a line of sight hits, and what a corner click means. Each Minecraft version only has to
 * draw the boxes and report clicks.
 */
public final class EditorOverlay {
    private static final int DRAFT_COLOR = 0xFFFFFF;

    private EditorOverlay() {
    }

    /** A box in world coordinates; max is exclusive, so a one-block box spans exactly one unit. */
    public record Box(
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            int rgb, boolean selected, boolean draft, String label
    ) {
        public boolean contains(double x, double y, double z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    public enum CornerResult {
        /** The first corner of a new area was placed. */
        DRAFT_STARTED,
        /** Both corners of the new area are set; it only needs music now. */
        DRAFT_COMPLETE,
        /** A corner of the selected area moved. */
        RESIZED,
        /** The selected area is in another dimension; nothing changed. */
        WRONG_DIMENSION,
        /** The second corner of a further box was set; the box now belongs to its area. */
        BOX_ADDED
    }

    /** Everything to draw in {@code dimension}: visible areas, then the area being drawn. */
    public static List<Box> boxes(EditorModel model, String dimension) {
        List<Box> boxes = new ArrayList<>();
        for (AreaDefinition area : model.areas()) {
            if (!area.dimension().equals(dimension) || model.hidden(area.id())) {
                continue;
            }
            // Every box of an area in its color; the name goes on the first only.
            boolean first = true;
            for (AreaBox part : area.boxes()) {
                boxes.add(box(
                        part.pos1(), part.pos2(), area.effectiveColor(),
                        area.id().equals(model.selectedId()), false, first ? area.label() : ""));
                first = false;
            }
        }
        EditorModel.Draft draft = model.draft();
        if (draft != null && draft.dimension().equals(dimension)) {
            AreaPosition first = draft.pos1() != null ? draft.pos1() : draft.pos2();
            AreaPosition second = draft.pos2() != null ? draft.pos2() : draft.pos1();
            if (first != null) {
                boxes.add(box(first, second, DRAFT_COLOR, true, true, size(first, second)));
            }
        }
        return boxes;
    }

    /**
     * The area a ray hits first. Standing inside areas, the smallest one around the eye wins, so
     * nested areas can always be picked from within.
     */
    public static Optional<String> pick(
            EditorModel model, String dimension,
            double originX, double originY, double originZ,
            double directionX, double directionY, double directionZ,
            double maxDistance
    ) {
        String best = null;
        double bestDistance = Double.MAX_VALUE;
        double bestVolume = Double.MAX_VALUE;
        for (AreaDefinition area : model.areas()) {
            if (!area.dimension().equals(dimension) || model.hidden(area.id())) {
                continue;
            }
            double distance = -1.0;
            for (AreaBox part : area.boxes()) {
                Box box = box(part.pos1(), part.pos2(), 0, false, false, "");
                double toPart = box.contains(originX, originY, originZ)
                        ? 0.0
                        : entryDistance(box, originX, originY, originZ, directionX, directionY, directionZ);
                if (toPart >= 0.0 && (distance < 0.0 || toPart < distance)) {
                    distance = toPart;
                }
            }
            if (distance < 0.0 || distance > maxDistance) {
                continue;
            }
            double volume = area.volumeInBlocks();
            if (distance < bestDistance || distance == bestDistance && volume < bestVolume) {
                best = area.id();
                bestDistance = distance;
                bestVolume = volume;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * A corner click on a block. With an area being drawn it sets that corner; with an area
     * selected it moves that area's corner; otherwise it starts a new area.
     *
     * @param corner 1 or 2
     */
    public static CornerResult cornerClicked(
            EditorModel model, int corner, String dimension, AreaPosition block
    ) {
        if (model.draft() == null) {
            AreaDefinition selected = model.selected().orElse(null);
            // Background music for a whole dimension has no corners; a click starts a new area.
            if (selected != null && !selected.wholeDimension()) {
                if (!selected.dimension().equals(dimension)) {
                    return CornerResult.WRONG_DIMENSION;
                }
                model.apply(area -> corner == 1
                        ? area.withBounds(block, area.pos2())
                        : area.withBounds(area.pos1(), block));
                return CornerResult.RESIZED;
            }
            model.startDraft(dimension);
        }
        if (!model.draft().dimension().equals(dimension)) {
            if (model.draft().extraFor() != null) {
                return CornerResult.WRONG_DIMENSION;
            }
            model.startDraft(dimension);
        }
        if (model.draftCorner(corner, block)) {
            return CornerResult.BOX_ADDED;
        }
        return model.draft().complete() ? CornerResult.DRAFT_COMPLETE : CornerResult.DRAFT_STARTED;
    }

    private static Box box(
            AreaPosition first, AreaPosition second, int rgb, boolean selected, boolean draft, String label
    ) {
        return new Box(
                Math.min(first.x(), second.x()), Math.min(first.y(), second.y()), Math.min(first.z(), second.z()),
                Math.max(first.x(), second.x()) + 1.0, Math.max(first.y(), second.y()) + 1.0,
                Math.max(first.z(), second.z()) + 1.0,
                rgb, selected, draft, label);
    }

    /** Distance along the ray to where it enters the box, or -1 when it misses. */
    private static double entryDistance(
            Box box, double ox, double oy, double oz, double dx, double dy, double dz
    ) {
        double near = 0.0;
        double far = Double.MAX_VALUE;
        double[] origin = {ox, oy, oz};
        double[] direction = {dx, dy, dz};
        double[] min = {box.minX(), box.minY(), box.minZ()};
        double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        for (int axis = 0; axis < 3; axis++) {
            if (Math.abs(direction[axis]) < 1.0e-9) {
                if (origin[axis] < min[axis] || origin[axis] > max[axis]) {
                    return -1.0;
                }
                continue;
            }
            double first = (min[axis] - origin[axis]) / direction[axis];
            double second = (max[axis] - origin[axis]) / direction[axis];
            near = Math.max(near, Math.min(first, second));
            far = Math.min(far, Math.max(first, second));
            if (near > far) {
                return -1.0;
            }
        }
        return near;
    }

    private static String size(AreaPosition first, AreaPosition second) {
        return (Math.abs((long) first.x() - second.x()) + 1) + " × "
                + (Math.abs((long) first.y() - second.y()) + 1) + " × "
                + (Math.abs((long) first.z() - second.z()) + 1);
    }
}
