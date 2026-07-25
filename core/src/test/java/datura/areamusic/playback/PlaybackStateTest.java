package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

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
    void playlistFactoryExposesExactlyOneDefinitionAndNoParallelTracks() {
        PlaybackState state = PlaybackState.playingPlaylistLoop(
                "area", List.of("first.ogg", "second.ogg"),
                0.75f, 250, 900, true
        );

        assertEquals(PlaybackMode.PLAYLIST_LOOP, state.mode().orElseThrow());
        assertEquals(
                new PlaylistLoopPlayback(List.of("first.ogg", "second.ogg"), 0.75f, 250, 900),
                state.definition().orElseThrow()
        );
        assertEquals(List.of(), state.tracks());
    }

    @Test
    void fromAreaPreservesPlaylistDefinition() {
        AreaDefinition area = AreaDefinition.createPlaylistLoop(
                "area", "minecraft:overworld",
                new datura.areamusic.area.AreaPosition(0, 0, 0),
                new datura.areamusic.area.AreaPosition(1, 1, 1),
                List.of("first.ogg", "second.ogg"),
                0.75f, 250, 900, true, 4
        );

        PlaybackState state = PlaybackState.fromArea(area);

        assertEquals(Optional.of(area.playback()), state.definition());
        assertEquals(Optional.of(PlaybackMode.PLAYLIST_LOOP), state.mode());
        assertEquals(area.musicIds(), state.musicIds());
    }

    @Test
    void rejectsPlayingStateWithoutDefinition() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackState(true, "area", Optional.empty(), false));
    }

    @Test
    void rejectsNullDefinitionContainer() {
        assertThrows(NullPointerException.class,
                () -> new PlaybackState(true, "area", null, false));
    }

    @Test
    void normalizesStoppedStateFields() {
        PlaybackState stopped = new PlaybackState(
                false, "ignored", Optional.of(new ParallelPlayback(List.of(TRACK))), true
        );

        assertEquals(PlaybackState.stopped(), stopped);
        assertEquals(Optional.empty(), stopped.definition());
        assertEquals(Optional.empty(), stopped.mode());
        assertEquals(List.of(), stopped.musicIds());
    }
}
