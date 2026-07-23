package datura.areamusic.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicLibraryTest {
    @TempDir
    Path tempDir;

    @Test
    void createsMissingRootAndReturnsAnEmptySnapshot() throws Exception {
        Path root = tempDir.resolve("areamusic");

        MusicLibrary library = MusicLibrary.scan(root);

        assertTrue(Files.isDirectory(root));
        assertTrue(library.ids().isEmpty());
        assertEquals(root.toAbsolutePath().normalize(), library.root());
    }

    @Test
    void recursivelyIndexesSupportedFormatsAndIgnoresOtherFiles() throws Exception {
        Path root = tempDir.resolve("areamusic");
        write(root.resolve("village/day.mp3"));
        write(root.resolve("ambient/Cave.OGG"));
        write(root.resolve("rain.wav"));
        write(root.resolve("boss.flac"));
        write(root.resolve("notes.txt"));

        MusicLibrary library = MusicLibrary.scan(root);

        assertEquals(Set.of("ambient/Cave.OGG", "boss.flac", "rain.wav", "village/day.mp3"), library.ids());
        assertEquals(root.resolve("village/day.mp3").toAbsolutePath().normalize(),
                library.find("village/day.mp3").orElseThrow());
        assertTrue(library.find("notes.txt").isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> library.ids().add("other.ogg"));
    }

    @Test
    void rejectsIdsThatDifferOnlyByCase() throws Exception {
        Path zip = tempDir.resolve("case-conflict.zip");
        URI uri = URI.create("jar:" + zip.toUri());
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of("create", "true"))) {
            Path root = fileSystem.getPath("/AreaMusic");
            write(root.resolve("Theme.ogg"));
            write(root.resolve("theme.ogg"));

            MusicLibrary.ScanException error = assertThrows(MusicLibrary.ScanException.class,
                    () -> MusicLibrary.scan(root));

            assertTrue(error.getMessage().contains("Theme.ogg"));
            assertTrue(error.getMessage().contains("theme.ogg"));
        }
    }

    private static void write(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "fixture");
    }
}
