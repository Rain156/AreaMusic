package datura.areamusic.area;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaDefinitionTest {
    private static final ResourceLocation OVERWORLD = ResourceLocation.tryParse("minecraft:overworld");
    private static final ResourceLocation NETHER = ResourceLocation.tryParse("minecraft:the_nether");

    @Test
    void normalizesReversedEndpointsAndIncludesBoundaryBlocks() {
        AreaDefinition area = area("square", new BlockPos(10, 80, 10), new BlockPos(0, 60, 0));

        assertEquals(new BlockPos(0, 60, 0), area.min());
        assertEquals(new BlockPos(10, 80, 10), area.max());
        assertTrue(area.contains(OVERWORLD, new BlockPos(0, 60, 0)));
        assertTrue(area.contains(OVERWORLD, new BlockPos(10, 80, 10)));
        assertFalse(area.contains(OVERWORLD, new BlockPos(11, 80, 10)));
        assertFalse(area.contains(NETHER, new BlockPos(5, 70, 5)));
    }

    @Test
    void calculatesInclusiveBlockVolume() {
        AreaDefinition area = area("cube", new BlockPos(0, 0, 0), new BlockPos(2, 2, 2));

        assertEquals(27L, area.volumeInBlocks());
    }

    @Test
    void defensivelyCopiesTracks() {
        List<AreaTrackDefinition> source = new ArrayList<>(List.of(track("first.ogg")));
        AreaDefinition area = AreaDefinition.create(
                "square", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, source, false, 0);

        source.add(track("second.ogg"));

        assertEquals(List.of(track("first.ogg")), area.tracks());
        assertThrows(UnsupportedOperationException.class, () -> area.tracks().add(track("third.ogg")));
    }

    @Test
    void retainsResumeOnReenter() {
        AreaDefinition area = AreaDefinition.create(
                "square", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, List.of(track("track.ogg")), true, 7);

        assertTrue(area.resumeOnReenter());
        assertEquals(7, area.priority());
    }

    @Test
    void rejectsTrackCountsOutsideInclusiveBounds() {
        List<AreaTrackDefinition> tooMany = IntStream.range(0, 17)
                .mapToObj(index -> track("track-" + index + ".ogg"))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, List.of(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, tooMany, false, 0));
        assertEquals(16, AreaDefinition.MAX_TRACKS);
    }

    @Test
    void rejectsInvalidAreaIds() {
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "Bad ID", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, List.of(track("track.ogg")), false, 0));
    }

    @Test
    void requiresDimensionEndpointsAndTracks() {
        List<AreaTrackDefinition> tracks = List.of(track("track.ogg"));

        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", null, BlockPos.ZERO, BlockPos.ZERO, tracks, false, 0));
        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, null, BlockPos.ZERO, tracks, false, 0));
        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, null, tracks, false, 0));
        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, null, false, 0));
    }

    private static AreaDefinition area(String id, BlockPos pos1, BlockPos pos2) {
        return AreaDefinition.create(
                id, OVERWORLD, pos1, pos2, List.of(track("music/theme.ogg")), false, 0);
    }

    private static AreaTrackDefinition track(String musicId) {
        return new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000);
    }
}
