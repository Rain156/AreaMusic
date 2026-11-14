package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;
import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TrackTimeTest {
    @Test
    void readsSecondsMinutesAndHours() {
        assertEquals(OptionalInt.of(0), TrackTime.parseMillis("0"));
        assertEquals(OptionalInt.of(90_000), TrackTime.parseMillis("90"));
        assertEquals(OptionalInt.of(90_500), TrackTime.parseMillis(" 90.5 "));
        assertEquals(OptionalInt.of(90_000), TrackTime.parseMillis("1:30"));
        assertEquals(OptionalInt.of(90_250), TrackTime.parseMillis("1:30.25"));
        assertEquals(OptionalInt.of(3_723_000), TrackTime.parseMillis("1:02:03"));
        assertEquals(OptionalInt.of(5_400_000), TrackTime.parseMillis("90:00"));
        assertEquals(OptionalInt.of(90_000), TrackTime.parseMillis("1：30"), "full-width colon");
    }

    @Test
    void rejectsWhatIsNotATimeOrIsTooLong() {
        for (String text : new String[]{
                "", ":", "1:", ":30", "abc", "-5", "1:60", "1:75:00", "1:2:3:4", "1.5:30", "90.1234", "1 30"
        }) {
            assertEquals(OptionalInt.empty(), TrackTime.parseMillis(text), text);
        }
        assertEquals(
                OptionalInt.of(AreaDefinition.MAX_START_OFFSET_MS), TrackTime.parseMillis("6:00:00"));
        assertEquals(OptionalInt.empty(), TrackTime.parseMillis("6:00:00.001"));
        assertEquals(OptionalInt.empty(), TrackTime.parseMillis("99999"));
    }

    @Test
    void writesTheShortestFormThatReadsBackTheSame() {
        assertEquals("0:00", TrackTime.format(0));
        assertEquals("0:05", TrackTime.format(5_000));
        assertEquals("1:30", TrackTime.format(90_000));
        assertEquals("1:30.5", TrackTime.format(90_500));
        assertEquals("0:00.025", TrackTime.format(25));
        assertEquals("1:02:03", TrackTime.format(3_723_000));
        for (int millis : new int[]{0, 1, 999, 1_000, 59_999, 60_000, 3_599_999, 3_600_000, 21_600_000}) {
            assertEquals(OptionalInt.of(millis), TrackTime.parseMillis(TrackTime.format(millis)));
        }
        assertThrows(IllegalArgumentException.class, () -> TrackTime.format(-1));
    }
}
