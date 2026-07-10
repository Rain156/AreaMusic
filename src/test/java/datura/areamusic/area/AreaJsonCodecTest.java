package datura.areamusic.area;

import com.google.gson.JsonParseException;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.StringReader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaJsonCodecTest {
    private final AreaJsonCodec codec = new AreaJsonCodec();

    @Test
    void readsRequiredFieldsAndAppliesPlaybackDefaults() {
        AreaDefinition area = codec.read("square", new StringReader("""
                {
                  "schemaVersion": 1,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 10, "y": 80, "z": 10 },
                  "pos2": { "x": 0, "y": 60, "z": 0 },
                  "musicId": "村庄/day.mp3"
                }
                """));

        assertEquals(new BlockPos(0, 60, 0), area.min());
        assertEquals(new BlockPos(10, 80, 10), area.max());
        assertEquals(ResourceLocation.tryParse("minecraft:overworld"), area.dimension());
        assertEquals("村庄/day.mp3", area.musicId());
        assertEquals(0, area.priority());
        assertEquals(1.0f, area.volume());
        assertTrue(area.loop());
        assertEquals(2000, area.fadeInMs());
        assertEquals(2000, area.fadeOutMs());
    }

    @Test
    void prettyPrintedOutputRoundTripsEveryField() {
        AreaDefinition original = AreaDefinition.create(
                "boss", ResourceLocation.tryParse("minecraft:the_nether"),
                new BlockPos(-5, 10, -4), new BlockPos(8, 90, 7),
                "boss/fight.flac", 12, 0.65f, false, 750, 3500);

        String json = codec.write(original);
        AreaDefinition decoded = codec.read("boss", new StringReader(json));

        assertTrue(json.contains("\n"));
        assertFalse(decoded.loop());
        assertEquals(original, decoded);
    }

    @Test
    void rejectsUnknownFieldsAndInvalidScalarTypes() {
        String unknown = validJson().replace("\"musicId\": \"track.ogg\"", "\"musicId\": \"track.ogg\", \"volum\": 1");
        String fractionalCoordinate = validJson().replace("\"x\": 0", "\"x\": 0.5");
        String wrongVersion = validJson().replace("\"schemaVersion\": 1", "\"schemaVersion\": 2");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(unknown)));
        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(fractionalCoordinate)));
        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(wrongVersion)));
    }

    private static String validJson() {
        return """
                {
                  "schemaVersion": 1,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 0, "y": 64, "z": 0 },
                  "pos2": { "x": 10, "y": 80, "z": 10 },
                  "musicId": "track.ogg"
                }
                """;
    }
}
