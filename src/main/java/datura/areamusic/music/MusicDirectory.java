package datura.areamusic.music;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.stream.Stream;

public final class MusicDirectory {
    public static final String DIRECTORY_NAME = "areamusic";
    static final String LEGACY_DIRECTORY_NAME = "AreaMusic";

    private MusicDirectory() {
    }

    public static Path canonicalPath(Path gameDirectory) {
        return gameDirectory.toAbsolutePath().normalize().resolve(DIRECTORY_NAME);
    }

    public static synchronized Path prepare(Path gameDirectory) throws IOException {
        Path gameRoot = gameDirectory.toAbsolutePath().normalize();
        Files.createDirectories(gameRoot);

        Path canonicalEntry = null;
        Path legacyEntry = null;
        try (Stream<Path> children = Files.list(gameRoot)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                String name = child.getFileName().toString();
                if (DIRECTORY_NAME.equals(name)) {
                    canonicalEntry = child;
                } else if (LEGACY_DIRECTORY_NAME.equals(name)) {
                    legacyEntry = child;
                }
            }
        }

        Path canonical = gameRoot.resolve(DIRECTORY_NAME);
        Path legacy = gameRoot.resolve(LEGACY_DIRECTORY_NAME);
        if (canonicalEntry == null && legacyEntry == null) {
            Files.createDirectory(canonical);
            return canonical;
        }
        if (canonicalEntry != null && legacyEntry == null) {
            requireRealDirectory(canonicalEntry);
            return canonical;
        }
        if (canonicalEntry == null) {
            requireRealDirectory(legacyEntry);
            migrate(legacy, canonical);
            return canonical;
        }

        requireRealDirectory(canonicalEntry);
        requireRealDirectory(legacyEntry);
        if (!Files.isSameFile(canonicalEntry, legacyEntry)) {
            throw new ConflictException("Both " + LEGACY_DIRECTORY_NAME + " and " + DIRECTORY_NAME
                    + " exist as different directories in " + gameRoot);
        }

        migrate(legacy, canonical);
        return canonical;
    }

    private static void requireRealDirectory(Path entry) throws IOException {
        if (Files.isSymbolicLink(entry)) {
            throw new IOException("Music directory must not be a symbolic link: " + entry);
        }
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Music directory entry is not a directory: " + entry);
        }
    }

    private static void migrate(Path legacy, Path canonical) throws IOException {
        Path temporary = uniqueTemporarySibling(legacy.getParent());
        move(legacy, temporary);
        try {
            move(temporary, canonical);
        } catch (IOException originalFailure) {
            throw rollbackFailure(
                    originalFailure,
                    presence(temporary),
                    presence(legacy),
                    () -> move(temporary, legacy)
            );
        }
    }

    static IOException rollbackFailure(
            IOException originalFailure,
            Presence temporary,
            Presence legacy,
            RollbackMove rollbackMove
    ) {
        if (temporary == Presence.PRESENT && legacy == Presence.ABSENT) {
            try {
                rollbackMove.run();
            } catch (IOException restoreFailure) {
                originalFailure.addSuppressed(restoreFailure);
            }
        } else {
            originalFailure.addSuppressed(new IOException(
                    "Could not safely roll back music directory migration: temporary entry is "
                            + temporary + "; legacy entry is " + legacy
            ));
        }
        return originalFailure;
    }

    private static Presence presence(Path entry) {
        if (Files.exists(entry, LinkOption.NOFOLLOW_LINKS)) {
            return Presence.PRESENT;
        }
        if (Files.notExists(entry, LinkOption.NOFOLLOW_LINKS)) {
            return Presence.ABSENT;
        }
        return Presence.UNKNOWN;
    }

    private static Path uniqueTemporarySibling(Path gameRoot) {
        Path candidate;
        do {
            candidate = gameRoot.resolve(".areamusic-migrate-" + UUID.randomUUID());
        } while (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS));
        return candidate;
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    enum Presence {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    @FunctionalInterface
    interface RollbackMove {
        void run() throws IOException;
    }

    public static final class ConflictException extends IOException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
