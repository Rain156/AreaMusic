package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;

import java.util.List;
import java.util.Objects;

public record PlaybackState(
        boolean playing,
        String areaId,
        List<AreaTrackDefinition> tracks,
        boolean resumeOnReenter
) {
    public PlaybackState {
        Objects.requireNonNull(areaId, "areaId");
        tracks = List.copyOf(Objects.requireNonNull(tracks, "tracks"));
        if (!playing) {
            areaId = "";
            tracks = List.of();
            resumeOnReenter = false;
        } else {
            if (areaId.isBlank()) {
                throw new IllegalArgumentException("Area ID must not be blank");
            }
            if (tracks.isEmpty() || tracks.size() > AreaDefinition.MAX_TRACKS) {
                throw new IllegalArgumentException(
                        "Track count must be between 1 and " + AreaDefinition.MAX_TRACKS
                );
            }
        }
    }

    public static PlaybackState stopped() {
        return new PlaybackState(false, "", List.of(), false);
    }

    public static PlaybackState playing(
            String areaId,
            List<AreaTrackDefinition> tracks,
            boolean resumeOnReenter
    ) {
        return new PlaybackState(true, areaId, tracks, resumeOnReenter);
    }

    public static PlaybackState fromArea(AreaDefinition area) {
        return playing(area.id(), area.tracks(), area.resumeOnReenter());
    }
}
