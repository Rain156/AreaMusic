package datura.areamusic.sync;

import datura.areamusic.music.MusicLibrary;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MusicFingerprinterTest {
    private static final String ABC = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path tempDir;

    @Test
    void hashesEveryFileOnceAndReusesUnchangedResults() throws Exception {
        Files.createDirectories(tempDir.resolve("village"));
        Files.writeString(tempDir.resolve("village/day.ogg"), "abc");
        Files.writeString(tempDir.resolve("night.mp3"), "night");
        MusicFingerprinter fingerprinter = new MusicFingerprinter();

        MusicManifest first = fingerprinter.fingerprint(MusicLibrary.scan(tempDir));
        MusicManifest second = fingerprinter.fingerprint(MusicLibrary.scan(tempDir));

        assertEquals(new MusicFingerprint("village/day.ogg", 3, ABC), first.find("village/day.ogg").orElseThrow());
        assertEquals(5, first.find("night.mp3").orElseThrow().size());
        assertEquals(first, second);
        assertEquals(2, fingerprinter.hashedFileCount());
    }

    @Test
    void rehashesChangedFilesAndForgetsDeletedOnes() throws Exception {
        Path day = tempDir.resolve("day.ogg");
        Path night = tempDir.resolve("night.ogg");
        Files.writeString(day, "abc");
        Files.writeString(night, "night");
        MusicFingerprinter fingerprinter = new MusicFingerprinter();
        MusicManifest before = fingerprinter.fingerprint(MusicLibrary.scan(tempDir));

        Files.writeString(day, "xyz");
        Files.setLastModifiedTime(day, FileTime.fromMillis(
                Files.getLastModifiedTime(day).toMillis() + 5_000));
        Files.delete(night);
        MusicManifest after = fingerprinter.fingerprint(MusicLibrary.scan(tempDir));

        assertEquals(List.of("day.ogg"), after.entries().stream().map(MusicFingerprint::musicId).toList());
        assertNotEquals(before.find("day.ogg"), after.find("day.ogg"));
        assertEquals(3, fingerprinter.hashedFileCount());
    }

    @Test
    void libraryOfResolvesIdsToArbitraryFiles() throws Exception {
        Path cached = tempDir.resolve("cache/abcdef.ogg");
        Files.createDirectories(cached.getParent());
        Files.writeString(cached, "abc");

        MusicLibrary library = MusicLibrary.of(tempDir.resolve("areamusic"), Map.of("village/day.ogg", cached));

        assertEquals(cached, library.find("village/day.ogg").orElseThrow());
        assertEquals(ABC, new MusicFingerprinter().fingerprint(library).find("village/day.ogg").orElseThrow().sha256());
    }
}
