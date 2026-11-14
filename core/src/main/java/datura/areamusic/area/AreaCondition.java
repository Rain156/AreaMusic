package datura.areamusic.area;

import java.util.Locale;
import java.util.Objects;

/**
 * When an area plays. Two areas in the same place with different conditions give a place one
 * tune by day and another at night, or a different one in the rain.
 */
public record AreaCondition(DayPart dayPart, Weather weather) {
    public static final AreaCondition ALWAYS = new AreaCondition(DayPart.ANY, Weather.ANY);

    public AreaCondition {
        Objects.requireNonNull(dayPart, "dayPart");
        Objects.requireNonNull(weather, "weather");
    }

    public boolean matches(WorldMoment moment) {
        boolean timeMatches = switch (dayPart) {
            case ANY -> true;
            case DAY -> moment.day();
            case NIGHT -> !moment.day();
        };
        boolean weatherMatches = switch (weather) {
            case ANY -> true;
            case CLEAR -> !moment.raining();
            // A thunderstorm is rain too, unless an area asks for thunder in particular.
            case RAIN -> moment.raining();
            case THUNDER -> moment.thundering();
        };
        return timeMatches && weatherMatches;
    }

    /**
     * How much the condition narrows things down. Where two areas would both play, the pickier
     * one wins: "at night in the rain" beats "in the rain", which beats "at night", which beats
     * "always". Weather counts for more than the time of day, since it is the rarer of the two,
     * and a thunderstorm for more than any rain.
     */
    public int specificity() {
        int weatherScore = switch (weather) {
            case ANY -> 0;
            case CLEAR, RAIN -> 2;
            case THUNDER -> 4;
        };
        return (dayPart == DayPart.ANY ? 0 : 1) + weatherScore;
    }

    public AreaCondition withDayPart(DayPart newDayPart) {
        return new AreaCondition(newDayPart, weather);
    }

    public AreaCondition withWeather(Weather newWeather) {
        return new AreaCondition(dayPart, newWeather);
    }

    public enum DayPart {
        ANY, DAY, NIGHT;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static DayPart fromId(String id) {
            for (DayPart value : values()) {
                if (value.id().equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown time of day: " + id);
        }
    }

    public enum Weather {
        ANY, CLEAR, RAIN, THUNDER;

        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static Weather fromId(String id) {
            for (Weather value : values()) {
                if (value.id().equals(id)) {
                    return value;
                }
            }
            throw new IllegalArgumentException("Unknown weather: " + id);
        }
    }
}
