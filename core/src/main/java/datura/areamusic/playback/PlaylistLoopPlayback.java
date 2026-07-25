package datura.areamusic.playback;

import datura.areamusic.area.AreaTrackDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record PlaylistLoopPlayback(
        List<String> playlist,
        float volume,
        int fadeInMs,
        int fadeOutMs
) implements PlaybackDefinition {
    public static final int MAX_ENTRIES = 256;
    public static final float DEFAULT_VOLUME = 1.0f;
    public static final int DEFAULT_FADE_IN_MS = 2000;
    public static final int DEFAULT_FADE_OUT_MS = 2000;

    public PlaylistLoopPlayback {
        Objects.requireNonNull(playlist, "playlist");
        if (playlist.isEmpty() || playlist.size() > MAX_ENTRIES) {
            throw new IllegalArgumentException(
                    "playlist must contain between 1 and " + MAX_ENTRIES + " entries"
            );
        }

        List<String> validatedPlaylist = new ArrayList<>(playlist.size());
        for (int index = 0; index < playlist.size(); index++) {
            String musicId = playlist.get(index);
            if (musicId == null) {
                throw new IllegalArgumentException("playlist[" + index + "] must not be null");
            }
            if (musicId.isBlank()) {
                throw new IllegalArgumentException("playlist[" + index + "] must not be blank");
            }
            PlaybackDefinition.validateMusicIdLength("playlist[" + index + "]", musicId);
            validatedPlaylist.add(musicId);
        }
        playlist = List.copyOf(validatedPlaylist);

        if (!Float.isFinite(volume) || volume < 0.0f || volume > 1.0f) {
            throw new IllegalArgumentException("volume must be finite and between 0 and 1");
        }
        if (volume == 0.0f) {
            volume = 0.0f;
        }
        validateFade("fadeInMs", fadeInMs);
        validateFade("fadeOutMs", fadeOutMs);
    }

    @Override
    public PlaybackMode mode() {
        return PlaybackMode.PLAYLIST_LOOP;
    }

    @Override
    public List<String> musicIds() {
        return playlist;
    }

    private static void validateFade(String name, int value) {
        if (value < 0 || value > AreaTrackDefinition.MAX_FADE_MS) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and " + AreaTrackDefinition.MAX_FADE_MS
            );
        }
    }
}
