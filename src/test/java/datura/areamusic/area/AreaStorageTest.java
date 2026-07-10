package datura.areamusic.area;

import datura.areamusic.music.MusicLibrary;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaStorageTest {
    @TempDir
    Path tempDir;

    @Test
    void saveIdIsReadableDeterministicAndCollisionResistant() {
        String first = AreaStorage.saveId("New World");

        assertTrue(first.startsWith("New_World-"));
        assertEquals(first, AreaStorage.saveId("New World"));
        assertNotEquals(first, AreaStorage.saveId("New:World"));
        assertFalse(first.contains("/"));
        assertFalse(first.contains("\\"));
    }

    @Test
    void rejectsTheWholeLoadWhenAnyFileIsInvalid() throws Exception {
        Path areaDirectory = tempDir.resolve("config/areamusic/save-id");
        Path musicRoot = tempDir.resolve("AreaMusic");
        Files.createDirectories(musicRoot);
        Files.writeString(musicRoot.resolve("track.ogg"), "fixture");
        MusicLibrary library = MusicLibrary.scan(musicRoot);
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        Files.createDirectories(areaDirectory);
        Files.writeString(areaDirectory.resolve("valid.json"), json("track.ogg"));
        Files.writeString(areaDirectory.resolve("broken.json"), "{ not-json }");

        AreaStorage.LoadException error = assertThrows(AreaStorage.LoadException.class, () -> storage.load(library));
        assertTrue(error.getMessage().contains("broken.json"));

        Files.delete(areaDirectory.resolve("broken.json"));
        List<AreaDefinition> loaded = storage.load(library);
        assertEquals(List.of("valid"), loaded.stream().map(AreaDefinition::id).toList());
    }

    @Test
    void rejectsAreasWhoseMusicIsMissingFromTheCandidateLibrary() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Files.createDirectories(areaDirectory);
        Files.writeString(areaDirectory.resolve("missing.json"), json("missing.mp3"));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadException error = assertThrows(AreaStorage.LoadException.class,
                () -> storage.load(MusicLibrary.scan(tempDir.resolve("empty-music"))));

        assertTrue(error.getMessage().contains("missing.mp3"));
    }

    @Test
    void createsAFormattedFileAndRefusesToOverwriteIt() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());
        AreaDefinition area = AreaDefinition.create(
                "spawn", ResourceLocation.tryParse("minecraft:overworld"),
                BlockPos.ZERO, new BlockPos(4, 4, 4), "track.ogg", 0, 1.0f, true, 2000, 2000);

        storage.create(area);

        Path saved = areaDirectory.resolve("spawn.json");
        assertTrue(Files.readString(saved).contains("\n"));
        assertThrows(AreaStorage.AreaAlreadyExistsException.class, () -> storage.create(area));
    }

    private static String json(String musicId) {
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
}
