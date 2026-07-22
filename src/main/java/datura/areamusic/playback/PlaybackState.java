package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;

import java.util.Objects;

public record PlaybackState(
        boolean playing,
        String areaId,
        String musicId,
        float volume,
        boolean loop,
        int fadeInMs,
        int fadeOutMs
) {
    public PlaybackState {
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(musicId, "musicId");
        if (!playing) {
            areaId = "";
            musicId = "";
            volume = 0.0f;
            loop = false;
            fadeInMs = 0;
            fadeOutMs = 0;
        } else {
            if (areaId.isBlank()) {
                throw new IllegalArgumentException("Area ID must not be blank");
            }
            if (musicId.isBlank()) {
                throw new IllegalArgumentException("Music ID must not be blank");
            }
            if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
                throw new IllegalArgumentException("Volume must be finite and between 0 and 1");
            }
            validateFade("fadeInMs", fadeInMs);
            validateFade("fadeOutMs", fadeOutMs);
        }
    }

    public static PlaybackState stopped() {
        return new PlaybackState(false, "", "", 0.0f, false, 0, 0);
    }

    public static PlaybackState playing(
            String areaId,
            String musicId,
            float volume,
            boolean loop,
            int fadeInMs,
            int fadeOutMs
    ) {
        return new PlaybackState(true, areaId, musicId, volume, loop, fadeInMs, fadeOutMs);
    }

    public static PlaybackState fromArea(AreaDefinition area) {
        AreaTrackDefinition track = area.tracks().get(0);
        return playing(
                area.id(), track.musicId(), track.volume(), track.loop(), track.fadeInMs(), track.fadeOutMs()
        );
    }

    private static void validateFade(String name, int value) {
        if (value < 0 || value > AreaTrackDefinition.MAX_FADE_MS) {
            throw new IllegalArgumentException(name + " must be between 0 and " + AreaTrackDefinition.MAX_FADE_MS);
        }
    }
}
