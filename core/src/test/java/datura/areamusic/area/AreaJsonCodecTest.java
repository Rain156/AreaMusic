package datura.areamusic.area;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.StringReader;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaJsonCodecTest {
    private static final Set<String> V3_ROOT_FIELDS = Set.of(
            "schemaVersion", "dimension", "pos1", "pos2", "playbackMode", "tracks",
            "resumeOnReenter", "priority"
    );
    private static final Set<String> TRACK_FIELDS = Set.of(
            "musicId", "delaySeconds", "volume", "loop", "fadeInMs", "fadeOutMs"
    );

    private final AreaJsonCodec codec = new AreaJsonCodec();

    @Test
    void writerPreservesCrossedAxisEndpointIdentity() {
        AreaPosition pos1 = new AreaPosition(10, 60, 0);
        AreaPosition pos2 = new AreaPosition(0, 80, 10);
        AreaDefinition area = AreaDefinition.create(
                "crossed", "minecraft:overworld",
                pos1, pos2,
                List.of(new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000)),
                false,
                0
        );

        JsonObject root = JsonParser.parseString(codec.write(area)).getAsJsonObject();
        AreaDefinition decoded = codec.read("crossed", new StringReader(codec.write(area)));

        assertEquals(JsonParser.parseString("{\"x\":10,\"y\":60,\"z\":0}"), root.get("pos1"));
        assertEquals(JsonParser.parseString("{\"x\":0,\"y\":80,\"z\":10}"), root.get("pos2"));
        assertEquals(pos1, decoded.pos1());
        assertEquals(pos2, decoded.pos2());
    }

    @Test
    void writerEmitsSchemaV3ParallelMode() {
        JsonObject root = JsonParser.parseString(codec.write(area(false))).getAsJsonObject();

        assertEquals(3, root.get("schemaVersion").getAsInt());
        assertEquals("parallel", root.get("playbackMode").getAsString());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void legacySchemasUpgradeToV3WithoutSwappingEndpoints(int schemaVersion) {
        String legacyJson = schemaVersion == 1
                ? v1Json("")
                : v2Json("[{ \"musicId\": \"track.ogg\" }]", "");

        AreaDefinition area = codec.read("square", new StringReader(legacyJson));
        JsonObject upgraded = JsonParser.parseString(codec.write(area)).getAsJsonObject();

        assertEquals(new AreaPosition(10, 80, 10), area.pos1());
        assertEquals(new AreaPosition(0, 60, 0), area.pos2());
        assertEquals(3, upgraded.get("schemaVersion").getAsInt());
        assertEquals("parallel", upgraded.get("playbackMode").getAsString());
        assertEquals(JsonParser.parseString("{\"x\":10,\"y\":80,\"z\":10}"), upgraded.get("pos1"));
        assertEquals(JsonParser.parseString("{\"x\":0,\"y\":60,\"z\":0}"), upgraded.get("pos2"));
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 2})
    void legacySchemasKeepMinecraftDefaultNamespaceSyntax(int schemaVersion) {
        String legacyJson = (schemaVersion == 1
                ? v1Json("")
                : v2Json("[{ \"musicId\": \"track.ogg\" }]", ""))
                .replace("minecraft:overworld", ":overworld");

        AreaDefinition area = codec.read("square", new StringReader(legacyJson));

        assertEquals("minecraft:overworld", area.dimension());
    }

    @Test
    void readsV3ParallelMode() {
        AreaDefinition area = codec.read("square", new StringReader(v3Json("\"parallel\"", "")));

        assertEquals(List.of(new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000)),
                area.tracks());
    }

    @Test
    void requiresV3PlaybackMode() {
        String json = v3Json("\"parallel\"", "")
                .replace("  \"playbackMode\": \"parallel\",\n", "");

        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("square", new StringReader(json))
        );

        assertEquals("Missing required field: playbackMode", error.getMessage());
    }

    @Test
    void rejectsNonStringV3PlaybackMode() {
        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("square", new StringReader(v3Json("3", "")))
        );

        assertEquals("playbackMode must be a string", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"playlist_loop", "random"})
    void rejectsUnsupportedV3PlaybackModes(String playbackMode) {
        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("square", new StringReader(v3Json("\"" + playbackMode + "\"", "")))
        );

        assertEquals("Unsupported playbackMode: " + playbackMode, error.getMessage());
    }

    @Test
    void rejectsUnknownV3RootFieldsClearly() {
        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("square", new StringReader(v3Json("\"parallel\"", ",\n  \"mystery\": true")))
        );

        assertEquals("Unknown field in root: mystery", error.getMessage());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("lenientJsonSamples")
    void rejectsLenientJsonSyntax(String description, String json) {
        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(json)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"TRUE", "False"})
    void rejectsNonLowercaseBooleanLiterals(String literal) {
        String json = v3Json("\"parallel\"", ",\n  \"resumeOnReenter\": " + literal);

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(json)));
    }

    @ParameterizedTest
    @ValueSource(ints = {0x00, 0x09, 0x0A, 0x1F})
    void rejectsUnescapedControlCharactersInStrings(int controlCharacter) {
        String json = v3Json("\"parallel\"", "")
                .replace("track.ogg", "track" + (char) controlCharacter + ".ogg");

        assertThrows(JsonParseException.class, () -> codec.read("square", new StringReader(json)));
    }

    @Test
    void rejectsNonLowercaseNullAsMalformedJson() {
        String json = v3Json("\"parallel\"", ",\n  \"resumeOnReenter\": NULL");

        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("square", new StringReader(json))
        );

        assertTrue(error.getMessage().contains("Malformed JSON"));
    }

    @Test
    void rejectsJsonBeyondMaximumNestingDepth() {
        int nestingDepth = 65;
        String json = "[".repeat(nestingDepth) + "0" + "]".repeat(nestingDepth);

        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("deep", new StringReader(json))
        );

        assertTrue(error.getMessage().contains("maximum JSON nesting depth of 64"));
    }

    @Test
    void rejectsDuplicateRootFields() {
        String json = v3Json("\"parallel\"", "")
                .replace(
                        "\"schemaVersion\": 3,",
                        "\"schemaVersion\": 1,\n  \"schemaVersion\": 3,"
                );

        assertDuplicateRejected(json, "root", "schemaVersion");
    }

    @ParameterizedTest
    @ValueSource(strings = {"pos1", "pos2"})
    void rejectsDuplicatePositionFields(String positionName) {
        String coordinate = positionName.equals("pos1") ? "10" : "0";
        String json = v3Json("\"parallel\"", "")
                .replace(
                        "\"" + positionName + "\": { \"x\": " + coordinate + ",",
                        "\"" + positionName + "\": { \"x\": -999, \"x\": " + coordinate + ","
                );

        assertDuplicateRejected(json, positionName, "x");
    }

    @Test
    void rejectsDuplicateTrackFields() {
        String json = v3Json("\"parallel\"", "")
                .replace(
                        "\"musicId\": \"track.ogg\"",
                        "\"musicId\": \"ignored.ogg\", \"musicId\": \"track.ogg\""
                );

        assertDuplicateRejected(json, "tracks[0]", "musicId");
    }

    @Test
    void readsLegacyV1AsOneTrackWithDefaults() {
        AreaDefinition area = codec.read("square", new StringReader(v1Json("")));

        assertEquals(new AreaPosition(0, 60, 0), area.min());
        assertEquals(new AreaPosition(10, 80, 10), area.max());
        assertEquals("minecraft:overworld", area.dimension());
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
    void writerAlwaysEmitsFormattedV3ParallelTrackSchema() {
        AreaDefinition area = area(true);

        String json = codec.write(area);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        JsonObject firstTrack = root.getAsJsonArray("tracks").get(0).getAsJsonObject();

        assertTrue(json.startsWith("{\n  \"schemaVersion\": 3,"));
        assertTrue(json.contains("\n  \"tracks\": [\n    {"));
        assertEquals(3, root.get("schemaVersion").getAsInt());
        assertEquals("parallel", root.get("playbackMode").getAsString());
        assertEquals(V3_ROOT_FIELDS, root.keySet());
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
    void v3RoundTripsEveryFieldIncludingUnicodeMusicId() {
        AreaDefinition original = area(true);

        AreaDefinition decoded = codec.read("boss", new StringReader(codec.write(original)));

        assertEquals(original, decoded);
    }

    @Test
    void v3RoundTripsProgrammaticNegativeZeroVolume() {
        AreaDefinition original = AreaDefinition.create(
                "zero", "minecraft:overworld",
                new AreaPosition(0, 0, 0), new AreaPosition(0, 0, 0),
                List.of(new AreaTrackDefinition("track.ogg", 0, -0.0f, true, 2000, 2000)),
                false,
                0
        );

        AreaDefinition decoded = codec.read("zero", new StringReader(codec.write(original)));

        assertEquals(original, decoded);
        assertEquals(Float.floatToRawIntBits(0.0f),
                Float.floatToRawIntBits(decoded.tracks().get(0).volume()));
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
    void reportsIndexedPathsForKnownSecondTrackFieldFailures() {
        assertSecondTrackFailureContains(
                "{ \"musicId\": \"second.ogg\", \"volume\": 1.01 }",
                "volume"
        );
        assertSecondTrackFailureContains(
                "{ \"volume\": 1 }",
                "musicId"
        );
        assertSecondTrackFailureContains(
                "{ \"musicId\": \"second.ogg\", \"volume\": \"loud\" }",
                "volume"
        );
        assertSecondTrackFailureContains(
                "{ \"musicId\": \"second.ogg\", \"fadeOutMs\": 60001 }",
                "fadeOutMs"
        );
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
    @ValueSource(ints = {0, 4, -1})
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
                "boss", "minecraft:the_nether",
                new AreaPosition(-5, 10, -4), new AreaPosition(8, 90, 7),
                List.of(
                        new AreaTrackDefinition("boss/序曲.flac", 0, 0.65f, false, 750, 3500),
                        new AreaTrackDefinition("boss/fight.flac", 6, 1.0f, true, 100, 5000)
                ),
                resumeOnReenter,
                12
        );
    }

    private void assertSecondTrackFailureContains(String secondTrack, String field) {
        String tracks = "[{ \"musicId\": \"first.ogg\" }, " + secondTrack + "]";

        JsonParseException error = assertThrows(JsonParseException.class,
                () -> codec.read("square", new StringReader(v2Json(tracks, ""))));

        assertTrue(error.getMessage().contains("tracks[1]"));
        assertTrue(error.getMessage().contains(field));
    }

    private void assertDuplicateRejected(String json, String location, String field) {
        JsonParseException error = assertThrows(
                JsonParseException.class,
                () -> codec.read("square", new StringReader(json))
        );

        assertTrue(error.getMessage().contains("Duplicate field"));
        assertTrue(error.getMessage().contains(location));
        assertTrue(error.getMessage().contains(field));
    }

    private static Stream<Arguments> lenientJsonSamples() {
        String valid = v3Json("\"parallel\"", "");
        return Stream.of(
                Arguments.of("line comment", "// comment\n" + valid),
                Arguments.of("unquoted field name", valid.replace("\"schemaVersion\"", "schemaVersion")),
                Arguments.of("single-quoted string", valid.replace("\"parallel\"", "'parallel'")),
                Arguments.of("trailing root comma", valid.replace("\n}", ",\n}")),
                Arguments.of("semicolon separator", valid.replace("\"schemaVersion\": 3,", "\"schemaVersion\": 3;")),
                Arguments.of("equals separator", valid.replace("\"schemaVersion\": 3", "\"schemaVersion\" = 3"))
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

    private static String v3Json(String playbackMode, String extraFields) {
        return """
                {
                  "schemaVersion": 3,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 10, "y": 80, "z": 10 },
                  "pos2": { "x": 0, "y": 60, "z": 0 },
                  "playbackMode": %s,
                  "tracks": [{ "musicId": "track.ogg" }]%s
                }
                """.formatted(playbackMode, extraFields);
    }
}
