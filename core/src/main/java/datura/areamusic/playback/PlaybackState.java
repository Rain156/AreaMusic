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
        boolean resumeOnReenter,
        int startOffsetMs
) {
    public PlaybackState {
        Objects.requireNonNull(areaId, "areaId");
        Objects.requireNonNull(definition, "definition");
        if (!playing) {
            areaId = "";
            definition = Optional.empty();
            resumeOnReenter = false;
            startOffsetMs = 0;
        } else {
            if (areaId.isBlank()) {
                throw new IllegalArgumentException("Area ID must not be blank");
            }
            if (definition.isEmpty()) {
                throw new IllegalArgumentException("Playing state must contain a playback definition");
            }
            if (startOffsetMs < 0 || startOffsetMs > AreaDefinition.MAX_START_OFFSET_MS) {
                throw new IllegalArgumentException(
                        "startOffsetMs must be between 0 and " + AreaDefinition.MAX_START_OFFSET_MS
                );
            }
        }
    }

    public static PlaybackState stopped() {
        return new PlaybackState(false, "", Optional.empty(), false, 0);
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
        return playing(areaId, definition, resumeOnReenter, 0);
    }

    /**
     * @param startOffsetMs where in the music playback begins, unless it is being resumed
     */
    public static PlaybackState playing(
            String areaId,
            PlaybackDefinition definition,
            boolean resumeOnReenter,
            int startOffsetMs
    ) {
        return new PlaybackState(
                true, areaId, Optional.of(Objects.requireNonNull(definition, "definition")),
                resumeOnReenter, startOffsetMs
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

    /**
     * What auditioning an area in the editor plays. It is never a real area's ID, so it cannot be
     * mistaken for one, and it always starts from scratch: see {@link #preview()}.
     */
    public static final String PREVIEW_AREA_ID = "#preview";

    public static PlaybackState preview(PlaybackDefinition definition, int startOffsetMs) {
        return playing(PREVIEW_AREA_ID, definition, false, startOffsetMs);
    }

    /**
     * Whether this is an audition rather than an area the player is standing in. An audition
     * begins at its start position every time; it never carries on a stream that is already
     * playing, which is what walking between two areas with the same music does.
     */
    public boolean preview() {
        return playing && areaId.equals(PREVIEW_AREA_ID);
    }

    public static PlaybackState fromArea(AreaDefinition area) {
        Objects.requireNonNull(area, "area");
        return playing(area.id(), area.playback(), area.resumeOnReenter(), area.startOffsetMs());
    }

    /** {@link #startOffsetMs} in audio frames. */
    public long startOffsetFrames(int sampleRate) {
        return (long) startOffsetMs * sampleRate / 1000L;
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
