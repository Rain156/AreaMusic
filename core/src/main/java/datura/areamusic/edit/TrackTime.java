package datura.areamusic.edit;

import datura.areamusic.area.AreaDefinition;

import java.util.Locale;
import java.util.OptionalInt;
import java.util.regex.Pattern;

/** Reads and writes a position in a track the way people type it: {@code 1:30}, {@code 90}. */
public final class TrackTime {
    private static final Pattern WHOLE = Pattern.compile("\\d{1,5}");
    private static final Pattern SECONDS = Pattern.compile("\\d{1,5}(\\.\\d{1,3})?");

    private TrackTime() {
    }

    /**
     * Accepts seconds ({@code 90}, {@code 90.5}), minutes and seconds ({@code 1:30}) or hours,
     * minutes and seconds ({@code 1:02:03}). A full-width colon works too, since that is what a
     * Chinese input method types.
     *
     * @return milliseconds, or empty when the text is not a time or is longer than an area allows
     */
    public static OptionalInt parseMillis(String text) {
        String[] parts = text.strip().replace('：', ':').split(":", -1);
        if (parts.length < 1 || parts.length > 3 || !SECONDS.matcher(parts[parts.length - 1]).matches()) {
            return OptionalInt.empty();
        }
        long wholeUnits = 0;
        for (int index = 0; index < parts.length - 1; index++) {
            if (!WHOLE.matcher(parts[index]).matches()) {
                return OptionalInt.empty();
            }
            int value = Integer.parseInt(parts[index]);
            if (index > 0 && value >= 60) {
                return OptionalInt.empty();
            }
            wholeUnits = wholeUnits * 60 + value;
        }
        String[] seconds = parts[parts.length - 1].split("\\.");
        int wholeSeconds = Integer.parseInt(seconds[0]);
        if (parts.length > 1 && wholeSeconds >= 60) {
            return OptionalInt.empty();
        }
        int fraction = seconds.length < 2 ? 0 : Integer.parseInt((seconds[1] + "00").substring(0, 3));
        long millis = (wholeUnits * 60 + wholeSeconds) * 1000 + fraction;
        return millis > AreaDefinition.MAX_START_OFFSET_MS
                ? OptionalInt.empty()
                : OptionalInt.of((int) millis);
    }

    /** {@code 0:00}, {@code 1:30}, {@code 1:30.5}, {@code 1:02:03}. */
    public static String format(int millis) {
        if (millis < 0) {
            throw new IllegalArgumentException("millis must not be negative");
        }
        int totalSeconds = millis / 1000;
        int hours = totalSeconds / 3600;
        int minutes = totalSeconds / 60 % 60;
        int seconds = totalSeconds % 60;
        String text = hours > 0
                ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(Locale.ROOT, "%d:%02d", minutes, seconds);
        int fraction = millis % 1000;
        if (fraction == 0) {
            return text;
        }
        String digits = String.format(Locale.ROOT, "%03d", fraction).replaceAll("0+$", "");
        return text + "." + digits;
    }
}
