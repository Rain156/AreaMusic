package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaIdsTest {
    @Test
    void derivesIdFromMusicFileName() {
        assertEquals("day-theme", AreaIds.generate("village/Day Theme.ogg", id -> false));
        assertEquals("boss_fight-2", AreaIds.generate("Boss_Fight-2.flac", id -> false));
        assertEquals("a-b", AreaIds.generate("a  !!  b.mp3", id -> false));
    }

    @Test
    void fallsBackWhenNothingUsableRemains() {
        assertEquals("area", AreaIds.generate("村庄.mp3", id -> false));
        assertEquals("area", AreaIds.generate(null, id -> false));
        assertEquals("area", AreaIds.generate("", id -> false));
        assertEquals("area", AreaIds.generate("---.ogg", id -> false));
        assertEquals("area", AreaIds.generate(".ogg", id -> false));
    }

    @Test
    void appendsCounterWhenTaken() {
        assertEquals("day-2", AreaIds.generate("day.ogg", Set.of("day")::contains));
        assertEquals("day-4", AreaIds.generate("day.ogg", Set.of("day", "day-2", "day-3")::contains));
        assertEquals("area-2", AreaIds.generate(null, Set.of("area")::contains));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "village/Day Theme.ogg", "_leading.ogg", "-x-.ogg", "UPPER.MP3", "a.b.c.wav",
            "trailing space .ogg", "🎵🎵.ogg", "x"
    })
    void resultAlwaysMatchesAreaIdPattern(String hint) {
        String longHint = hint + "-" + "y".repeat(200) + ".ogg";

        assertTrue(AreaDefinition.ID_PATTERN.matcher(AreaIds.generate(hint, id -> false)).matches());
        assertTrue(AreaDefinition.ID_PATTERN.matcher(AreaIds.generate(longHint, id -> false)).matches());
        assertTrue(AreaDefinition.ID_PATTERN.matcher(
                AreaIds.generate(longHint, id -> !id.endsWith("-12"))).matches());
    }
}
