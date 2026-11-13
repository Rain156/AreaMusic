package datura.areamusic.editor;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.editor.EditorOverlay.CornerResult;
import datura.areamusic.net.SyncMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorOverlayTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    private final List<SyncMessage.Serverbound> sent = new ArrayList<>();
    private EditorModel model;

    @BeforeEach
    void setUp() {
        model = new EditorModel(sent::add);
        model.onSnapshot(new SyncMessage.EditorSnapshot(1, List.of(
                area("big", OVERWORLD, 0, 60, 0, 31, 80, 31),
                area("small", OVERWORLD, 10, 62, 10, 14, 66, 14),
                area("far", OVERWORLD, 100, 60, 0, 110, 70, 10),
                area("hell", NETHER, 0, 60, 0, 5, 65, 5)
        ), true, true));
        sent.clear();
    }

    @Test
    void boxesCoverWholeBlocksOfTheCurrentDimensionAndSkipHiddenAreas() {
        model.select("small");
        model.setHidden("far", true);

        List<EditorOverlay.Box> boxes = EditorOverlay.boxes(model, OVERWORLD);

        assertEquals(List.of("big", "small"), boxes.stream().map(EditorOverlay.Box::label).toList());
        EditorOverlay.Box small = boxes.get(1);
        assertEquals(10.0, small.minX());
        assertEquals(15.0, small.maxX());
        assertEquals(67.0, small.maxY());
        assertTrue(small.selected());
        assertFalse(boxes.get(0).selected());
        assertFalse(small.draft());
        assertEquals(List.of("hell"), EditorOverlay.boxes(model, NETHER).stream().map(EditorOverlay.Box::label).toList());
    }

    @Test
    void draftShowsAsOneBlockThenAsTheFullBox() {
        model.startDraft(OVERWORLD);
        assertEquals(3, EditorOverlay.boxes(model, OVERWORLD).size());

        model.draftCorner(2, new AreaPosition(50, 64, 50));
        EditorOverlay.Box single = last(EditorOverlay.boxes(model, OVERWORLD));
        assertTrue(single.draft());
        assertEquals(51.0, single.maxX());
        assertEquals("1 × 1 × 1", single.label());

        model.draftCorner(1, new AreaPosition(40, 70, 55));
        EditorOverlay.Box full = last(EditorOverlay.boxes(model, OVERWORLD));
        assertEquals(40.0, full.minX());
        assertEquals(71.0, full.maxY());
        assertEquals("11 × 7 × 6", full.label());
        assertEquals(1, EditorOverlay.boxes(model, NETHER).size());
    }

    @Test
    void pickPrefersTheNearestBoxAlongTheRay() {
        assertEquals(Optional.of("big"),
                EditorOverlay.pick(model, OVERWORLD, -10, 70, 16, 1, 0, 0, 64));
        assertEquals(Optional.of("far"),
                EditorOverlay.pick(model, OVERWORLD, 130, 65, 5, -1, 0, 0, 64));
        assertEquals(Optional.empty(),
                EditorOverlay.pick(model, OVERWORLD, -10, 70, 16, -1, 0, 0, 64));
        assertEquals(Optional.empty(),
                EditorOverlay.pick(model, OVERWORLD, -10, 200, 16, 1, 0, 0, 64));
        assertEquals(Optional.empty(),
                EditorOverlay.pick(model, OVERWORLD, -100, 70, 16, 1, 0, 0, 64), "beyond reach");
    }

    @Test
    void pickFromInsideChoosesTheSmallestSurroundingArea() {
        assertEquals(Optional.of("small"),
                EditorOverlay.pick(model, OVERWORLD, 12, 64, 12, 0, 1, 0, 64));
        assertEquals(Optional.of("big"),
                EditorOverlay.pick(model, OVERWORLD, 2, 64, 2, 0, -1, 0, 64));
        model.setHidden("small", true);
        assertEquals(Optional.of("big"),
                EditorOverlay.pick(model, OVERWORLD, 12, 64, 12, 0, 1, 0, 64));
        assertEquals(Optional.of("hell"),
                EditorOverlay.pick(model, NETHER, 2, 62, 2, 0, 1, 0, 64));
    }

    @Test
    void cornerClicksWithNothingSelectedDrawANewArea() {
        assertEquals(CornerResult.DRAFT_STARTED,
                EditorOverlay.cornerClicked(model, 1, OVERWORLD, new AreaPosition(1, 2, 3)));
        assertEquals(CornerResult.DRAFT_STARTED,
                EditorOverlay.cornerClicked(model, 1, OVERWORLD, new AreaPosition(4, 5, 6)));
        assertEquals(CornerResult.DRAFT_COMPLETE,
                EditorOverlay.cornerClicked(model, 2, OVERWORLD, new AreaPosition(7, 8, 9)));

        assertEquals(new EditorModel.Draft(OVERWORLD, new AreaPosition(4, 5, 6), new AreaPosition(7, 8, 9)), model.draft());
        assertTrue(sent.isEmpty(), "nothing is sent until the area has music");
    }

    @Test
    void cornerClicksWithAnAreaSelectedMoveItsCorners() {
        model.select("small");

        assertEquals(CornerResult.RESIZED,
                EditorOverlay.cornerClicked(model, 2, OVERWORLD, new AreaPosition(20, 70, 20)));

        AreaDefinition small = model.find("small").orElseThrow();
        assertEquals(new AreaPosition(10, 62, 10), small.pos1());
        assertEquals(new AreaPosition(20, 70, 20), small.pos2());
        assertNull(model.draft());
        assertEquals(1, sent.size());
    }

    @Test
    void cornerClickInAnotherDimensionLeavesTheSelectedAreaAlone() {
        model.select("hell");

        assertEquals(CornerResult.WRONG_DIMENSION,
                EditorOverlay.cornerClicked(model, 1, OVERWORLD, new AreaPosition(1, 2, 3)));

        assertEquals(new AreaPosition(0, 60, 0), model.find("hell").orElseThrow().pos1());
        assertTrue(sent.isEmpty());
    }

    @Test
    void draftRestartsWhenThePlayerChangesDimension() {
        EditorOverlay.cornerClicked(model, 1, OVERWORLD, new AreaPosition(1, 2, 3));

        assertEquals(CornerResult.DRAFT_STARTED,
                EditorOverlay.cornerClicked(model, 2, NETHER, new AreaPosition(4, 5, 6)));

        assertEquals(new EditorModel.Draft(NETHER, null, new AreaPosition(4, 5, 6)), model.draft());
    }

    private static EditorOverlay.Box last(List<EditorOverlay.Box> boxes) {
        return boxes.get(boxes.size() - 1);
    }

    private static AreaDefinition area(String id, String dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        return AreaEdits.single(
                id, dimension, new AreaPosition(x1, y1, z1), new AreaPosition(x2, y2, z2), "day.ogg");
    }
}
