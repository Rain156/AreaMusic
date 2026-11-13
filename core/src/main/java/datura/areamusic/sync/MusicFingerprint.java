package datura.areamusic.sync;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Identifies one audio file's exact content: two files with the same hash are interchangeable. */
public record MusicFingerprint(String musicId, long size, String sha256) {
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public MusicFingerprint {
        Objects.requireNonNull(musicId, "musicId");
        Objects.requireNonNull(sha256, "sha256");
        if (musicId.isBlank()) {
            throw new IllegalArgumentException("musicId must not be blank");
        }
        if (size < 0) {
            throw new IllegalArgumentException("size must not be negative");
        }
        if (!SHA_256.matcher(sha256).matches()) {
            throw new IllegalArgumentException("sha256 must be 64 lowercase hex characters");
        }
    }

    /** Lowercase file extension without the dot, or an empty string. */
    public String extension() {
        int separator = musicId.lastIndexOf('/');
        int dot = musicId.lastIndexOf('.');
        return dot > separator && dot < musicId.length() - 1
                ? musicId.substring(dot + 1).toLowerCase(Locale.ROOT)
                : "";
    }
}
