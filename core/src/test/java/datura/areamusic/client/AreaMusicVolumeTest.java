package datura.areamusic.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaMusicVolumeTest {
    @Test
    void combinesOnlyMasterAndAreaMusicVolume() {
        assertEquals(0.4f, AreaMusicVolume.effectiveGain(0.5f, 0.8), 0.0001f);
        assertEquals(0.0f, AreaMusicVolume.effectiveGain(0.0f, 1.0), 0.0001f);
        assertEquals(0.0f, AreaMusicVolume.effectiveGain(1.0f, 0.0), 0.0001f);
    }

    @Test
    void boundsInputsBeforePassingGainToTheMixer() {
        assertEquals(0.0f, AreaMusicVolume.effectiveGain(-1.0f, 1.0), 0.0001f);
        assertEquals(1.0f, AreaMusicVolume.effectiveGain(2.0f, 2.0), 0.0001f);
        assertThrows(IllegalArgumentException.class,
                () -> AreaMusicVolume.effectiveGain(Float.NaN, 1.0));
        assertThrows(IllegalArgumentException.class,
                () -> AreaMusicVolume.effectiveGain(1.0f, Double.POSITIVE_INFINITY));
    }
}
