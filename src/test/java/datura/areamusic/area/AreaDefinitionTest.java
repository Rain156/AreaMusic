package datura.areamusic.area;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

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
    void rejectsInvalidEditableFields() {
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "Bad ID", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, "track.ogg", 0, 1.0f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, " ", 0, 1.0f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, "track.ogg", 0, 1.01f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, "track.ogg", 0, Float.NaN, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, "track.ogg", 0, 1.0f, true, -1, 2000));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, BlockPos.ZERO, BlockPos.ZERO, "track.ogg", 0, 1.0f, true, 2000, 60_001));
    }

    private static AreaDefinition area(String id, BlockPos pos1, BlockPos pos2) {
        return AreaDefinition.create(id, OVERWORLD, pos1, pos2, "music/theme.ogg", 0, 1.0f, true, 2000, 2000);
    }
}
