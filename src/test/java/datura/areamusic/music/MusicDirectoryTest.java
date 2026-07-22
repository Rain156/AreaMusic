package datura.areamusic.music;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
    void moveNoClobberPreservesExistingSourceAndTarget() throws Exception {
        Path source = tempDir.resolve("source.txt");
        Path target = tempDir.resolve("target.txt");
        Files.writeString(source, "source contents");
        Files.writeString(target, "target sentinel");

        assertThrows(IOException.class, () -> MusicDirectory.moveNoClobber(source, target));

        assertEquals("source contents", Files.readString(source));
        assertEquals("target sentinel", Files.readString(target));
    }

    @Test
    void targetAppearingBeforeInstallIsPreservedAndTheSourceIsRestored() throws Exception {
        Path source = tempDir.resolve("legacy-source");
        Path target = tempDir.resolve("canonical-target");
        Path temporary = tempDir.resolve("captured-temporary");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");
        AtomicInteger moves = new AtomicInteger();
        AtomicReference<IOException> installFailure = new AtomicReference<>();
        MusicDirectory.MoveOperation moveOperation = (moveSource, moveTarget) -> {
            int move = moves.incrementAndGet();
            if (move == 2) {
                Files.createDirectories(target);
                Files.writeString(target.resolve("sentinel.txt"), "canonical sentinel");
            }
            try {
                MusicDirectory.moveNoClobber(moveSource, moveTarget);
            } catch (IOException exception) {
                if (move == 2) {
                    installFailure.set(exception);
                }
                throw exception;
            }
        };

        IOException failure = assertThrows(IOException.class,
                () -> MusicDirectory.migrate(source, target, temporary, moveOperation));

        assertSame(installFailure.get(), failure);
        assertEquals(3, moves.get());
        assertEquals("canonical sentinel", Files.readString(target.resolve("sentinel.txt")));
        assertEquals("original track", Files.readString(source.resolve("track.ogg")));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void sourceAppearingBeforeRollbackIsPreservedAndOriginalContentRemainsCaptured() throws Exception {
        Path source = tempDir.resolve("legacy-source");
        Path target = tempDir.resolve("canonical-target");
        Path temporary = tempDir.resolve("captured-temporary");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");
        AtomicInteger moves = new AtomicInteger();
        AtomicReference<IOException> installFailure = new AtomicReference<>();
        AtomicReference<IOException> rollbackFailure = new AtomicReference<>();
        MusicDirectory.MoveOperation moveOperation = (moveSource, moveTarget) -> {
            int move = moves.incrementAndGet();
            if (move == 2) {
                Files.createDirectories(target);
                Files.writeString(target.resolve("sentinel.txt"), "canonical sentinel");
            } else if (move == 3) {
                Files.createDirectories(source);
                Files.writeString(source.resolve("sentinel.txt"), "legacy sentinel");
            }
            try {
                MusicDirectory.moveNoClobber(moveSource, moveTarget);
            } catch (IOException exception) {
                if (move == 2) {
                    installFailure.set(exception);
                } else if (move == 3) {
                    rollbackFailure.set(exception);
                }
                throw exception;
            }
        };

        IOException failure = assertThrows(IOException.class,
                () -> MusicDirectory.migrate(source, target, temporary, moveOperation));

        assertSame(installFailure.get(), failure);
        assertEquals(3, moves.get());
        assertEquals(1, failure.getSuppressed().length);
        assertSame(rollbackFailure.get(), failure.getSuppressed()[0]);
        assertEquals("canonical sentinel", Files.readString(target.resolve("sentinel.txt")));
        assertEquals("legacy sentinel", Files.readString(source.resolve("sentinel.txt")));
        assertEquals("original track", Files.readString(temporary.resolve("track.ogg")));
    }

    @Test
    void temporaryCandidateThatIsPresentIsSkippedWithoutChangingExistingContent() throws Exception {
        Path gameRoot = tempDir.resolve("present-candidate");
        Path legacy = gameRoot.resolve("legacy-source");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "original track");
        UUID occupiedId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID availableId = UUID.fromString("00000000-0000-0000-0000-000000000002");
        Path occupied = gameRoot.resolve(".areamusic-migrate-" + occupiedId);
        Files.createDirectories(occupied);
        Files.writeString(occupied.resolve("sentinel.txt"), "temporary sentinel");
        ArrayDeque<UUID> ids = new ArrayDeque<>(List.of(occupiedId, availableId));

        Path selected = MusicDirectory.uniqueTemporarySibling(
                gameRoot,
                ids::removeFirst,
                candidate -> candidate.equals(occupied)
                        ? MusicDirectory.Presence.PRESENT
                        : MusicDirectory.Presence.ABSENT
        );

        assertEquals(gameRoot.resolve(".areamusic-migrate-" + availableId), selected);
        assertEquals("temporary sentinel", Files.readString(occupied.resolve("sentinel.txt")));
        assertEquals("original track", Files.readString(legacy.resolve("track.ogg")));
    }

    @Test
    void unknownTemporaryCandidateFailsClosedWithoutChangingTheNamespace() throws Exception {
        Path gameRoot = tempDir.resolve("unknown-candidate");
        Path legacy = gameRoot.resolve("legacy-source");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "original track");
        UUID candidateId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        Path candidate = gameRoot.resolve(".areamusic-migrate-" + candidateId);

        IOException failure = assertThrows(IOException.class, () -> MusicDirectory.uniqueTemporarySibling(
                gameRoot,
                () -> candidateId,
                ignored -> MusicDirectory.Presence.UNKNOWN
        ));

        assertTrue(failure.getMessage().contains("UNKNOWN"));
        assertTrue(failure.getMessage().contains(candidate.toString()));
        assertEquals(Set.of("legacy-source"), exactChildNames(gameRoot));
        assertEquals("original track", Files.readString(legacy.resolve("track.ogg")));
    }

    @Test
    void directoryWithUnavailableIdentityMetadataFailsClosedBeforeAnyMove() throws Exception {
        Path source = tempDir.resolve("null-initial-identity");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");

        IOException failure = assertThrows(IOException.class, () -> MusicDirectory.validateDirectory(
                source,
                ignored -> new MusicDirectory.EntryAttributes(true, false, null, null, null)
        ));

        assertTrue(failure.getMessage().contains("identity"));
        assertEquals("original track", Files.readString(source.resolve("track.ogg")));
        assertEquals(Set.of("null-initial-identity"), exactChildNames(tempDir));
    }

    @Test
    void identityBecomingUnavailableAfterCaptureRollsBackWithoutInstalling() throws Exception {
        Path source = tempDir.resolve("null-moved-identity-source");
        Path target = tempDir.resolve("null-moved-identity-target");
        Path temporary = tempDir.resolve("null-moved-identity-temporary");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");
        AtomicInteger identityReads = new AtomicInteger();
        MusicDirectory.IdentityProbe identityProbe = ignored -> identityReads.incrementAndGet() == 1
                ? fallbackAttributes("store-a", 1000L)
                : new MusicDirectory.EntryAttributes(true, false, null, null, null);
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(source, identityProbe);
        AtomicInteger moves = new AtomicInteger();

        IOException failure = assertThrows(IOException.class, () -> MusicDirectory.migrate(
                captured,
                target,
                temporary,
                (moveSource, moveTarget) -> {
                    moves.incrementAndGet();
                    MusicDirectory.moveNoClobber(moveSource, moveTarget);
                },
                identityProbe
        ));

        assertTrue(failure.getMessage().contains("identity"));
        assertEquals(2, identityReads.get());
        assertEquals(2, moves.get());
        assertEquals("original track", Files.readString(source.resolve("track.ogg")));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
    }

    @Test
    void matchingFileKeysAllowInstallation() throws Exception {
        assertIdentityAccepted(
                keyedAttributes("stable-key"),
                keyedAttributes("stable-key")
        );
    }

    @Test
    void differentFileKeysRejectInstallationAndRestoreTheSource() throws Exception {
        assertIdentityRejected(
                keyedAttributes("original-key"),
                keyedAttributes("replacement-key")
        );
    }

    @Test
    void matchingFallbackStoreAndCreationTimeAllowInstallation() throws Exception {
        assertIdentityAccepted(
                fallbackAttributes("store-a", 1000L),
                fallbackAttributes("store-a", 1000L)
        );
    }

    @Test
    void changedFallbackStoreRejectsInstallationAndRestoresTheSource() throws Exception {
        assertIdentityRejected(
                fallbackAttributes("store-a", 1000L),
                fallbackAttributes("store-b", 1000L)
        );
    }

    @Test
    void changedFallbackCreationTimeRejectsInstallationAndRestoresTheSource() throws Exception {
        assertIdentityRejected(
                fallbackAttributes("store-a", 1000L),
                fallbackAttributes("store-a", 2000L)
        );
    }

    @Test
    void sourceReplacedByAFileAfterCaptureIsNotInstalled() throws Exception {
        assertReplacementAfterCaptureIsRejected(SourceReplacement.FILE);
    }

    @Test
    void sourceReplacedByASymbolicLinkAfterCaptureIsNotInstalled() throws Exception {
        assertReplacementAfterCaptureIsRejected(SourceReplacement.SYMBOLIC_LINK);
    }

    @Test
    void sourceReplacedByAnotherDirectoryAfterCaptureIsNotInstalled() throws Exception {
        assertReplacementAfterCaptureIsRejected(SourceReplacement.DIFFERENT_DIRECTORY);
    }

    @Test
    void verifiedSameFileAliasIsCleanedWithoutASecondMove() throws Exception {
        Path legacy = tempDir.resolve("legacy-exact-entry");
        Path canonical = tempDir.resolve("canonical-exact-entry");
        Path temporary = tempDir.resolve("temporary-exact-entry");
        Map<Path, String> namespace = new HashMap<>();
        namespace.put(legacy, "music-object");
        AtomicInteger moves = new AtomicInteger();
        AtomicInteger cleanups = new AtomicInteger();
        MusicDirectory.IdentityProbe identityProbe = namespaceIdentityProbe(namespace);
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(legacy, identityProbe);
        MusicDirectory.MoveOperation moveOperation = (source, target) -> {
            moves.incrementAndGet();
            moveNamespaceEntry(namespace, source, target);
            namespace.put(canonical, namespace.get(temporary));
        };
        MusicDirectory.MigrationOperations operations = new MusicDirectory.MigrationOperations(
                moveOperation,
                namespacePresenceProbe(namespace),
                identityProbe,
                (left, right) -> Objects.equals(namespace.get(left), namespace.get(right)),
                path -> {
                    cleanups.incrementAndGet();
                    namespace.remove(path);
                }
        );

        MusicDirectory.migrate(captured, canonical, temporary, operations);

        assertEquals(1, moves.get());
        assertEquals(1, cleanups.get());
        assertEquals("music-object", namespace.get(canonical));
        assertFalse(namespace.containsKey(temporary));
        assertFalse(namespace.containsKey(legacy));
    }

    @Test
    void sameFileAliasCleanupFailureLeavesCanonicalAndTemporaryForDiagnosis() throws Exception {
        Path legacy = tempDir.resolve("legacy-exact-entry");
        Path canonical = tempDir.resolve("canonical-exact-entry");
        Path temporary = tempDir.resolve("temporary-exact-entry");
        Map<Path, String> namespace = new HashMap<>();
        namespace.put(legacy, "music-object");
        AtomicInteger moves = new AtomicInteger();
        IOException cleanupFailure = new IOException("alias cleanup failed");
        MusicDirectory.IdentityProbe identityProbe = namespaceIdentityProbe(namespace);
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(legacy, identityProbe);
        MusicDirectory.MigrationOperations operations = new MusicDirectory.MigrationOperations(
                (source, target) -> {
                    moves.incrementAndGet();
                    moveNamespaceEntry(namespace, source, target);
                    namespace.put(canonical, namespace.get(temporary));
                },
                namespacePresenceProbe(namespace),
                identityProbe,
                (left, right) -> Objects.equals(namespace.get(left), namespace.get(right)),
                ignored -> {
                    throw cleanupFailure;
                }
        );

        IOException failure = assertThrows(IOException.class,
                () -> MusicDirectory.migrate(captured, canonical, temporary, operations));

        assertSame(cleanupFailure, failure);
        assertEquals(1, moves.get());
        assertEquals("music-object", namespace.get(canonical));
        assertEquals("music-object", namespace.get(temporary));
        assertFalse(namespace.containsKey(legacy));
    }

    @Test
    void differentCanonicalEntryIsPreservedWhileTemporaryRollsBack() throws Exception {
        Path legacy = tempDir.resolve("legacy-exact-entry");
        Path canonical = tempDir.resolve("canonical-exact-entry");
        Path temporary = tempDir.resolve("temporary-exact-entry");
        Map<Path, String> namespace = new HashMap<>();
        namespace.put(legacy, "music-object");
        namespace.put(canonical, "canonical-sentinel");
        AtomicInteger moves = new AtomicInteger();
        AtomicInteger sameFileChecks = new AtomicInteger();
        MusicDirectory.IdentityProbe identityProbe = namespaceIdentityProbe(namespace);
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(legacy, identityProbe);
        MusicDirectory.MigrationOperations operations = new MusicDirectory.MigrationOperations(
                (source, target) -> {
                    moves.incrementAndGet();
                    moveNamespaceEntry(namespace, source, target);
                },
                namespacePresenceProbe(namespace),
                identityProbe,
                (left, right) -> {
                    sameFileChecks.incrementAndGet();
                    return Objects.equals(namespace.get(left), namespace.get(right));
                },
                namespace::remove
        );

        IOException failure = assertThrows(IOException.class,
                () -> MusicDirectory.migrate(captured, canonical, temporary, operations));

        assertTrue(failure.getMessage().contains("identity"));
        assertEquals(2, moves.get());
        assertEquals(0, sameFileChecks.get());
        assertEquals("canonical-sentinel", namespace.get(canonical));
        assertEquals("music-object", namespace.get(legacy));
        assertFalse(namespace.containsKey(temporary));
    }

    @Test
    void unknownCanonicalPresenceFailsClosedAndRollsBack() throws Exception {
        Path legacy = tempDir.resolve("legacy-exact-entry");
        Path canonical = tempDir.resolve("canonical-exact-entry");
        Path temporary = tempDir.resolve("temporary-exact-entry");
        Map<Path, String> namespace = new HashMap<>();
        namespace.put(legacy, "music-object");
        AtomicInteger moves = new AtomicInteger();
        MusicDirectory.IdentityProbe identityProbe = namespaceIdentityProbe(namespace);
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(legacy, identityProbe);
        MusicDirectory.PresenceProbe presenceProbe = path -> path.equals(canonical)
                ? MusicDirectory.Presence.UNKNOWN
                : namespace.containsKey(path)
                ? MusicDirectory.Presence.PRESENT
                : MusicDirectory.Presence.ABSENT;
        MusicDirectory.MigrationOperations operations = new MusicDirectory.MigrationOperations(
                (source, target) -> {
                    moves.incrementAndGet();
                    moveNamespaceEntry(namespace, source, target);
                },
                presenceProbe,
                identityProbe,
                (left, right) -> false,
                namespace::remove
        );

        IOException failure = assertThrows(IOException.class,
                () -> MusicDirectory.migrate(captured, canonical, temporary, operations));

        assertTrue(failure.getMessage().contains("UNKNOWN"));
        assertEquals(2, moves.get());
        assertEquals("music-object", namespace.get(legacy));
        assertFalse(namespace.containsKey(temporary));
        assertFalse(namespace.containsKey(canonical));
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
                        tempDir.resolve("temporary"),
                        tempDir.resolve("legacy"),
                        (source, target) -> rollbackMoves.incrementAndGet()
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
                tempDir.resolve("temporary"),
                tempDir.resolve("legacy"),
                (source, target) -> {
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

    private void assertReplacementAfterCaptureIsRejected(SourceReplacement replacement) throws Exception {
        Path gameRoot = tempDir.resolve("replacement-" + replacement.name().toLowerCase());
        Path source = gameRoot.resolve("legacy-exact-entry");
        Path target = gameRoot.resolve("canonical-target");
        Path temporary = gameRoot.resolve("captured-temporary");
        Path original = gameRoot.resolve("original-moved-aside");
        Path linkTarget = gameRoot.resolve("link-target");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");
        if (replacement == SourceReplacement.SYMBOLIC_LINK) {
            Files.createDirectories(linkTarget);
            Files.writeString(linkTarget.resolve("sentinel.txt"), "link target sentinel");
            Path probe = gameRoot.resolve("symlink-capability-probe");
            assumeTrue(createSymbolicLink(probe, linkTarget), "Symbolic links are unavailable on this platform");
            Files.delete(probe);
        }
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(source);
        AtomicInteger moves = new AtomicInteger();
        MusicDirectory.MoveOperation moveOperation = (moveSource, moveTarget) -> {
            if (moves.incrementAndGet() == 1) {
                MusicDirectory.moveNoClobber(source, original);
                switch (replacement) {
                    case FILE -> Files.writeString(source, "replacement file");
                    case SYMBOLIC_LINK -> Files.createSymbolicLink(source, linkTarget);
                    case DIFFERENT_DIRECTORY -> {
                        Files.createDirectories(source);
                        Files.writeString(source.resolve("sentinel.txt"), "replacement directory");
                    }
                }
            }
            MusicDirectory.moveNoClobber(moveSource, moveTarget);
        };

        IOException failure = assertThrows(IOException.class, () -> MusicDirectory.migrate(
                captured,
                target,
                temporary,
                moveOperation,
                MusicDirectory::readEntryAttributes
        ));

        assertEquals(2, moves.get());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
        assertEquals("original track", Files.readString(original.resolve("track.ogg")));
        switch (replacement) {
            case FILE -> assertEquals("replacement file", Files.readString(source));
            case SYMBOLIC_LINK -> {
                assertTrue(Files.isSymbolicLink(source));
                assertEquals("link target sentinel", Files.readString(linkTarget.resolve("sentinel.txt")));
            }
            case DIFFERENT_DIRECTORY -> {
                assertTrue(failure.getMessage().contains("identity"));
                assertEquals("replacement directory", Files.readString(source.resolve("sentinel.txt")));
            }
        }
    }

    private void assertIdentityAccepted(
            MusicDirectory.EntryAttributes capturedAttributes,
            MusicDirectory.EntryAttributes movedAttributes
    ) throws Exception {
        Path source = tempDir.resolve("identity-accepted-source");
        Path target = tempDir.resolve("identity-accepted-target");
        Path temporary = tempDir.resolve("identity-accepted-temporary");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");
        AtomicInteger reads = new AtomicInteger();
        MusicDirectory.IdentityProbe probe = ignored -> reads.incrementAndGet() == 1
                ? capturedAttributes
                : movedAttributes;
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(source, probe);

        MusicDirectory.migrate(
                captured,
                target,
                temporary,
                MusicDirectory::moveNoClobber,
                probe
        );

        assertEquals(3, reads.get());
        assertEquals("original track", Files.readString(target.resolve("track.ogg")));
        assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
    }

    private void assertIdentityRejected(
            MusicDirectory.EntryAttributes capturedAttributes,
            MusicDirectory.EntryAttributes movedAttributes
    ) throws Exception {
        Path source = tempDir.resolve("identity-rejected-source");
        Path target = tempDir.resolve("identity-rejected-target");
        Path temporary = tempDir.resolve("identity-rejected-temporary");
        Files.createDirectories(source);
        Files.writeString(source.resolve("track.ogg"), "original track");
        AtomicInteger reads = new AtomicInteger();
        MusicDirectory.IdentityProbe probe = ignored -> reads.incrementAndGet() == 1
                ? capturedAttributes
                : movedAttributes;
        MusicDirectory.ValidatedDirectory captured = MusicDirectory.validateDirectory(source, probe);

        IOException failure = assertThrows(IOException.class, () -> MusicDirectory.migrate(
                captured,
                target,
                temporary,
                MusicDirectory::moveNoClobber,
                probe
        ));

        assertTrue(failure.getMessage().contains("identity"));
        assertEquals(2, reads.get());
        assertEquals("original track", Files.readString(source.resolve("track.ogg")));
        assertFalse(Files.exists(target, LinkOption.NOFOLLOW_LINKS));
        assertFalse(Files.exists(temporary, LinkOption.NOFOLLOW_LINKS));
    }

    private static MusicDirectory.EntryAttributes keyedAttributes(Object fileKey) {
        return new MusicDirectory.EntryAttributes(true, false, fileKey, null, null);
    }

    private static MusicDirectory.EntryAttributes fallbackAttributes(String storeName, long creationMillis) {
        return new MusicDirectory.EntryAttributes(
                true,
                false,
                null,
                new MusicDirectory.StoreIdentity(storeName, "test-store-type"),
                FileTime.fromMillis(creationMillis)
        );
    }

    private static MusicDirectory.IdentityProbe namespaceIdentityProbe(Map<Path, String> namespace) {
        return path -> {
            String identity = namespace.get(path);
            if (identity == null) {
                throw new IOException("Missing fake namespace entry: " + path);
            }
            return keyedAttributes(identity);
        };
    }

    private static MusicDirectory.PresenceProbe namespacePresenceProbe(Map<Path, String> namespace) {
        return path -> namespace.containsKey(path)
                ? MusicDirectory.Presence.PRESENT
                : MusicDirectory.Presence.ABSENT;
    }

    private static void moveNamespaceEntry(Map<Path, String> namespace, Path source, Path target) throws IOException {
        if (namespace.containsKey(target)) {
            throw new IOException("Fake target already exists: " + target);
        }
        String identity = namespace.remove(source);
        if (identity == null) {
            throw new IOException("Fake source is missing: " + source);
        }
        namespace.put(target, identity);
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

    private enum SourceReplacement {
        FILE,
        SYMBOLIC_LINK,
        DIFFERENT_DIRECTORY
    }
}
