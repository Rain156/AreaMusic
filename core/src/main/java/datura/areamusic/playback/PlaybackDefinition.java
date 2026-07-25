package datura.areamusic.playback;

import java.util.List;

public sealed interface PlaybackDefinition permits ParallelPlayback, PlaylistLoopPlayback {
    int MAX_MUSIC_ID_LENGTH = 1024;

    PlaybackMode mode();

    List<String> musicIds();

    static void validateMusicIdLength(String location, String musicId) {
        if (musicId.length() > MAX_MUSIC_ID_LENGTH) {
            throw new IllegalArgumentException(
                    location + " must be at most " + MAX_MUSIC_ID_LENGTH + " characters"
            );
        }
    }
}
