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
    void carriesTheAreasStartOffsetAndConvertsItToFrames() {
        AreaDefinition area = AreaDefinition.create(
                "area", "minecraft:overworld",
                new datura.areamusic.area.AreaPosition(0, 0, 0),
                new datura.areamusic.area.AreaPosition(1, 1, 1),
                List.of(TRACK), false, 0
        ).withStartOffsetMs(1_500);

        PlaybackState state = PlaybackState.fromArea(area);

        assertEquals(1_500, state.startOffsetMs());
        assertEquals(66_150L, state.startOffsetFrames(44_100));
        assertEquals(0, PlaybackState.playing("area", List.of(TRACK), false).startOffsetMs());
        assertThrows(IllegalArgumentException.class, () -> PlaybackState.playing(
                "area", new ParallelPlayback(List.of(TRACK)), false, -1));
    }

    @Test
    void rejectsPlayingStateWithoutDefinition() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaybackState(true, "area", Optional.empty(), false, 0));
    }

    @Test
    void rejectsNullDefinitionContainer() {
        assertThrows(NullPointerException.class,
                () -> new PlaybackState(true, "area", null, false, 0));
    }

    @Test
    void normalizesStoppedStateFields() {
        PlaybackState stopped = new PlaybackState(
                false, "ignored", Optional.of(new ParallelPlayback(List.of(TRACK))), true, 5_000
        );

        assertEquals(PlaybackState.stopped(), stopped);
        assertEquals(0, stopped.startOffsetMs());
        assertEquals(Optional.empty(), stopped.definition());
        assertEquals(Optional.empty(), stopped.mode());
        assertEquals(List.of(), stopped.musicIds());
    }
}
