package datura.areamusic.sync;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Finds the local file that holds the content a server asked for: the player's own music folder
 * first, then the download cache.
 */
public final class MusicCache {
    private static final String TEMPORARY_SUFFIX = ".part";

    private final Path libraryRoot;
    private final Path cacheRoot;
    private final Map<Path, Remembered> remembered = new HashMap<>();

    public MusicCache(Path libraryRoot, Path cacheRoot) {
        this.libraryRoot = Objects.requireNonNull(libraryRoot, "libraryRoot").toAbsolutePath().normalize();
        this.cacheRoot = Objects.requireNonNull(cacheRoot, "cacheRoot").toAbsolutePath().normalize();
    }

    public synchronized Optional<Path> find(MusicFingerprint fingerprint) throws IOException {
        Objects.requireNonNull(fingerprint, "fingerprint");
        Path local = libraryRoot.resolve(fingerprint.musicId()).normalize();
        if (local.startsWith(libraryRoot)
                && isPlainFile(local)
                && Files.size(local) == fingerprint.size()
                && localSha256(local).equals(fingerprint.sha256())) {
            return Optional.of(local);
        }
        Path cached = cachePath(fingerprint);
        if (isPlainFile(cached) && Files.size(cached) == fingerprint.size()) {
            return Optional.of(cached);
        }
        return Optional.empty();
    }

    /** Where a download with this content is kept. Named by hash, so it never collides. */
    public Path cachePath(MusicFingerprint fingerprint) {
        String extension = fingerprint.extension();
        return cacheRoot.resolve(
                extension.isEmpty() ? fingerprint.sha256() : fingerprint.sha256() + "." + extension);
    }

    public Path newTemporaryPath(MusicFingerprint fingerprint) {
        return cacheRoot.resolve(
                "." + fingerprint.sha256() + "-" + UUID.randomUUID() + TEMPORARY_SUFFIX);
    }

    /** Deletes downloads that a crash or disconnect left unfinished. */
    public int sweepTemporaryFiles() throws IOException {
        if (!Files.isDirectory(cacheRoot)) {
            return 0;
        }
        int deleted = 0;
        try (Stream<Path> files = Files.list(cacheRoot)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (file.getFileName().toString().endsWith(TEMPORARY_SUFFIX)
                        && Files.deleteIfExists(file)) {
                    deleted++;
                }
            }
        }
        return deleted;
    }

    private String localSha256(Path file) throws IOException {
        long size = Files.size(file);
        long modified = Files.getLastModifiedTime(file).toMillis();
        Remembered known = remembered.get(file);
        if (known == null || known.size != size || known.modified != modified) {
            known = new Remembered(size, modified, Sha256.of(file));
            remembered.put(file, known);
        }
        return known.sha256;
    }

    private static boolean isPlainFile(Path file) {
        return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(file);
    }

    private record Remembered(long size, long modified, String sha256) {
    }
}
