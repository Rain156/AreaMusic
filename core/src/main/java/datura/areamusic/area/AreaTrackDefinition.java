package datura.areamusic.area;

import java.util.Objects;

public record AreaTrackDefinition(
        String musicId,
        int delaySeconds,
        float volume,
        boolean loop,
        int fadeInMs,
        int fadeOutMs
) {
    public static final int MAX_FADE_MS = 60_000;

    public AreaTrackDefinition {
        Objects.requireNonNull(musicId, "musicId");
        if (musicId.isBlank()) {
            throw new IllegalArgumentException("musicId must not be blank");
        }
        if (delaySeconds < 0) {
            throw new IllegalArgumentException("delaySeconds must not be negative");
        }
        if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
            throw new IllegalArgumentException("volume must be finite and between 0 and 1");
        }
        if (volume == 0.0f) {
            volume = 0.0f;
        }
        validateFade("fadeInMs", fadeInMs);
        validateFade("fadeOutMs", fadeOutMs);
    }

    public long delayFrames(int sampleRate) {
        if (sampleRate <= 0) {
            throw new IllegalArgumentException("sampleRate must be positive");
        }
        return (long) delaySeconds * sampleRate;
    }

    private static void validateFade(String name, int value) {
        if (value < 0 || value > MAX_FADE_MS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + MAX_FADE_MS);
        }
    }
}
