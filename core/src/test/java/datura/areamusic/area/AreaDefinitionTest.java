package datura.areamusic.area;

import datura.areamusic.playback.ParallelPlayback;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaylistLoopPlayback;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaDefinitionTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";
    private static final AreaPosition ZERO = new AreaPosition(0, 0, 0);

    @Test
    void preservesCrossedEndpointsAndDerivesInclusiveBounds() {
        AreaPosition pos1 = new AreaPosition(10, 60, 0);
        AreaPosition pos2 = new AreaPosition(0, 80, 10);
        AreaDefinition area = area("square", pos1, pos2);

        assertEquals(pos1, area.pos1());
        assertEquals(pos2, area.pos2());
        assertEquals(new AreaPosition(0, 60, 0), area.min());
        assertEquals(new AreaPosition(10, 80, 10), area.max());
        assertTrue(area.contains(OVERWORLD, new AreaPosition(0, 60, 0)));
        assertTrue(area.contains(OVERWORLD, new AreaPosition(10, 80, 10)));
        assertFalse(area.contains(OVERWORLD, new AreaPosition(11, 80, 10)));
        assertFalse(area.contains(NETHER, new AreaPosition(5, 70, 5)));
    }

    @Test
    void normalizesUnqualifiedDimensionAndPreservesQualifiedDimension() {
        AreaDefinition unqualified = AreaDefinition.create(
                "plain", "overworld", ZERO, ZERO, List.of(track("track.ogg")), false, 0);
        AreaDefinition explicitDefaultNamespace = AreaDefinition.create(
                "defaulted", ":overworld", ZERO, ZERO, List.of(track("track.ogg")), false, 0);
        AreaDefinition qualified = AreaDefinition.create(
                "qualified", "example.mod:world/path-name_1", ZERO, ZERO,
                List.of(track("track.ogg")), false, 0);

        assertEquals("minecraft:overworld", unqualified.dimension());
        assertEquals("minecraft:overworld", explicitDefaultNamespace.dimension());
        assertEquals("example.mod:world/path-name_1", qualified.dimension());
    }

    @Test
    void normalizesCandidateDimensionsDuringContainment() {
        AreaDefinition area = area("square", ZERO, ZERO);

        assertTrue(area.contains("overworld", ZERO));
        assertTrue(area.contains(":overworld", ZERO));
    }

    @Test
    void canonicalContainmentPathUsesPrevalidatedDimensionAndShortCircuitsMismatch() {
        AreaDefinition area = area("square", ZERO, ZERO);
        String canonicalDimension = AreaDefinition.canonicalizeDimension("overworld");

        assertEquals("minecraft:overworld", canonicalDimension);
        assertTrue(area.containsCanonical(canonicalDimension, ZERO));
        assertFalse(area.containsCanonical("minecraft:the_nether", null));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "", " ", "Minecraft:overworld", "minecraft:Bad", "minecraft:bad path",
            "minecraft:", "minecraft:bad?path", "a:b:c"
    })
    void rejectsInvalidDimensions(String dimension) {
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", dimension, ZERO, ZERO, List.of(track("track.ogg")), false, 0));
    }

    @Test
    void calculatesInclusiveBlockVolume() {
        AreaDefinition area = area("cube", ZERO, new AreaPosition(2, 2, 2));

        assertEquals(27L, area.volumeInBlocks());
    }

    @Test
    void saturatesBlockVolumeAtLongMaxValue() {
        AreaDefinition area = area(
                "world",
                new AreaPosition(Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE),
                new AreaPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE)
        );

        assertEquals(Long.MAX_VALUE, area.volumeInBlocks());
    }

    @Test
    void defensivelyCopiesTracks() {
        List<AreaTrackDefinition> source = new ArrayList<>(List.of(track("first.ogg")));
        AreaDefinition area = AreaDefinition.create(
                "square", OVERWORLD, ZERO, ZERO, source, false, 0);

        source.add(track("second.ogg"));

        assertEquals(List.of(track("first.ogg")), area.tracks());
        assertThrows(UnsupportedOperationException.class, () -> area.tracks().add(track("third.ogg")));
    }

    @Test
    void parallelAreaExposesPlaybackDefinitionAndOrderedMusicIds() {
        AreaDefinition area = AreaDefinition.create(
                "square", OVERWORLD, ZERO, ZERO,
                List.of(track("first.ogg"), track("second.ogg")), false, 0);

        assertEquals(PlaybackMode.PARALLEL, area.playback().mode());
        assertTrue(area.playback() instanceof ParallelPlayback);
        assertEquals(List.of("first.ogg", "second.ogg"), area.musicIds());
    }

    @Test
    void playlistAreaDefensivelyCopiesPlaylistAndExposesEmptyTracks() {
        List<String> source = new ArrayList<>(List.of("first.ogg", "second.ogg"));
        AreaDefinition area = AreaDefinition.createPlaylistLoop(
                "playlist", OVERWORLD, ZERO, ZERO,
                source, 0.75f, 250, 900, true, 4);
        source.clear();

        PlaylistLoopPlayback playback = (PlaylistLoopPlayback) area.playback();
        assertEquals(PlaybackMode.PLAYLIST_LOOP, playback.mode());
        assertEquals(List.of("first.ogg", "second.ogg"), playback.playlist());
        assertEquals(playback.playlist(), area.musicIds());
        assertEquals(List.of(), area.tracks());
        assertThrows(UnsupportedOperationException.class, () -> area.musicIds().clear());
    }

    @Test
    void retainsResumeOnReenter() {
        AreaDefinition area = AreaDefinition.create(
                "square", OVERWORLD, ZERO, ZERO, List.of(track("track.ogg")), true, 7);

        assertTrue(area.resumeOnReenter());
        assertEquals(7, area.priority());
    }

    @Test
    void rejectsTrackCountsOutsideInclusiveBounds() {
        List<AreaTrackDefinition> tooMany = IntStream.range(0, 17)
                .mapToObj(index -> track("track-" + index + ".ogg"))
                .toList();

        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, ZERO, ZERO, List.of(), false, 0));
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, ZERO, ZERO, tooMany, false, 0));
        assertEquals(16, AreaDefinition.MAX_TRACKS);
    }

    @Test
    void rejectsInvalidAreaIds() {
        assertThrows(IllegalArgumentException.class, () -> AreaDefinition.create(
                "Bad ID", OVERWORLD, ZERO, ZERO, List.of(track("track.ogg")), false, 0));
    }

    @Test
    void requiresDimensionEndpointsAndTracks() {
        List<AreaTrackDefinition> tracks = List.of(track("track.ogg"));

        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", null, ZERO, ZERO, tracks, false, 0));
        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, null, ZERO, tracks, false, 0));
        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, ZERO, null, tracks, false, 0));
        assertThrows(NullPointerException.class, () -> AreaDefinition.create(
                "valid", OVERWORLD, ZERO, ZERO, null, false, 0));
    }

    @Test
    void sevenArgumentConstructorUsesEditorDefaults() {
        AreaDefinition area = area("square", ZERO, ZERO);

        assertEquals("", area.displayName());
        assertEquals(AreaDefinition.AUTO_COLOR, area.color());
        assertTrue(area.enabled());
        assertEquals("square", area.label());
    }

    @Test
    void displayNameAcceptsCjkAndEmojiAndIsStripped() {
        AreaDefinition area = area("square", ZERO, ZERO).withDisplayName("  村庄🎵 ");

        assertEquals("村庄🎵", area.displayName());
        assertEquals("村庄🎵", area.label());
    }

    @Test
    void displayNameLongerThan64CodePointsIsRejected() {
        AreaDefinition area = area("square", ZERO, ZERO);

        assertEquals(64, area.withDisplayName("🎵".repeat(64)).displayName()
                .codePointCount(0, "🎵".repeat(64).length()));
        assertThrows(IllegalArgumentException.class, () -> area.withDisplayName("🎵".repeat(65)));
    }

    @Test
    void displayNameWithControlCharacterIsRejected() {
        AreaDefinition area = area("square", ZERO, ZERO);

        assertThrows(IllegalArgumentException.class, () -> area.withDisplayName("a\nb"));
        assertThrows(NullPointerException.class, () -> area.withDisplayName(null));
    }

    @Test
    void colorOutsideRgbRangeIsRejected() {
        AreaDefinition area = area("square", ZERO, ZERO);

        assertEquals(0xFFFFFF, area.withColor(0xFFFFFF).color());
        assertEquals(0, area.withColor(0).color());
        assertEquals(AreaDefinition.AUTO_COLOR, area.withColor(AreaDefinition.AUTO_COLOR).color());
        assertThrows(IllegalArgumentException.class, () -> area.withColor(0x1000000));
        assertThrows(IllegalArgumentException.class, () -> area.withColor(-2));
    }

    @Test
    void effectiveColorFallsBackToPaletteDeterministically() {
        AreaDefinition area = area("square", ZERO, ZERO);

        assertEquals(area.effectiveColor(), area("square", ZERO, ZERO).effectiveColor());
        assertTrue(AreaPalette.colors().contains(area.effectiveColor()));
        assertEquals(8, AreaPalette.colors().size());
        assertEquals(0x123456, area.withColor(0x123456).effectiveColor());
    }

    @Test
    void withersReplaceOnlyTheirField() {
        AreaDefinition area = area("square", ZERO, ZERO);
        AreaPosition corner = new AreaPosition(3, 4, 5);
        PlaylistLoopPlayback playlist = new PlaylistLoopPlayback(List.of("a.ogg"), 0.5f, 10, 20);

        assertEquals(area, area.withBounds(ZERO, ZERO));
        assertEquals(corner, area.withBounds(ZERO, corner).pos2());
        assertEquals(ZERO, area.withBounds(ZERO, corner).pos1());
        assertEquals(playlist, area.withPlayback(playlist).playback());
        assertFalse(area.withEnabled(false).enabled());
        assertEquals(7, area.withPriority(7).priority());
        assertTrue(area.withResumeOnReenter(true).resumeOnReenter());
        assertEquals(
                area.withDisplayName("x").withColor(1).withEnabled(false).withPriority(2)
                        .withResumeOnReenter(true).withBounds(corner, ZERO).withPlayback(playlist),
                new AreaDefinition(
                        "square", OVERWORLD, corner, ZERO, playlist, true, 2, "x", 1, false)
        );
    }

    private static AreaDefinition area(String id, AreaPosition pos1, AreaPosition pos2) {
        return AreaDefinition.create(
                id, OVERWORLD, pos1, pos2, List.of(track("music/theme.ogg")), false, 0);
    }

    private static AreaTrackDefinition track(String musicId) {
        return new AreaTrackDefinition(musicId, 0, 1.0f, true, 2000, 2000);
    }
}
