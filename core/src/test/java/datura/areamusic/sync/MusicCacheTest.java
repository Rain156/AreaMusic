package datura.areamusic.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicCacheTest {
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path tempDir;
    private Path libraryRoot;
    private Path cacheRoot;
    private MusicCache cache;

    @BeforeEach
    void setUp() throws Exception {
        libraryRoot = tempDir.resolve("areamusic");
        cacheRoot = tempDir.resolve("areamusic-cache");
        Files.createDirectories(libraryRoot.resolve("village"));
        cache = new MusicCache(libraryRoot, cacheRoot);
    }

    @Test
    void prefersTheLocalFileWhenItsContentMatches() throws Exception {
        MusicFingerprint fingerprint = new MusicFingerprint("village/day.ogg", 3, ABC);
        Path local = libraryRoot.resolve("village/day.ogg");
        Files.writeString(local, "abc");
        Files.createDirectories(cacheRoot);
        Files.writeString(cache.cachePath(fingerprint), "abc");

        assertEquals(Optional.of(local), cache.find(fingerprint));
    }

    @Test
    void localFileWithDifferentContentIsNotAHit() throws Exception {
        MusicFingerprint fingerprint = new MusicFingerprint("village/day.ogg", 3, ABC);
        Files.writeString(libraryRoot.resolve("village/day.ogg"), "xyz");

        assertEquals(Optional.empty(), cache.find(fingerprint));

        Files.createDirectories(cacheRoot);
        Files.writeString(cache.cachePath(fingerprint), "abc");
        assertEquals(Optional.of(cache.cachePath(fingerprint)), cache.find(fingerprint));
    }

    @Test
    void cacheFileOfTheWrongSizeIsNotAHit() throws Exception {
        MusicFingerprint fingerprint = new MusicFingerprint("village/day.ogg", 3, ABC);
        Files.createDirectories(cacheRoot);
        Files.writeString(cache.cachePath(fingerprint), "ab");

        assertEquals(Optional.empty(), cache.find(fingerprint));
    }

    @Test
    void cachePathIsNamedByHashAndKeepsTheExtension() {
        MusicFingerprint fingerprint = new MusicFingerprint("Village/Day Theme.OGG", 3, ABC);

        assertEquals(cacheRoot.resolve(ABC + ".ogg"), cache.cachePath(fingerprint));
        assertEquals(cacheRoot.resolve(ABC), cache.cachePath(new MusicFingerprint("noext", 3, ABC)));
    }

    @Test
    void musicIdCannotEscapeTheLibraryRoot() throws Exception {
        Files.writeString(tempDir.resolve("secret.ogg"), "abc");

        assertEquals(Optional.empty(), cache.find(new MusicFingerprint("../secret.ogg", 3, ABC)));
        assertEquals(Optional.empty(),
                cache.find(new MusicFingerprint("village/../../secret.ogg", 3, ABC)));
    }

    @Test
    void sweepDeletesOnlyUnfinishedDownloads() throws Exception {
        MusicFingerprint fingerprint = new MusicFingerprint("day.ogg", 3, ABC);
        assertEquals(0, cache.sweepTemporaryFiles());
        Files.createDirectories(cacheRoot);
        Path finished = cache.cachePath(fingerprint);
        Path first = cache.newTemporaryPath(fingerprint);
        Path second = cache.newTemporaryPath(fingerprint);
        Files.writeString(finished, "abc");
        Files.writeString(first, "a");
        Files.writeString(second, "ab");

        assertNotEquals(first, second);
        assertTrue(first.getFileName().toString().startsWith("."));
        assertEquals(Optional.of(finished), cache.find(fingerprint));
        assertEquals(2, cache.sweepTemporaryFiles());
        assertTrue(Files.exists(finished));
        assertFalse(Files.exists(first));
        assertFalse(Files.exists(second));
    }
}
