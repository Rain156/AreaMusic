package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PlaybackStateTest {
    private static final AreaTrackDefinition TRACK =
            new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000);

    @Test
    void rejectsPlayingStateWithoutTracks() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("area", List.of(), false));
    }

    @Test
    void rejectsPlayingStateAboveTrackLimit() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing(
                        "area", Collections.nCopies(AreaDefinition.MAX_TRACKS + 1, TRACK), false
                ));
    }

    @Test
    void rejectsBlankPlayingAreaId() {
        assertThrows(IllegalArgumentException.class,
                () -> PlaybackState.playing("", List.of(TRACK), false));
    }

    @Test
    void defensivelyCopiesPlayingTracks() {
        List<AreaTrackDefinition> source = new ArrayList<>(List.of(TRACK));

        PlaybackState state = PlaybackState.playing("area", source, false);
        source.clear();

        assertEquals(List.of(TRACK), state.tracks());
        assertThrows(UnsupportedOperationException.class, () -> state.tracks().clear());
    }

    @Test
    void normalizesStoppedStateFields() {
        PlaybackState stopped = new PlaybackState(false, "ignored", List.of(TRACK), true);

        assertEquals(PlaybackState.stopped(), stopped);
    }
}
