package datura.areamusic.sync;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/** The fingerprints of a set of music files, ordered by MusicID. */
public final class MusicManifest {
    private static final MusicManifest EMPTY = new MusicManifest(new TreeMap<>());

    private final TreeMap<String, MusicFingerprint> byId;
    private final Map<String, MusicFingerprint> bySha256 = new HashMap<>();
    private final List<MusicFingerprint> entries;

    private MusicManifest(TreeMap<String, MusicFingerprint> byId) {
        this.byId = byId;
        entries = List.copyOf(byId.values());
        for (MusicFingerprint fingerprint : entries) {
            bySha256.putIfAbsent(fingerprint.sha256(), fingerprint);
        }
    }

    public static MusicManifest empty() {
        return EMPTY;
    }

    public static MusicManifest of(Collection<MusicFingerprint> fingerprints) {
        Objects.requireNonNull(fingerprints, "fingerprints");
        TreeMap<String, MusicFingerprint> byId = new TreeMap<>();
        for (MusicFingerprint fingerprint : fingerprints) {
            if (byId.put(fingerprint.musicId(), fingerprint) != null) {
                throw new IllegalArgumentException("Duplicate MusicID: " + fingerprint.musicId());
            }
        }
        return new MusicManifest(byId);
    }

    public List<MusicFingerprint> entries() {
        return entries;
    }

    public Optional<MusicFingerprint> find(String musicId) {
        return Optional.ofNullable(byId.get(musicId));
    }

    /** Any entry with this content; several MusicIDs may share one hash. */
    public Optional<MusicFingerprint> findBySha256(String sha256) {
        return Optional.ofNullable(bySha256.get(sha256));
    }

    /** The entries for the given MusicIDs; IDs this manifest does not know are left out. */
    public MusicManifest subset(Collection<String> musicIds) {
        TreeMap<String, MusicFingerprint> selected = new TreeMap<>();
        for (String musicId : musicIds) {
            MusicFingerprint fingerprint = byId.get(musicId);
            if (fingerprint != null) {
                selected.put(musicId, fingerprint);
            }
        }
        return new MusicManifest(selected);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MusicManifest manifest && entries.equals(manifest.entries);
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        return "MusicManifest" + entries;
    }
}
