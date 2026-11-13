package datura.areamusic.sync;

import datura.areamusic.music.MusicLibrary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Hashes a music library, remembering files that have not changed since the last call. */
public final class MusicFingerprinter {
    private Map<Path, Remembered> remembered = new HashMap<>();
    private int hashedFileCount;

    public synchronized MusicManifest fingerprint(MusicLibrary library) throws IOException {
        Objects.requireNonNull(library, "library");
        Map<Path, Remembered> current = new HashMap<>();
        List<MusicFingerprint> fingerprints = new ArrayList<>();
        for (String musicId : library.ids()) {
            Path file = library.find(musicId).orElseThrow();
            long size = Files.size(file);
            long modified = Files.getLastModifiedTime(file).toMillis();
            Remembered known = remembered.get(file);
            if (known == null || known.size != size || known.modified != modified) {
                known = new Remembered(size, modified, Sha256.of(file));
                hashedFileCount++;
            }
            current.put(file, known);
            fingerprints.add(new MusicFingerprint(musicId, size, known.sha256));
        }
        remembered = current;
        return MusicManifest.of(fingerprints);
    }

    /** How many files have actually been hashed so far; unchanged files are not hashed again. */
    public synchronized int hashedFileCount() {
        return hashedFileCount;
    }

    private record Remembered(long size, long modified, String sha256) {
    }
}
