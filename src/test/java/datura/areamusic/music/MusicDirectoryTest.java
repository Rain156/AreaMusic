package datura.areamusic.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class MusicDirectoryTest {
    @TempDir
    Path tempDir;

    @Test
    void prepareCreatesOnlyTheExactLowercaseDirectory() throws Exception {
        Path gameDirectory = tempDir.resolve("game");

        Path prepared = MusicDirectory.prepare(gameDirectory);

        Path expected = gameDirectory.toAbsolutePath().normalize().resolve("areamusic");
        assertEquals(expected, prepared);
        assertEquals(Set.of("areamusic"), exactChildNames(gameDirectory));
        assertTrue(Files.isDirectory(prepared, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void prepareMigratesTheLegacyDirectoryWithoutLosingContents() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "legacy track contents");

        Path prepared = MusicDirectory.prepare(gameDirectory);

        assertEquals(gameDirectory.toAbsolutePath().normalize().resolve("areamusic"), prepared);
        assertEquals("legacy track contents", Files.readString(prepared.resolve("track.ogg")));
        assertEquals(Set.of("areamusic"), exactChildNames(gameDirectory));
    }

    @Test
    void prepareRejectsDistinctLegacyAndCanonicalDirectoriesWithoutChangingEither() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path legacy = gameDirectory.resolve("AreaMusic");
        Path canonical = gameDirectory.resolve("areamusic");
        Files.createDirectories(legacy);
        Files.createDirectories(canonical);
        assumeTrue(exactChildNames(gameDirectory).equals(Set.of("AreaMusic", "areamusic"))
                        && !Files.isSameFile(legacy, canonical),
                "File system does not support distinct case-sensitive entries");
        Files.writeString(legacy.resolve("legacy.ogg"), "legacy");
        Files.writeString(canonical.resolve("canonical.ogg"), "canonical");

        assertThrows(MusicDirectory.ConflictException.class,
                () -> MusicDirectory.prepare(gameDirectory));

        assertEquals(Set.of("AreaMusic", "areamusic"), exactChildNames(gameDirectory));
        assertEquals("legacy", Files.readString(legacy.resolve("legacy.ogg")));
        assertEquals("canonical", Files.readString(canonical.resolve("canonical.ogg")));
    }

    @Test
    void prepareRejectsALegacyFileWithoutChangingIt() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Files.createDirectories(gameDirectory);
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.writeString(legacy, "not a directory");

        assertThrows(IOException.class, () -> MusicDirectory.prepare(gameDirectory));

        assertEquals("not a directory", Files.readString(legacy));
        assertEquals(Set.of("AreaMusic"), exactChildNames(gameDirectory));
    }

    @Test
    void prepareRejectsACanonicalSymbolicLinkWithoutFollowingItsTarget() throws Exception {
        assertSymbolicLinkIsRejected("areamusic");
    }

    @Test
    void prepareRejectsALegacySymbolicLinkWithoutFollowingItsTarget() throws Exception {
        assertSymbolicLinkIsRejected("AreaMusic");
    }

    @Test
    void canonicalPathDoesNotCreateAnything() throws Exception {
        Path gameDirectory = tempDir.resolve("missing").resolve("..").resolve("game");

        Path canonical = MusicDirectory.canonicalPath(gameDirectory);

        assertEquals(tempDir.resolve("game").toAbsolutePath().normalize().resolve("areamusic"), canonical);
        assertEquals(Set.of(), exactChildNames(tempDir));
        assertFalse(Files.exists(canonical, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void rollbackOnlyRestoresAPresentTemporaryEntryToAnAbsentLegacyEntry() {
        for (MusicDirectory.Presence temporary : MusicDirectory.Presence.values()) {
            for (MusicDirectory.Presence legacy : MusicDirectory.Presence.values()) {
                IOException secondMoveFailure = new IOException("second move failed");
                AtomicInteger rollbackMoves = new AtomicInteger();

                IOException result = MusicDirectory.rollbackFailure(
                        secondMoveFailure,
                        temporary,
                        legacy,
                        () -> rollbackMoves.incrementAndGet()
                );

                boolean shouldRestore = temporary == MusicDirectory.Presence.PRESENT
                        && legacy == MusicDirectory.Presence.ABSENT;
                assertSame(secondMoveFailure, result);
                assertEquals(shouldRestore ? 1 : 0, rollbackMoves.get());

                boolean diagnosticRequired = temporary == MusicDirectory.Presence.UNKNOWN
                        || legacy == MusicDirectory.Presence.UNKNOWN
                        || temporary == MusicDirectory.Presence.PRESENT
                        && legacy == MusicDirectory.Presence.PRESENT;
                if (diagnosticRequired) {
                    assertEquals(1, result.getSuppressed().length);
                    String diagnostic = result.getSuppressed()[0].getMessage();
                    assertTrue(diagnostic.contains(temporary.name()));
                    assertTrue(diagnostic.contains(legacy.name()));
                }
            }
        }
    }

    @Test
    void rollbackMoveFailureIsSuppressedUnderTheOriginalSecondMoveFailure() {
        IOException secondMoveFailure = new IOException("second move failed");
        IOException rollbackMoveFailure = new IOException("rollback move failed");

        IOException result = MusicDirectory.rollbackFailure(
                secondMoveFailure,
                MusicDirectory.Presence.PRESENT,
                MusicDirectory.Presence.ABSENT,
                () -> {
                    throw rollbackMoveFailure;
                }
        );

        assertSame(secondMoveFailure, result);
        assertEquals(1, result.getSuppressed().length);
        assertSame(rollbackMoveFailure, result.getSuppressed()[0]);
    }

    private void assertSymbolicLinkIsRejected(String entryName) throws Exception {
        Path gameDirectory = tempDir.resolve("game-" + entryName);
        Path target = tempDir.resolve("target-" + entryName);
        Files.createDirectories(gameDirectory);
        Files.createDirectories(target);
        Files.writeString(target.resolve("track.ogg"), "target contents");
        Path link = gameDirectory.resolve(entryName);
        assumeTrue(createSymbolicLink(link, target), "Symbolic links are unavailable on this platform");

        assertThrows(IOException.class, () -> MusicDirectory.prepare(gameDirectory));

        assertTrue(Files.isSymbolicLink(link));
        assertEquals(Set.of(entryName), exactChildNames(gameDirectory));
        assertEquals("target contents", Files.readString(target.resolve("track.ogg")));
        assertEquals(Set.of("track.ogg"), exactChildNames(target));
    }

    private static boolean createSymbolicLink(Path link, Path target) {
        try {
            Files.createSymbolicLink(link, target);
            return true;
        } catch (IOException | UnsupportedOperationException | SecurityException exception) {
            return false;
        }
    }

    private static Set<String> exactChildNames(Path directory) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
