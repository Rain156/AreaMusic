package datura.areamusic.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MusicManifestTest {
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final String OTHER = "0".repeat(64);

    @TempDir
    Path tempDir;

    @Test
    void sha256MatchesKnownVector() throws Exception {
        Path file = tempDir.resolve("abc.bin");
        Files.writeString(file, "abc");

        assertEquals(ABC, Sha256.of(file));
        assertEquals(64, Sha256.hex(Sha256.newDigest().digest()).length());
    }

    @Test
    void fingerprintValidatesItsFields() {
        assertEquals("ogg", new MusicFingerprint("Village/Day.OGG", 3, ABC).extension());
        assertEquals("", new MusicFingerprint("folder.v2/noext", 3, ABC).extension());
        assertThrows(IllegalArgumentException.class, () -> new MusicFingerprint(" ", 3, ABC));
        assertThrows(IllegalArgumentException.class, () -> new MusicFingerprint("a.ogg", -1, ABC));
        assertThrows(IllegalArgumentException.class,
                () -> new MusicFingerprint("a.ogg", 3, ABC.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> new MusicFingerprint("a.ogg", 3, "abc"));
    }

    @Test
    void entriesAreSortedAndFindable() {
        MusicFingerprint night = new MusicFingerprint("night.ogg", 5, OTHER);
        MusicFingerprint day = new MusicFingerprint("day.ogg", 3, ABC);
        MusicManifest manifest = MusicManifest.of(List.of(night, day));

        assertEquals(List.of(day, night), manifest.entries());
        assertEquals(Optional.of(day), manifest.find("day.ogg"));
        assertEquals(Optional.empty(), manifest.find("dawn.ogg"));
        assertEquals(Optional.of(night), manifest.findBySha256(OTHER));
        assertEquals(Optional.empty(), manifest.findBySha256("1".repeat(64)));
    }

    @Test
    void subsetIgnoresUnknownIds() {
        MusicFingerprint night = new MusicFingerprint("night.ogg", 5, OTHER);
        MusicFingerprint day = new MusicFingerprint("day.ogg", 3, ABC);
        MusicManifest manifest = MusicManifest.of(List.of(night, day));

        assertEquals(MusicManifest.of(List.of(day)), manifest.subset(List.of("day.ogg", "gone.ogg", "day.ogg")));
        assertEquals(MusicManifest.empty(), manifest.subset(List.of()));
        assertNotEquals(manifest, manifest.subset(List.of("day.ogg")));
        assertTrue(MusicManifest.empty().entries().isEmpty());
    }

    @Test
    void duplicateMusicIdsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> MusicManifest.of(List.of(
                new MusicFingerprint("day.ogg", 3, ABC),
                new MusicFingerprint("day.ogg", 5, OTHER)
        )));
    }
}
