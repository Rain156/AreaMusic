package datura.areamusic.playback;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaTrackDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlaybackDefinitionTest {
    private static final AreaTrackDefinition TRACK =
            new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000);

    @Test
    void playbackModesHaveStableJsonIds() {
        assertEquals("parallel", PlaybackMode.PARALLEL.jsonId());
        assertEquals("playlist_loop", PlaybackMode.PLAYLIST_LOOP.jsonId());
        assertSame(PlaybackMode.PARALLEL, PlaybackMode.fromJsonId("parallel"));
        assertSame(PlaybackMode.PLAYLIST_LOOP, PlaybackMode.fromJsonId("playlist_loop"));
        assertThrows(IllegalArgumentException.class, () -> PlaybackMode.fromJsonId("random"));
    }

    @Test
    void playbackModesHaveStableNetworkIds() {
        assertEquals(0, PlaybackMode.PARALLEL.networkId());
        assertEquals(1, PlaybackMode.PLAYLIST_LOOP.networkId());
        assertSame(PlaybackMode.PARALLEL, PlaybackMode.fromNetworkId(0));
        assertSame(PlaybackMode.PLAYLIST_LOOP, PlaybackMode.fromNetworkId(1));
        assertThrows(IllegalArgumentException.class, () -> PlaybackMode.fromNetworkId(2));
    }

    @Test
    void parallelPlaybackDefensivelyCopiesTracksAndExposesMusicIds() {
        List<AreaTrackDefinition> source = new ArrayList<>(List.of(TRACK));

        ParallelPlayback playback = new ParallelPlayback(source);
        source.clear();

        assertEquals(List.of(TRACK), playback.tracks());
        assertEquals(List.of("track.ogg"), playback.musicIds());
        assertThrows(UnsupportedOperationException.class, () -> playback.tracks().clear());
        assertThrows(UnsupportedOperationException.class, () -> playback.musicIds().clear());
    }

    @Test
    void parallelPlaybackRejectsTrackCountsOutsideBounds() {
        assertThrows(IllegalArgumentException.class, () -> new ParallelPlayback(List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ParallelPlayback(
                Collections.nCopies(AreaDefinition.MAX_TRACKS + 1, TRACK)
        ));
    }

    @Test
    void parallelPlaybackAcceptsMusicIdAt1024CharacterLimit() {
        String musicId = "x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH);

        ParallelPlayback playback = new ParallelPlayback(List.of(track(musicId)));

        assertEquals(musicId, playback.musicIds().get(0));
    }

    @Test
    void parallelPlaybackRejectsOverlongMusicIdWithTrackIndex() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new ParallelPlayback(List.of(
                        TRACK, track("x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH + 1))
                )));

        assertTrue(error.getMessage().contains("tracks[1]"));
    }

    @Test
    void playlistLoopDefensivelyCopiesEntriesAndAllowsDuplicates() {
        List<String> source = new ArrayList<>(List.of("first.ogg", "second.ogg", "first.ogg"));

        PlaylistLoopPlayback playback = new PlaylistLoopPlayback(source, 0.75f, 250, 900);
        source.clear();

        assertEquals(List.of("first.ogg", "second.ogg", "first.ogg"), playback.playlist());
        assertEquals(playback.playlist(), playback.musicIds());
        assertThrows(UnsupportedOperationException.class, () -> playback.playlist().clear());
    }

    @Test
    void playlistLoopRejectsEntryCountsOutsideBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(List.of(), 1.0f, 2000, 2000));
        assertThrows(IllegalArgumentException.class, () -> new PlaylistLoopPlayback(
                Collections.nCopies(PlaylistLoopPlayback.MAX_ENTRIES + 1, "track.ogg"),
                1.0f, 2000, 2000
        ));
        assertEquals(256, PlaylistLoopPlayback.MAX_ENTRIES);
    }

    @Test
    void playlistLoopRejectsNullAndBlankEntriesWithIndexes() {
        IllegalArgumentException nullEntry = assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(
                        Arrays.asList("first.ogg", null), 1.0f, 2000, 2000
                ));
        IllegalArgumentException blankEntry = assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(
                        List.of("first.ogg", " "), 1.0f, 2000, 2000
                ));

        assertEquals("playlist[1] must not be null", nullEntry.getMessage());
        assertEquals("playlist[1] must not be blank", blankEntry.getMessage());
    }

    @Test
    void playlistLoopAcceptsMusicIdAt1024CharacterLimit() {
        String musicId = "x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH);

        PlaylistLoopPlayback playback = new PlaylistLoopPlayback(
                List.of(musicId), 1.0f, 2000, 2000
        );

        assertEquals(musicId, playback.playlist().get(0));
    }

    @Test
    void playlistLoopRejectsOverlongMusicIdWithPlaylistIndex() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(
                        List.of(
                                "first.ogg",
                                "x".repeat(PlaybackDefinition.MAX_MUSIC_ID_LENGTH + 1)
                        ),
                        1.0f, 2000, 2000
                ));

        assertTrue(error.getMessage().contains("playlist[1]"));
    }

    @ParameterizedTest
    @ValueSource(floats = {-0.1f, 1.1f, Float.NaN, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY})
    void playlistLoopRejectsInvalidVolumes(float volume) {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(List.of("track.ogg"), volume, 2000, 2000));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 60001})
    void playlistLoopRejectsInvalidFadeIn(int fadeInMs) {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(List.of("track.ogg"), 1.0f, fadeInMs, 2000));
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 60001})
    void playlistLoopRejectsInvalidFadeOut(int fadeOutMs) {
        assertThrows(IllegalArgumentException.class,
                () -> new PlaylistLoopPlayback(List.of("track.ogg"), 1.0f, 2000, fadeOutMs));
    }

    private static AreaTrackDefinition track(String musicId) {
        return new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000);
    }
}
