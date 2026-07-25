package datura.areamusic.server;

import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.area.AreaStorage;
import datura.areamusic.music.MusicLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaMusicServerTest {
    @TempDir
    Path tempDir;

    @Test
    void loadCandidateMigratesTheLegacyDirectoryAndUsesTheLowercaseRoot() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "fixture");
        AreaStorage storage = new AreaStorage(tempDir.resolve("areas"), new AreaJsonCodec());

        AreaMusicServer.ReloadCandidate candidate = AreaMusicServer.loadCandidate(gameDirectory, storage);

        assertEquals(gameDirectory.toAbsolutePath().normalize().resolve("areamusic"),
                candidate.musicLibrary().root());
        assertEquals(Set.of("track.ogg"), candidate.musicLibrary().ids());
        assertEquals(Set.of("areamusic"), exactChildNames(gameDirectory));
    }

    @Test
    void failedMigrationDoesNotReplaceTheCurrentSnapshotOrCreateAFallback() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Files.createDirectories(gameDirectory);
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.writeString(legacy, "not a directory");
        AreaStorage storage = new AreaStorage(tempDir.resolve("areas"), new AreaJsonCodec());
        MusicLibrary current = MusicLibrary.empty(tempDir.resolve("current"));
        Field libraryField = AreaMusicServer.class.getDeclaredField("musicLibrary");
        libraryField.setAccessible(true);
        Object original = libraryField.get(null);

        try {
            libraryField.set(null, current);

            RuntimeException failure = assertThrows(RuntimeException.class,
                    () -> AreaMusicServer.loadCandidate(gameDirectory, storage));

            assertInstanceOf(IOException.class, failure.getCause());
            assertSame(current, libraryField.get(null));
            assertEquals("not a directory", Files.readString(legacy));
            assertEquals(Set.of("AreaMusic"), exactChildNames(gameDirectory));
        } finally {
            libraryField.set(null, original);
        }
    }

    private static Set<String> exactChildNames(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
