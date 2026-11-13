package datura.areamusic.area;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LegacyAreaMigrationTest {
    @TempDir
    Path tempDir;

    @Test
    void migratesLegacyFilesAndLeavesOriginals() throws Exception {
        Path legacy = tempDir.resolve("config/areamusic/world-1234");
        Files.createDirectories(legacy);
        String villageJson = v3Json("village.ogg");
        Files.writeString(legacy.resolve("village.json"), villageJson);
        Files.writeString(legacy.resolve("cave.json"), v1Json("cave.ogg"));
        AreaStorage target = target();

        LegacyAreaMigration.Result result = LegacyAreaMigration.migrateIfNeeded(legacy, target);

        assertTrue(result.ran());
        assertEquals(2, result.migrated());
        assertEquals(List.of(), result.errors());
        assertEquals(
                List.of("cave", "village"),
                target.loadLenient().areas().stream().map(AreaDefinition::id).toList()
        );
        for (String id : List.of("cave", "village")) {
            assertEquals(4, JsonParser.parseString(
                    Files.readString(target.directory().resolve(id + ".json"))
            ).getAsJsonObject().get("schemaVersion").getAsInt());
        }
        assertEquals(villageJson, Files.readString(legacy.resolve("village.json")));
        assertTrue(Files.exists(legacy.resolve("cave.json")));
        String marker = Files.readString(legacy.resolve(LegacyAreaMigration.MARKER_FILE_NAME));
        assertTrue(marker.contains(target.directory().toString()));
        assertTrue(marker.contains("2 area file(s)"));
    }

    @Test
    void migrationContinuesPastBrokenLegacyFile() throws Exception {
        Path legacy = tempDir.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("good.json"), v3Json("good.ogg"));
        Files.writeString(legacy.resolve("broken.json"), "{ nope");
        AreaStorage target = target();

        LegacyAreaMigration.Result result = LegacyAreaMigration.migrateIfNeeded(legacy, target);

        assertTrue(result.ran());
        assertEquals(1, result.migrated());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).startsWith("broken.json: "));
        assertEquals("{ nope", Files.readString(legacy.resolve("broken.json")));
        assertFalse(Files.exists(target.directory().resolve("broken.json")));
        assertEquals(1, target.loadLenient().areas().size());
    }

    @Test
    void migrationDoesNotRunWhenTargetExists() throws Exception {
        Path legacy = tempDir.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("good.json"), v3Json("good.ogg"));
        AreaStorage target = target();
        Files.createDirectories(target.directory());

        LegacyAreaMigration.Result result = LegacyAreaMigration.migrateIfNeeded(legacy, target);

        assertFalse(result.ran());
        assertEquals(List.of(), target.loadLenient().areas());
        assertFalse(Files.exists(legacy.resolve(LegacyAreaMigration.MARKER_FILE_NAME)));
    }

    @Test
    void migrationDoesNotRunWithoutLegacyFiles() throws Exception {
        Path emptyLegacy = tempDir.resolve("empty-legacy");
        Files.createDirectories(emptyLegacy);
        AreaStorage target = target();

        assertFalse(LegacyAreaMigration.migrateIfNeeded(emptyLegacy, target).ran());
        assertFalse(LegacyAreaMigration.migrateIfNeeded(tempDir.resolve("absent"), target).ran());
        assertFalse(Files.exists(target.directory()));
    }

    @Test
    void secondMigrationIsANoOp() throws Exception {
        Path legacy = tempDir.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("good.json"), v3Json("good.ogg"));
        AreaStorage target = target();

        assertTrue(LegacyAreaMigration.migrateIfNeeded(legacy, target).ran());
        assertTrue(target.delete("good"));
        assertFalse(LegacyAreaMigration.migrateIfNeeded(legacy, target).ran());
        assertEquals(List.of(), target.loadLenient().areas());
    }

    private AreaStorage target() {
        return new AreaStorage(
                AreaStorage.worldDirectory(tempDir.resolve("saves/world")), new AreaJsonCodec());
    }

    private static String v1Json(String musicId) {
        return """
                {
                  "schemaVersion": 1,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 0, "y": 64, "z": 0 },
                  "pos2": { "x": 10, "y": 80, "z": 10 },
                  "musicId": "%s"
                }
                """.formatted(musicId);
    }

    private static String v3Json(String musicId) {
        return """
                {
                  "schemaVersion": 3,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 10, "y": 80, "z": 10 },
                  "pos2": { "x": 0, "y": 60, "z": 0 },
                  "playbackMode": "parallel",
                  "tracks": [{ "musicId": "%s" }]
                }
                """.formatted(musicId);
    }
}
