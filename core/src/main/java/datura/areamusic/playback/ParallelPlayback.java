package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;

import java.util.List;
import java.util.Objects;

public record ParallelPlayback(List<AreaTrackDefinition> tracks) implements PlaybackDefinition {
    public ParallelPlayback {
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (tracks.isEmpty() || tracks.size() > AreaDefinition.MAX_TRACKS) {
            throw new IllegalArgumentException(
                    "Track count must be between 1 and " + AreaDefinition.MAX_TRACKS
            );
        }
        for (int index = 0; index < tracks.size(); index++) {
            PlaybackDefinition.validateMusicIdLength(
                    "tracks[" + index + "].musicId", tracks.get(index).musicId()
            );
        }
    }

    @Override
    public PlaybackMode mode() {
        return PlaybackMode.PARALLEL;
    }

    @Override
    public List<String> musicIds() {
        return tracks.stream().map(AreaTrackDefinition::musicId).toList();
    }
}
