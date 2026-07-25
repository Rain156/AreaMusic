package datura.areamusic.client.audio;

import java.util.Objects;

public record AudioFailure(Kind kind, String musicId, Throwable cause) {
    public AudioFailure {
        Objects.requireNonNull(kind, "kind");
        musicId = Objects.requireNonNull(musicId, "musicId");
        Objects.requireNonNull(cause, "cause");
    }

    public String deduplicationKey() {
        return kind.name() + ':' + musicId;
    }

    public enum Kind {
        MISSING_FILE,
        DECODE,
        DEVICE,
        SCAN,
        THREAD
    }
}
