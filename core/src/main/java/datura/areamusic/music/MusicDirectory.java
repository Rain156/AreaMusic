package datura.areamusic.music;

import java.io.IOException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.UUID;
import java.util.function.Supplier;
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
        if (canonicalEntry == null && legacyEntry == null) {
            Files.createDirectory(canonical);
            return canonical;
        }
        if (canonicalEntry != null && legacyEntry == null) {
            validateDirectory(canonicalEntry);
            return canonical;
        }
        if (canonicalEntry == null) {
            migrate(validateDirectory(legacyEntry), canonical);
            return canonical;
        }

        ValidatedDirectory canonicalDirectory = validateDirectory(canonicalEntry);
        ValidatedDirectory legacyDirectory = validateDirectory(legacyEntry);
        if (!Files.isSameFile(canonicalDirectory.path(), legacyDirectory.path())) {
            throw new ConflictException("Both " + LEGACY_DIRECTORY_NAME + " and " + DIRECTORY_NAME
                    + " exist as different directories in " + gameRoot);
        }

        migrate(legacyDirectory, canonical);
        return canonical;
    }

    static ValidatedDirectory validateDirectory(Path entry) throws IOException {
        return validateDirectory(entry, MusicDirectory::readEntryAttributes);
    }

    static ValidatedDirectory validateDirectory(Path entry, IdentityProbe identityProbe) throws IOException {
        EntryAttributes attributes = identityProbe.read(entry);
        if (attributes.symbolicLink()) {
            throw new IOException("Music directory must not be a symbolic link: " + entry);
        }
        if (!attributes.directory()) {
            throw new IOException("Music directory entry is not a directory: " + entry);
        }
        DirectoryIdentity identity;
        if (attributes.fileKey() != null) {
            identity = new DirectoryIdentity(attributes.fileKey(), null, null);
        } else {
            StoreIdentity store = attributes.storeIdentity();
            if (store == null || store.name() == null || store.type() == null || attributes.creationTime() == null) {
                throw new IOException("Music directory identity metadata is unavailable: " + entry);
            }
            identity = new DirectoryIdentity(null, store, attributes.creationTime());
        }
        return new ValidatedDirectory(entry, identity);
    }

    static EntryAttributes readEntryAttributes(Path entry) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(
                entry,
                BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS
        );
        StoreIdentity storeIdentity = null;
        FileTime creationTime = null;
        if (attributes.isDirectory() && !attributes.isSymbolicLink() && attributes.fileKey() == null) {
            FileStore store = Files.getFileStore(entry);
            storeIdentity = new StoreIdentity(store.name(), store.type());
            creationTime = attributes.creationTime();
        }
        return new EntryAttributes(
                attributes.isDirectory(),
                attributes.isSymbolicLink(),
                attributes.fileKey(),
                storeIdentity,
                creationTime
        );
    }

    private static void migrate(ValidatedDirectory legacy, Path canonical) throws IOException {
        Path temporary = uniqueTemporarySibling(legacy.path().getParent());
        migrate(
                legacy,
                canonical,
                temporary,
                defaultMigrationOperations(
                        MusicDirectory::moveNoClobber,
                        MusicDirectory::readEntryAttributes
                )
        );
    }

    static void migrate(
            Path legacy,
            Path canonical,
            Path temporary,
            MoveOperation moveOperation
    ) throws IOException {
        migrate(
                validateDirectory(legacy),
                canonical,
                temporary,
                defaultMigrationOperations(moveOperation, MusicDirectory::readEntryAttributes)
        );
    }

    static void migrate(
            ValidatedDirectory legacy,
            Path canonical,
            Path temporary,
            MoveOperation moveOperation,
            IdentityProbe identityProbe
    ) throws IOException {
        migrate(legacy, canonical, temporary, defaultMigrationOperations(moveOperation, identityProbe));
    }

    static void migrate(
            ValidatedDirectory legacy,
            Path canonical,
            Path temporary,
            MigrationOperations operations
    ) throws IOException {
        operations.move().move(legacy.path(), temporary);
        try {
            ValidatedDirectory moved = validateDirectory(temporary, operations.identity());
            if (!legacy.identity().matches(moved.identity())) {
                throw new IOException("Music directory identity changed during migration: " + temporary);
            }
        } catch (IOException originalFailure) {
            throw rollbackMigrationFailure(originalFailure, legacy.path(), temporary, operations);
        }

        Presence canonicalPresence = operations.presence().presence(canonical);
        if (canonicalPresence == Presence.ABSENT) {
            installCanonical(legacy, canonical, temporary, operations);
            return;
        }
        if (canonicalPresence == Presence.UNKNOWN) {
            throw rollbackMigrationFailure(
                    new IOException("Canonical music directory presence is UNKNOWN: " + canonical),
                    legacy.path(),
                    temporary,
                    operations
            );
        }

        normalizeSameFileAlias(legacy, canonical, temporary, operations);
    }

    private static void installCanonical(
            ValidatedDirectory legacy,
            Path canonical,
            Path temporary,
            MigrationOperations operations
    ) throws IOException {
        try {
            operations.move().move(temporary, canonical);
            Presence installedPresence = operations.presence().presence(canonical);
            Presence temporaryPresence = operations.presence().presence(temporary);
            if (installedPresence != Presence.PRESENT || temporaryPresence != Presence.ABSENT) {
                throw new IOException("Could not confirm canonical music directory installation: canonical="
                        + installedPresence + "; temporary=" + temporaryPresence);
            }
            requireMatchingIdentity(legacy, canonical, operations.identity(), "installed canonical directory");
        } catch (IOException originalFailure) {
            throw rollbackMigrationFailure(originalFailure, legacy.path(), temporary, operations);
        }
    }

    private static void normalizeSameFileAlias(
            ValidatedDirectory legacy,
            Path canonical,
            Path temporary,
            MigrationOperations operations
    ) throws IOException {
        try {
            requireMatchingIdentity(legacy, canonical, operations.identity(), "canonical alias");
            if (!operations.sameFile().isSameFile(temporary, canonical)) {
                throw new IOException("Canonical music directory is not the same file as temporary: " + canonical);
            }
        } catch (IOException conflict) {
            throw rollbackMigrationFailure(conflict, legacy.path(), temporary, operations);
        }

        operations.delete().delete(temporary);

        Presence canonicalPresence = operations.presence().presence(canonical);
        Presence temporaryPresence = operations.presence().presence(temporary);
        if (canonicalPresence != Presence.PRESENT || temporaryPresence != Presence.ABSENT) {
            throw new IOException("Could not confirm same-file alias cleanup: canonical="
                    + canonicalPresence + "; temporary=" + temporaryPresence);
        }
        requireMatchingIdentity(legacy, canonical, operations.identity(), "canonical alias after cleanup");
    }

    private static void requireMatchingIdentity(
            ValidatedDirectory expected,
            Path candidate,
            IdentityProbe identityProbe,
            String description
    ) throws IOException {
        ValidatedDirectory actual = validateDirectory(candidate, identityProbe);
        if (!expected.identity().matches(actual.identity())) {
            throw new IOException("Music directory identity conflict for " + description + ": " + candidate);
        }
    }

    private static IOException rollbackMigrationFailure(
            IOException originalFailure,
            Path legacy,
            Path temporary,
            MigrationOperations operations
    ) {
        return rollbackFailure(
                originalFailure,
                operations.presence().presence(temporary),
                operations.presence().presence(legacy),
                temporary,
                legacy,
                operations.move()
        );
    }

    private static MigrationOperations defaultMigrationOperations(
            MoveOperation moveOperation,
            IdentityProbe identityProbe
    ) {
        return new MigrationOperations(
                moveOperation,
                MusicDirectory::presence,
                identityProbe,
                Files::isSameFile,
                Files::delete
        );
    }

    static IOException rollbackFailure(
            IOException originalFailure,
            Presence temporary,
            Presence legacy,
            Path temporaryPath,
            Path legacyPath,
            MoveOperation moveOperation
    ) {
        if (temporary == Presence.PRESENT && legacy == Presence.ABSENT) {
            try {
                moveOperation.move(temporaryPath, legacyPath);
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

    private static Path uniqueTemporarySibling(Path gameRoot) throws IOException {
        return uniqueTemporarySibling(gameRoot, UUID::randomUUID, MusicDirectory::presence);
    }

    static Path uniqueTemporarySibling(
            Path gameRoot,
            Supplier<UUID> uuidSupplier,
            PresenceProbe presenceProbe
    ) throws IOException {
        while (true) {
            Path candidate = gameRoot.resolve(".areamusic-migrate-" + uuidSupplier.get());
            Presence candidatePresence = presenceProbe.presence(candidate);
            if (candidatePresence == Presence.PRESENT) {
                continue;
            }
            if (candidatePresence == Presence.ABSENT) {
                return candidate;
            }
            throw new IOException("Temporary music migration path presence is UNKNOWN: " + candidate);
        }
    }

    static void moveNoClobber(Path source, Path target) throws IOException {
        // ATOMIC_MOVE makes replacement of an existing target provider-defined; no options is portable no-clobber.
        Files.move(source, target);
    }

    enum Presence {
        PRESENT,
        ABSENT,
        UNKNOWN
    }

    @FunctionalInterface
    interface MoveOperation {
        void move(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface PresenceProbe {
        Presence presence(Path path);
    }

    @FunctionalInterface
    interface IdentityProbe {
        EntryAttributes read(Path path) throws IOException;
    }

    @FunctionalInterface
    interface SameFileProbe {
        boolean isSameFile(Path first, Path second) throws IOException;
    }

    @FunctionalInterface
    interface DeleteOperation {
        void delete(Path path) throws IOException;
    }

    record MigrationOperations(
            MoveOperation move,
            PresenceProbe presence,
            IdentityProbe identity,
            SameFileProbe sameFile,
            DeleteOperation delete
    ) {
    }

    record EntryAttributes(
            boolean directory,
            boolean symbolicLink,
            Object fileKey,
            StoreIdentity storeIdentity,
            FileTime creationTime
    ) {
    }

    record StoreIdentity(String name, String type) {
    }

    record DirectoryIdentity(Object fileKey, StoreIdentity storeIdentity, FileTime creationTime) {
        private boolean matches(DirectoryIdentity other) {
            if (fileKey != null || other.fileKey != null) {
                return fileKey != null && other.fileKey != null && fileKey.equals(other.fileKey);
            }
            // Without a portable file ID, this is the best portable check for ordinary namespace races.
            // It does not claim to resist deliberately forged identical metadata.
            return storeIdentity != null
                    && creationTime != null
                    && storeIdentity.equals(other.storeIdentity)
                    && creationTime.equals(other.creationTime);
        }
    }

    record ValidatedDirectory(Path path, DirectoryIdentity identity) {
    }

    public static final class ConflictException extends IOException {
        public ConflictException(String message) {
            super(message);
        }
    }
}
