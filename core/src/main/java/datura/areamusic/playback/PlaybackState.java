package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

public record PlaybackState(
        boolean playing,
        String areaId,
        Optional<PlaybackDefinition> definition,
        boolean resumeOnReenter
) {
    public PlaybackState {
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(definition, "definition");
        if (!playing) {
            areaId = "";
            definition = Optional.empty();
            resumeOnReenter = false;
        } else {
            if (areaId.isBlank()) {
                throw new IllegalArgumentException("Area ID must not be blank");
            }
            if (definition.isEmpty()) {
                throw new IllegalArgumentException("Playing state must contain a playback definition");
            }
        }
    }

    public static PlaybackState stopped() {
        return new PlaybackState(false, "", Optional.empty(), false);
    }

    public static PlaybackState playing(
            String areaId,
            List<AreaTrackDefinition> tracks,
            boolean resumeOnReenter
    ) {
        return playing(areaId, new ParallelPlayback(tracks), resumeOnReenter);
    }

    public static PlaybackState playing(
            String areaId,
            PlaybackDefinition definition,
            boolean resumeOnReenter
    ) {
        return new PlaybackState(
                true, areaId, Optional.of(Objects.requireNonNull(definition, "definition")),
                resumeOnReenter
        );
    }

    public static PlaybackState playingPlaylistLoop(
            String areaId,
            List<String> playlist,
            float volume,
            int fadeInMs,
            int fadeOutMs,
            boolean resumeOnReenter
    ) {
        return playing(
                areaId,
                new PlaylistLoopPlayback(playlist, volume, fadeInMs, fadeOutMs),
                resumeOnReenter
        );
    }

    public static PlaybackState fromArea(AreaDefinition area) {
        Objects.requireNonNull(area, "area");
        return playing(area.id(), area.playback(), area.resumeOnReenter());
    }

    public Optional<PlaybackMode> mode() {
        return definition.map(PlaybackDefinition::mode);
    }

    public List<AreaTrackDefinition> tracks() {
        return definition
                .filter(ParallelPlayback.class::isInstance)
                .map(ParallelPlayback.class::cast)
                .map(ParallelPlayback::tracks)
                .orElseGet(List::of);
    }

    public List<String> musicIds() {
        return definition.map(PlaybackDefinition::musicIds).orElseGet(List::of);
    }
}
