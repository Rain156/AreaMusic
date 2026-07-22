package datura.areamusic.area;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaJsonCodecTest {
    private static final Set<String> V2_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "tracks", "resumeOnReenter", "priority"
    );
    private static final Set<String> TRACK_FIELDS = Set.of(
            "musicId", "delaySeconds", "volume", "loop", "fadeInMs", "fadeOutMs"
    );

    private final AreaJsonCodec codec = new AreaJsonCodec();

    @Test
    void readsLegacyV1AsOneTrackWithDefaults() {
        AreaDefinition area = codec.read("square", new StringReader(v1Json("")));

        assertEquals(new BlockPos(0, 60, 0), area.min());
        assertEquals(new BlockPos(10, 80, 10), area.max());
        assertEquals(ResourceLocation.tryParse("minecraft:overworld"), area.dimension());
        assertEquals(List.of(new AreaTrackDefinition("村庄/day.mp3", 0, 1.0f, true, 2000, 2000)),
                area.tracks());
        assertFalse(area.resumeOnReenter());
        assertEquals(0, area.priority());
    }

    @Test
    void retainsExplicitLegacyV1PlaybackFields() {
        AreaDefinition area = codec.read("square", new StringReader(v1Json("""
                ,
                  "priority": 4,
                  "volume": 0.35,
                  "loop": false,
                  "fadeInMs": 750,
                  "fadeOutMs": 3500
                """)));

        assertEquals(List.of(new AreaTrackDefinition("村庄/day.mp3", 0, 0.35f, false, 750, 3500)),
                area.tracks());
        assertFalse(area.resumeOnReenter());
        assertEquals(4, area.priority());
    }

    @Test
    void readsOrderedV2TracksWithDefaultsAndAllowsDuplicates() {
        AreaDefinition area = codec.read("square", new StringReader(v2Json("""
                [
                    { "musicId": "ambient.ogg" },
                    {
                      "musicId": "music.ogg",
                      "delaySeconds": 3,
                      "volume": 0.4,
                      "loop": false,
                      "fadeInMs": 250,
                      "fadeOutMs": 900
                    },
                    { "musicId": "ambient.ogg" }
                  ]
                """, ",\n  \"priority\": 5")));

        assertEquals(List.of(
                new AreaTrackDefinition("ambient.ogg", 0, 1.0f, true, 2000, 2000),
                new AreaTrackDefinition("music.ogg", 3, 0.4f, false, 250, 900),
                new AreaTrackDefinition("ambient.ogg", 0, 1.0f, true, 2000, 2000)
        ), area.tracks());
        assertFalse(area.resumeOnReenter());
        assertEquals(5, area.priority());
    }

    @Test
    void acceptsSixteenV2TracksAndExplicitResume() {
        String tracks = IntStream.range(0, 16)
                .mapToObj(index -> "{ \"musicId\": \"track-" + index + ".ogg\" }")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        AreaDefinition area = codec.read("square", new StringReader(
                v2Json(tracks, ",\n  \"resumeOnReenter\": true")));

        assertEquals(16, area.tracks().size());
        assertTrue(area.resumeOnReenter());
    }

    @Test
    void writerAlwaysEmitsFormattedV2TrackSchema() {
        AreaDefinition area = area(true);

        String json = codec.write(area);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject firstTrack = root.getAsJsonArray("tracks").get(0).getAsJsonObject();

        assertTrue(json.startsWith("{\n  \"schemaVersion\": 2,"));
        assertTrue(json.contains("\n  \"tracks\": [\n    {"));
        assertEquals(2, root.get("schemaVersion").getAsInt());
        assertEquals(V2_ROOT_FIELDS, root.keySet());
        assertEquals(TRACK_FIELDS, firstTrack.keySet());
        assertTrue(root.get("resumeOnReenter").getAsBoolean());
        assertEquals(12, root.get("priority").getAsInt());
        assertFalse(root.has("musicId"));
        assertFalse(root.has("volume"));
        assertFalse(root.has("loop"));
        assertFalse(root.has("fadeInMs"));
        assertFalse(root.has("fadeOutMs"));
    }

    @Test
    void v2RoundTripsEveryField() {
        AreaDefinition original = area(true);

        AreaDefinition decoded = codec.read("boss", new StringReader(codec.write(original)));

        assertEquals(original, decoded);
    }

    @ParameterizedTest
    @ValueSource(strings = {"musicId", "volume", "loop", "fadeInMs", "fadeOutMs"})
    void rejectsLegacyPlaybackFieldsAtV2Root(String field) {
        String json = v2Json("[{ \"musicId\": \"track.ogg\" }]", ",\n  \"" + field + "\": null");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(json)));
    }

    @Test
    void rejectsEmptyAndSeventeenTrackArrays() {
        String tooMany = IntStream.range(0, 17)
                .mapToObj(index -> "{ \"musicId\": \"track-" + index + ".ogg\" }")
                .collect(java.util.stream.Collectors.joining(",", "[", "]"));

        assertThrows(JsonParseException.class,
                () -> codec.read("square", new StringReader(v2Json("[]", ""))));
        assertThrows(JsonParseException.class,
                () -> codec.read("square", new StringReader(v2Json(tooMany, ""))));
    }

    @Test
    void rejectsMissingOrNonArrayTracks() {
        String missing = v2Json("[]", "").replace(",\n  \"tracks\": []", "");
        String object = v2Json("{}", "");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(missing)));
        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(object)));
    }

    @Test
    void rejectsFractionalAndNegativeDelaySeconds() {
        String fractional = v2Json("[{ \"musicId\": \"track.ogg\", \"delaySeconds\": 1.5 }]", "");
        String negative = v2Json("[{ \"musicId\": \"track.ogg\", \"delaySeconds\": -1 }]", "");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(fractional)));
        JsonParseException error = assertThrows(JsonParseException.class,
                () -> codec.read("square", new StringReader(negative)));
        assertTrue(error.getMessage().contains("Invalid area 'square'"));
    }

    @Test
    void rejectsUnknownTrackFields() {
        String json = v2Json("[{ \"musicId\": \"track.ogg\", \"volum\": 1 }]", "");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(json)));
    }

    @Test
    void wrapsInvalidTrackValuesWithAreaId() {
        List<String> invalidTracks = List.of(
                "{ \"musicId\": \" \" }",
                "{ \"musicId\": \"track.ogg\", \"volume\": -0.01 }",
                "{ \"musicId\": \"track.ogg\", \"volume\": 1.01 }",
                "{ \"musicId\": \"track.ogg\", \"fadeInMs\": -1 }",
                "{ \"musicId\": \"track.ogg\", \"fadeOutMs\": 60001 }"
        );

        for (String track : invalidTracks) {
            JsonParseException error = assertThrows(JsonParseException.class,
                    () -> codec.read("square", new StringReader(v2Json("[" + track + "]", ""))));
            assertTrue(error.getMessage().contains("Invalid area 'square'"));
        }
    }

    @Test
    void rejectsOutOfRangeVolumesBeforeFloatRounding() {
        for (String volume : List.of("1.00000001", "-1e-1000")) {
            String track = "{ \"musicId\": \"track.ogg\", \"volume\": " + volume + " }";

            JsonParseException error = assertThrows(JsonParseException.class,
                    () -> codec.read("square", new StringReader(v2Json("[" + track + "]", ""))));
            assertTrue(error.getMessage().contains("Invalid area 'square'"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 3, -1})
    void rejectsUnsupportedSchemaVersions(int schemaVersion) {
        String json = v2Json("[{ \"musicId\": \"track.ogg\" }]", "")
                .replace("\"schemaVersion\": 2", "\"schemaVersion\": " + schemaVersion);

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(json)));
    }

    @Test
    void rejectsUnknownRootFieldsAndFractionalCoordinates() {
        String unknown = v1Json(",\n  \"volum\": 1");
        String fractionalCoordinate = v1Json("").replace("\"x\": 10", "\"x\": 10.5");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(unknown)));
        assertThrows(JsonParseException.class,
                () -> codec.read("square", new StringReader(fractionalCoordinate)));
    }

    private static AreaDefinition area(boolean resumeOnReenter) {
        return AreaDefinition.create(
                "boss", ResourceLocation.tryParse("minecraft:the_nether"),
                new BlockPos(-5, 10, -4), new BlockPos(8, 90, 7),
                List.of(
                        new AreaTrackDefinition("boss/intro.flac", 0, 0.65f, false, 750, 3500),
                        new AreaTrackDefinition("boss/fight.flac", 6, 1.0f, true, 100, 5000)
                ),
                resumeOnReenter,
                12
        );
    }

    private static String v1Json(String extraFields) {
        return """
                {
                  "schemaVersion": 1,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 10, "y": 80, "z": 10 },
                  "pos2": { "x": 0, "y": 60, "z": 0 },
                  "musicId": "村庄/day.mp3"%s
                }
                """.formatted(extraFields);
    }

    private static String v2Json(String tracks, String extraFields) {
        return """
                {
                  "schemaVersion": 2,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 10, "y": 80, "z": 10 },
                  "pos2": { "x": 0, "y": 60, "z": 0 },
                  "tracks": %s%s
                }
                """.formatted(tracks, extraFields);
    }
}
