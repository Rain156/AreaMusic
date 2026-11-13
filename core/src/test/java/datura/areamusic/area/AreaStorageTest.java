package datura.areamusic.area;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import datura.areamusic.music.MusicLibrary;
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
        Path musicRoot = tempDir.resolve("areamusic");
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
    void rejectsWholeLoadWhenAnyTrackIsMissingAndAnotherIsPresent() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Path musicRoot = tempDir.resolve("music");
        Files.createDirectories(areaDirectory);
        Files.createDirectories(musicRoot);
        Files.writeString(musicRoot.resolve("present.ogg"), "fixture");
        Files.writeString(areaDirectory.resolve("mixed.json"), v2Json("""
                [
                    { "musicId": "present.ogg" },
                    { "musicId": "missing.ogg" }
                  ]
                """));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadException error = assertThrows(AreaStorage.LoadException.class,
                () -> storage.load(MusicLibrary.scan(musicRoot)));

        assertTrue(error.getMessage().contains("missing.ogg"));
    }

    @Test
    void rejectsPlaylistWhenAnIndexedEntryIsMissingFromCandidateLibrary() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Path musicRoot = tempDir.resolve("music");
        Files.createDirectories(areaDirectory);
        Files.createDirectories(musicRoot);
        Files.writeString(musicRoot.resolve("present.ogg"), "fixture");
        Files.writeString(areaDirectory.resolve("playlist.json"), playlistJson(
                "[\"present.ogg\", \"missing.ogg\", \"present.ogg\"]"
        ));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadException error = assertThrows(AreaStorage.LoadException.class,
                () -> storage.load(MusicLibrary.scan(musicRoot)));

        assertTrue(error.getMessage().contains("missing.ogg"));
        assertTrue(error.getMessage().contains("playlist[1]"));
    }

    @Test
    void loadsPlaylistWhenEveryEntryExistsInCandidateLibrary() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Path musicRoot = tempDir.resolve("music");
        Files.createDirectories(areaDirectory);
        Files.createDirectories(musicRoot);
        Files.writeString(musicRoot.resolve("first.ogg"), "fixture");
        Files.writeString(musicRoot.resolve("second.ogg"), "fixture");
        Files.writeString(areaDirectory.resolve("playlist.json"), playlistJson(
                "[\"first.ogg\", \"second.ogg\", \"first.ogg\"]"
        ));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        List<AreaDefinition> loaded = storage.load(MusicLibrary.scan(musicRoot));

        assertEquals(List.of("first.ogg", "second.ogg", "first.ogg"),
                loaded.get(0).musicIds());
    }

    @Test
    void preservesIndexedKnownTrackFieldErrorsFromTheCodec() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Path musicRoot = tempDir.resolve("music");
        Files.createDirectories(areaDirectory);
        Files.createDirectories(musicRoot);
        Files.writeString(musicRoot.resolve("first.ogg"), "fixture");
        Files.writeString(musicRoot.resolve("second.ogg"), "fixture");
        Files.writeString(areaDirectory.resolve("invalid.json"), v2Json("""
                [
                    { "musicId": "first.ogg" },
                    { "musicId": "second.ogg", "volume": "loud" }
                  ]
                """));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadException error = assertThrows(AreaStorage.LoadException.class,
                () -> storage.load(MusicLibrary.scan(musicRoot)));

        assertTrue(error.getMessage().contains("invalid.json"));
        assertTrue(error.getMessage().contains("tracks[1]"));
        assertTrue(error.getMessage().contains("volume"));
    }

    @Test
    void createsAFormattedParallelFileAndRefusesToOverwriteIt() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());
        AreaDefinition area = AreaDefinition.create(
                "spawn", "minecraft:overworld",
                new AreaPosition(0, 0, 0), new AreaPosition(4, 4, 4),
                List.of(new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000)),
                false,
                0);

        storage.create(area);

        Path saved = areaDirectory.resolve("spawn.json");
        String json = Files.readString(saved);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        assertTrue(json.startsWith("{\n  \"schemaVersion\": 4,"));
        assertTrue(json.contains("\n  \"tracks\": [\n    {"));
        assertEquals(4, root.get("schemaVersion").getAsInt());
        assertEquals("parallel", root.get("playbackMode").getAsString());
        assertEquals(1, root.getAsJsonArray("tracks").size());
        assertFalse(root.has("musicId"));
        assertThrows(AreaStorage.AreaAlreadyExistsException.class, () -> storage.create(area));
    }

    @Test
    void saveOverwritesExistingAreaAtomically() throws Exception {
        Path areaDirectory = tempDir.resolve("world/areamusic/areas");
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());
        AreaDefinition first = spawn();

        storage.save(first);
        storage.save(first.withPriority(9).withDisplayName("出生点"));

        AreaStorage.LoadResult loaded = storage.loadLenient();
        assertEquals(List.of(first.withPriority(9).withDisplayName("出生点")), loaded.areas());
        assertEquals(List.of(), loaded.errors());
        try (var listed = Files.list(areaDirectory)) {
            assertEquals(List.of("spawn.json"),
                    listed.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void deleteRemovesFileAndReportsPresence() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());
        storage.save(spawn());

        assertTrue(storage.delete("spawn"));
        assertFalse(storage.delete("spawn"));
        assertFalse(Files.exists(areaDirectory.resolve("spawn.json")));
        assertThrows(IllegalArgumentException.class, () -> storage.delete("../x"));
        assertThrows(IllegalArgumentException.class, () -> storage.delete(""));
    }

    @Test
    void deleteOnMissingDirectoryReportsAbsence() throws Exception {
        AreaStorage storage = new AreaStorage(tempDir.resolve("never-created"), new AreaJsonCodec());

        assertFalse(storage.delete("spawn"));
    }

    @Test
    void loadLenientKeepsAreasWhoseMusicIsMissing() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Files.createDirectories(areaDirectory);
        Files.writeString(areaDirectory.resolve("orphan.json"), json("gone.ogg"));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadResult loaded = storage.loadLenient();

        assertEquals(List.of("orphan"), loaded.areas().stream().map(AreaDefinition::id).toList());
        assertEquals(List.of("gone.ogg"), loaded.areas().get(0).musicIds());
        assertEquals(List.of(), loaded.errors());
    }

    @Test
    void loadLenientSkipsBrokenFilesAndReportsThem() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Files.createDirectories(areaDirectory);
        Files.writeString(areaDirectory.resolve("valid.json"), json("track.ogg"));
        Files.writeString(areaDirectory.resolve("broken.json"), "{");
        Files.writeString(areaDirectory.resolve("Bad Name.json"), json("track.ogg"));
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadResult loaded = storage.loadLenient();

        assertEquals(List.of("valid"), loaded.areas().stream().map(AreaDefinition::id).toList());
        assertEquals(2, loaded.errors().size());
        assertTrue(loaded.errors().stream().anyMatch(error -> error.startsWith("broken.json: ")));
        assertTrue(loaded.errors().stream().anyMatch(error -> error.startsWith("Bad Name.json: ")));
    }

    @Test
    void loadLenientIgnoresLeftoverTemporaryFiles() throws Exception {
        Path areaDirectory = tempDir.resolve("areas");
        Files.createDirectories(areaDirectory);
        Files.writeString(areaDirectory.resolve(".village-1234.tmp"), "{");
        Files.writeString(areaDirectory.resolve(".hidden.json"), "{");
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        AreaStorage.LoadResult loaded = storage.loadLenient();

        assertEquals(List.of(), loaded.areas());
        assertEquals(List.of(), loaded.errors());
    }

    @Test
    void loadLenientOnMissingDirectoryIsEmptyAndDoesNotCreateIt() throws Exception {
        Path areaDirectory = tempDir.resolve("absent");
        AreaStorage storage = new AreaStorage(areaDirectory, new AreaJsonCodec());

        assertEquals(List.of(), storage.loadLenient().areas());
        assertFalse(Files.exists(areaDirectory));
    }

    @Test
    void worldDirectoryIsAreasFolderInsideWorld() {
        Path world = tempDir.resolve("saves/New World");

        assertEquals(
                world.toAbsolutePath().normalize().resolve("areamusic").resolve("areas"),
                AreaStorage.worldDirectory(world)
        );
    }

    private static AreaDefinition spawn() {
        return AreaDefinition.create(
                "spawn", "minecraft:overworld",
                new AreaPosition(0, 0, 0), new AreaPosition(4, 4, 4),
                List.of(new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000)),
                false,
                0);
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

    private static String v2Json(String tracks) {
        return """
                {
                  "schemaVersion": 2,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 0, "y": 64, "z": 0 },
                  "pos2": { "x": 10, "y": 80, "z": 10 },
                  "tracks": %s
                }
                """.formatted(tracks);
    }

    private static String playlistJson(String playlist) {
        return """
                {
                  "schemaVersion": 3,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 0, "y": 64, "z": 0 },
                  "pos2": { "x": 10, "y": 80, "z": 10 },
                  "playbackMode": "playlist_loop",
                  "playlist": %s
                }
                """.formatted(playlist);
    }
}
