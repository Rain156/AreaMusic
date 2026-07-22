package datura.areamusic.area;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaTrackDefinitionTest {
    @Test
    void convertsDelaySecondsToExactSampleFrames() {
        AreaTrackDefinition track = new AreaTrackDefinition("track.ogg", 5, 1.0f, true, 2000, 2000);

        assertEquals(220_500L, track.delayFrames(44_100));
    }

    @Test
    void usesLongArithmeticForMaximumDelayAndSampleRate() {
        AreaTrackDefinition track = new AreaTrackDefinition(
                "track.ogg", Integer.MAX_VALUE, 1.0f, true, 2000, 2000);

        assertEquals((long) Integer.MAX_VALUE * Integer.MAX_VALUE, track.delayFrames(Integer.MAX_VALUE));
    }

    @Test
    void rejectsNonPositiveSampleRates() {
        AreaTrackDefinition track = validTrack();

        assertThrows(IllegalArgumentException.class, () -> track.delayFrames(0));
        assertThrows(IllegalArgumentException.class, () -> track.delayFrames(-1));
    }

    @Test
    void rejectsNullOrBlankMusicIds() {
        assertThrows(NullPointerException.class,
                () -> new AreaTrackDefinition(null, 0, 1.0f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition(" ", 0, 1.0f, true, 2000, 2000));
    }

    @Test
    void rejectsNegativeDelaySeconds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", -1, 1.0f, true, 2000, 2000));
    }

    @Test
    void rejectsNonFiniteOrOutOfRangeVolumes() {
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, Float.NaN, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, Float.POSITIVE_INFINITY, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, -0.01f, true, 2000, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, 1.01f, true, 2000, 2000));
    }

    @Test
    void rejectsFadesOutsideInclusiveBounds() {
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, 1.0f, true, -1, 2000));
        assertThrows(IllegalArgumentException.class,
                () -> new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 60_001));

        assertEquals(60_000,
                new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 60_000, 60_000).fadeOutMs());
    }

    private static AreaTrackDefinition validTrack() {
        return new AreaTrackDefinition("track.ogg", 0, 1.0f, true, 2000, 2000);
    }
}
