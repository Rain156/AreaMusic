package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.edit.AreaEdits.SimpleMode;
import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaylistLoopPlayback;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaEditsTest {
    private static final AreaPosition A = new AreaPosition(0, 0, 0);
    private static final AreaPosition B = new AreaPosition(4, 4, 4);

    @Test
    void singleCreatesOneLoopingTrackWithDefaults() {
        AreaDefinition area = AreaEdits.single("village", "minecraft:overworld", A, B, "day.ogg");

        assertEquals(
                List.of(new AreaTrackDefinition("day.ogg", 0, 1.0f, true, 2000, 2000)),
                area.tracks()
        );
        assertEquals(SimpleMode.LOOP, AreaEdits.modeOf(area));
        assertEquals(0, area.priority());
        assertTrue(area.enabled());
    }

    @Test
    void modeOfDistinguishesTheThreeModes() {
        assertEquals(SimpleMode.LOOP, AreaEdits.modeOf(loop()));
        assertEquals(SimpleMode.PLAYLIST, AreaEdits.modeOf(playlist("a.ogg")));
        assertEquals(SimpleMode.LAYERS, AreaEdits.modeOf(layers()));
        assertEquals(SimpleMode.LAYERS, SimpleMode.fromId("layers"));
        assertThrows(IllegalArgumentException.class, () -> SimpleMode.fromId("LAYERS"));
    }

    @Test
    void volumeAndFadesApplyToPlaylistOrEveryTrack() {
        AreaDefinition layered = AreaEdits.withFadeOut(
                AreaEdits.withFadeIn(AreaEdits.withVolume(layers(), 0.25f), 100), 300);
        AreaDefinition listed = AreaEdits.withFadeOut(
                AreaEdits.withFadeIn(AreaEdits.withVolume(playlist("a.ogg", "b.ogg"), 0.25f), 100), 300);

        for (AreaTrackDefinition track : layered.tracks()) {
            assertEquals(0.25f, track.volume());
            assertEquals(100, track.fadeInMs());
            assertEquals(300, track.fadeOutMs());
        }
        assertEquals(7, layered.tracks().get(1).delaySeconds());
        assertEquals(false, layered.tracks().get(1).loop());
        assertEquals(new PlaylistLoopPlayback(List.of("a.ogg", "b.ogg"), 0.25f, 100, 300), listed.playback());
        assertEquals(0.25f, AreaEdits.volumeOf(layered));
        assertEquals(100, AreaEdits.fadeInOf(listed));
        assertEquals(300, AreaEdits.fadeOutOf(layered));
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.withVolume(loop(), 1.5f));
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.withFadeIn(loop(), 60_001));
    }

    @Test
    void modeConversionKeepsOrderVolumeAndFades() {
        AreaDefinition listed = AreaEdits.withMode(layers(), SimpleMode.PLAYLIST);
        assertEquals(new PlaylistLoopPlayback(List.of("a.ogg", "b.ogg"), 0.5f, 10, 20), listed.playback());

        AreaDefinition layeredAgain = AreaEdits.withMode(listed, SimpleMode.LAYERS);
        assertEquals(List.of(
                new AreaTrackDefinition("a.ogg", 0, 0.5f, true, 10, 20),
                new AreaTrackDefinition("b.ogg", 0, 0.5f, true, 10, 20)
        ), layeredAgain.tracks());

        AreaDefinition looped = AreaEdits.withMode(listed, SimpleMode.LOOP);
        assertEquals(List.of(new AreaTrackDefinition("a.ogg", 0, 0.5f, true, 10, 20)), looped.tracks());
    }

    @Test
    void convertingToTheCurrentModeReturnsTheSameArea() {
        AreaDefinition loop = loop();

        assertSame(loop, AreaEdits.withMode(loop, SimpleMode.LOOP));
        assertSame(loop, AreaEdits.withMode(loop, SimpleMode.LAYERS));
        AreaDefinition layers = layers();
        assertSame(layers, AreaEdits.withMode(layers, SimpleMode.LAYERS));
    }

    @Test
    void playlistLongerThanSixteenCannotBecomeLayers() {
        AreaDefinition seventeen = playlist(Collections.nCopies(17, "a.ogg").toArray(String[]::new));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> AreaEdits.withMode(seventeen, SimpleMode.LAYERS));
        assertTrue(error.getMessage().contains("16"));
    }

    @Test
    void addMusicAppendsLayersOrStartsAPlaylist() {
        assertEquals(
                new PlaylistLoopPlayback(List.of("a.ogg", "b.ogg"), 0.5f, 10, 20),
                AreaEdits.addMusic(loop(), "b.ogg").playback()
        );
        assertEquals(
                List.of("a.ogg", "b.ogg", "a.ogg"),
                AreaEdits.addMusic(playlist("a.ogg", "b.ogg"), "a.ogg").musicIds()
        );
        AreaDefinition threeLayers = AreaEdits.addMusic(layers(), "c.ogg");
        assertEquals(SimpleMode.LAYERS, AreaEdits.modeOf(threeLayers));
        assertEquals(new AreaTrackDefinition("c.ogg", 0, 0.5f, true, 10, 20), threeLayers.tracks().get(2));
        assertEquals(layers().tracks(), threeLayers.tracks().subList(0, 2));
    }

    @Test
    void addMusicRespectsLimits() {
        AreaDefinition sixteenLayers = loop().withPlayback(new ParallelPlayback(
                Collections.nCopies(16, new AreaTrackDefinition("a.ogg", 0, 1.0f, true, 0, 0))));
        AreaDefinition fullPlaylist = playlist(Collections.nCopies(256, "a.ogg").toArray(String[]::new));

        assertThrows(IllegalArgumentException.class, () -> AreaEdits.addMusic(sixteenLayers, "b.ogg"));
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.addMusic(fullPlaylist, "b.ogg"));
    }

    @Test
    void removeMusicDropsLastOccurrenceAndNeverEmptiesAnArea() {
        assertEquals(
                List.of("a.ogg", "b.ogg"),
                AreaEdits.removeMusic(playlist("a.ogg", "b.ogg", "a.ogg"), "a.ogg").musicIds()
        );
        assertEquals(List.of("b.ogg"), AreaEdits.removeMusic(layers(), "a.ogg").musicIds());
        assertEquals(SimpleMode.PLAYLIST,
                AreaEdits.modeOf(AreaEdits.removeMusic(playlist("a.ogg", "b.ogg"), "b.ogg")));
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.removeMusic(loop(), "a.ogg"));
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.removeMusic(layers(), "zzz.ogg"));
    }

    @Test
    void withOnlyMusicReplacesEverythingButKeepsVolumeAndFades() {
        AreaDefinition replaced = AreaEdits.withOnlyMusic(playlist("a.ogg", "b.ogg"), "c.ogg");

        assertEquals(List.of(new AreaTrackDefinition("c.ogg", 0, 0.5f, true, 10, 20)), replaced.tracks());
        assertEquals(SimpleMode.LOOP, AreaEdits.modeOf(replaced));
    }

    private static AreaDefinition loop() {
        return AreaDefinition.create(
                "village", "minecraft:overworld", A, B,
                List.of(new AreaTrackDefinition("a.ogg", 0, 0.5f, true, 10, 20)), true, 3
        ).withDisplayName("村庄");
    }

    private static AreaDefinition layers() {
        return loop().withPlayback(new ParallelPlayback(List.of(
                new AreaTrackDefinition("a.ogg", 0, 0.5f, true, 10, 20),
                new AreaTrackDefinition("b.ogg", 7, 0.9f, false, 30, 40)
        )));
    }

    private static AreaDefinition playlist(String... musicIds) {
        return loop().withPlayback(new PlaylistLoopPlayback(List.of(musicIds), 0.5f, 10, 20));
    }
}
