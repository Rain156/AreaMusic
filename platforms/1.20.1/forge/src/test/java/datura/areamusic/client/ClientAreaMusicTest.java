package datura.areamusic.client;

import datura.areamusic.music.MusicLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClientAreaMusicTest {
    @TempDir
    Path tempDir;

    @Test
    void scanMigratesTheLegacyDirectoryAndReturnsTheLowercaseRoot() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "fixture");

        MusicLibrary library = ClientAreaMusic.scan(gameDirectory);

        assertEquals(gameDirectory.toAbsolutePath().normalize().resolve("areamusic"), library.root());
        assertEquals(Set.of("track.ogg"), library.ids());
        assertEquals(Set.of("areamusic"), exactChildNames(gameDirectory));
    }

    @Test
    void scanReportsMigrationFailureWithoutCreatingAFallbackDirectory() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Files.createDirectories(gameDirectory);
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.writeString(legacy, "not a directory");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> ClientAreaMusic.scan(gameDirectory));

        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("not a directory", Files.readString(legacy));
        assertEquals(Set.of("AreaMusic"), exactChildNames(gameDirectory));
    }

    private static Set<String> exactChildNames(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
