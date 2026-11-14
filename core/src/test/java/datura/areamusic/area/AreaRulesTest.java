package datura.areamusic.area;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import datura.areamusic.area.AreaCondition.DayPart;
import datura.areamusic.area.AreaCondition.Weather;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Where and when an area plays: several boxes, a whole dimension, time of day and weather. */
class AreaRulesTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final WorldMoment DAY = WorldMoment.at(6_000, false, false);
    private static final WorldMoment NIGHT = WorldMoment.at(18_000, false, false);
    private static final WorldMoment RAINY_DAY = WorldMoment.at(6_000, true, false);
    private static final WorldMoment STORMY_NIGHT = WorldMoment.at(18_000, true, true);

    private final AreaJsonCodec codec = new AreaJsonCodec();

    @Test
    void timeOfDayFollowsTheGameClockAcrossDays() {
        assertTrue(WorldMoment.at(0, false, false).day());
        assertTrue(WorldMoment.at(12_999, false, false).day());
        assertFalse(WorldMoment.at(13_000, false, false).day());
        assertFalse(WorldMoment.at(23_999, false, false).day());
        assertTrue(WorldMoment.at(24_000L * 37 + 5, false, false).day());
        assertFalse(WorldMoment.at(24_000L * 37 + 20_000, false, false).day());
    }

    @Test
    void conditionsMatchTheSkyAndRainIncludesThunderstorms() {
        assertTrue(AreaCondition.ALWAYS.matches(STORMY_NIGHT));
        assertTrue(new AreaCondition(DayPart.DAY, Weather.ANY).matches(RAINY_DAY));
        assertFalse(new AreaCondition(DayPart.DAY, Weather.ANY).matches(NIGHT));
        assertTrue(new AreaCondition(DayPart.NIGHT, Weather.ANY).matches(NIGHT));
        assertTrue(new AreaCondition(DayPart.ANY, Weather.CLEAR).matches(NIGHT));
        assertFalse(new AreaCondition(DayPart.ANY, Weather.CLEAR).matches(RAINY_DAY));
        assertTrue(new AreaCondition(DayPart.ANY, Weather.RAIN).matches(RAINY_DAY));
        assertTrue(new AreaCondition(DayPart.ANY, Weather.RAIN).matches(STORMY_NIGHT));
        assertFalse(new AreaCondition(DayPart.ANY, Weather.THUNDER).matches(RAINY_DAY));
        assertTrue(new AreaCondition(DayPart.NIGHT, Weather.THUNDER).matches(STORMY_NIGHT));
    }

    @Test
    void onePlaceCanPlayByDayByNightAndInTheRain() {
        AreaDefinition always = area("village", "day.ogg");
        AreaDefinition night = area("village-night", "night.ogg")
                .withCondition(new AreaCondition(DayPart.NIGHT, Weather.ANY));
        AreaDefinition rain = area("village-rain", "rain.ogg")
                .withCondition(new AreaCondition(DayPart.ANY, Weather.RAIN));
        AreaDefinition storm = area("village-storm", "storm.ogg")
                .withCondition(new AreaCondition(DayPart.NIGHT, Weather.THUNDER));
        List<AreaDefinition> areas = List.of(always, night, rain, storm);
        AreaPosition inside = new AreaPosition(5, 70, 5);

        assertEquals("village", id(AreaResolver.resolve(areas, OVERWORLD, inside, DAY)));
        assertEquals("village-night", id(AreaResolver.resolve(areas, OVERWORLD, inside, NIGHT)));
        assertEquals("village-rain", id(AreaResolver.resolve(areas, OVERWORLD, inside, RAINY_DAY)));
        // Night, rain and storm all apply; the pickiest one plays.
        assertEquals("village-storm", id(AreaResolver.resolve(areas, OVERWORLD, inside, STORMY_NIGHT)));
        // On a rainy night without a storm, the weather outranks the time of day.
        assertEquals("village-rain", id(AreaResolver.resolve(
                List.of(always, night, rain), OVERWORLD, inside, WorldMoment.at(18_000, true, false))));
        AreaDefinition rainyNight = area("village-rainy-night", "drizzle.ogg")
                .withCondition(new AreaCondition(DayPart.NIGHT, Weather.RAIN));
        assertEquals("village-rainy-night", id(AreaResolver.resolve(
                List.of(always, night, rain, rainyNight), OVERWORLD, inside, WorldMoment.at(18_000, true, false))));
        // Priority still comes first.
        assertEquals("village", id(AreaResolver.resolve(
                List.of(always.withPriority(5), night, storm), OVERWORLD, inside, STORMY_NIGHT)));
        // An area that only plays at night leaves the day silent.
        assertEquals(Optional.empty(), AreaResolver.resolve(List.of(night), OVERWORLD, inside, DAY));
    }

    @Test
    void anAreaOfSeveralBoxesIsOnePlace() {
        AreaDefinition hall = area("hall", "day.ogg").withExtraBoxes(List.of(
                new AreaBox(new AreaPosition(11, 60, 0), new AreaPosition(30, 80, 4))));

        assertTrue(hall.contains(OVERWORLD, new AreaPosition(5, 70, 5)));
        assertTrue(hall.contains(OVERWORLD, new AreaPosition(25, 70, 2)), "in the corridor");
        assertFalse(hall.contains(OVERWORLD, new AreaPosition(25, 70, 8)), "beside the corridor");
        assertFalse(hall.contains("minecraft:the_nether", new AreaPosition(5, 70, 5)));
        assertEquals(2, hall.boxes().size());
        assertEquals(11L * 21 * 11 + 20L * 21 * 5, hall.volumeInBlocks());

        List<AreaBox> tooMany = new ArrayList<>();
        for (int index = 0; index <= AreaDefinition.MAX_EXTRA_BOXES; index++) {
            tooMany.add(new AreaBox(new AreaPosition(index, 0, 0), new AreaPosition(index, 1, 1)));
        }
        assertThrows(IllegalArgumentException.class, () -> hall.withExtraBoxes(tooMany));
    }

    @Test
    void wholeDimensionMusicPlaysEverywhereThereAndGivesWayToAnyOrdinaryArea() {
        AreaDefinition backdrop = area("nether-backdrop", "drone.ogg").withWholeDimension(true);
        AreaDefinition village = area("village", "day.ogg");
        AreaPosition farAway = new AreaPosition(90_000, -40, -90_000);

        assertTrue(backdrop.contains(OVERWORLD, farAway));
        assertFalse(backdrop.contains("minecraft:the_end", farAway));
        assertEquals(List.of(), backdrop.boxes());
        assertEquals(Long.MAX_VALUE, backdrop.volumeInBlocks());
        assertEquals("nether-backdrop",
                id(AreaResolver.resolve(List.of(backdrop, village), OVERWORLD, farAway, DAY)));
        assertEquals("village", id(AreaResolver.resolve(
                List.of(backdrop, village), OVERWORLD, new AreaPosition(5, 70, 5), DAY)));
        // Covering the dimension drops the boxes, which no longer mean anything.
        AreaDefinition converted = village
                .withExtraBoxes(List.of(new AreaBox(new AreaPosition(0, 0, 0), new AreaPosition(1, 1, 1))))
                .withWholeDimension(true);
        assertEquals(List.of(), converted.extraBoxes());
    }

    @Test
    void theNewSettingsAreWrittenOnlyWhenSetAndReadBack() {
        AreaDefinition plain = area("village", "day.ogg");
        AreaDefinition tuned = AreaEdits.withGap(AreaEdits.withShuffle(
                        AreaEdits.addMusic(plain, "night.ogg"), true), 4_000)
                .withCondition(new AreaCondition(DayPart.NIGHT, Weather.RAIN))
                .withAnnounce(false)
                .withExtraBoxes(List.of(
                        new AreaBox(new AreaPosition(11, 60, 0), new AreaPosition(30, 80, 4))));
        AreaDefinition backdrop = plain.withWholeDimension(true);

        JsonObject plainJson = JsonParser.parseString(codec.write(plain)).getAsJsonObject();
        for (String field : List.of("time", "weather", "wholeDimension", "announce", "extraBoxes")) {
            assertFalse(plainJson.has(field), field);
        }
        JsonObject tunedJson = JsonParser.parseString(codec.write(tuned)).getAsJsonObject();
        assertEquals("night", tunedJson.get("time").getAsString());
        assertEquals("rain", tunedJson.get("weather").getAsString());
        assertFalse(tunedJson.get("announce").getAsBoolean());
        assertTrue(tunedJson.get("shuffle").getAsBoolean());
        assertEquals(4_000, tunedJson.get("gapMs").getAsInt());
        assertEquals(1, tunedJson.getAsJsonArray("extraBoxes").size());

        assertEquals(plain, codec.read("village", new StringReader(codec.write(plain))));
        assertEquals(tuned, codec.read("village", new StringReader(codec.write(tuned))));
        assertEquals(backdrop, codec.read("village", new StringReader(codec.write(backdrop))));

        String badTime = codec.write(tuned).replace("\"night\"", "\"dusk\"");
        assertThrows(JsonParseException.class, () -> codec.read("village", new StringReader(badTime)));
    }

    @Test
    void aNamedAreaAnnouncesItselfUnlessToldNotTo() {
        AreaDefinition unnamed = area("village", "day.ogg");
        AreaDefinition named = unnamed.withDisplayName("South Gate");

        assertEquals("", PlaybackState.fromArea(unnamed).title());
        assertEquals("South Gate", PlaybackState.fromArea(named).title());
        assertEquals("", PlaybackState.fromArea(named.withAnnounce(false)).title());
        assertEquals(
                PlaybackState.fromArea(unnamed), PlaybackState.fromArea(named).withTitle(""),
                "the name is not part of what is played");
    }

    @Test
    void editsKeepEverySettingTheyDoNotTouch() {
        AreaDefinition tuned = AreaEdits.withGap(AreaEdits.withShuffle(
                        AreaEdits.addMusic(area("village", "day.ogg"), "night.ogg"), true), 4_000)
                .withCondition(new AreaCondition(DayPart.DAY, Weather.CLEAR))
                .withAnnounce(false)
                .withStartOffsetMs(1_500);

        AreaDefinition edited = AreaEdits.removeMusic(
                AreaEdits.addMusic(AreaEdits.withFadeIn(AreaEdits.withVolume(tuned, 0.4f), 100), "rain.ogg"),
                "night.ogg").withDisplayName("Square").withPriority(3);

        assertTrue(AreaEdits.shuffleOf(edited));
        assertEquals(4_000, AreaEdits.gapOf(edited));
        assertEquals(new AreaCondition(DayPart.DAY, Weather.CLEAR), edited.condition());
        assertFalse(edited.announce());
        assertEquals(1_500, edited.startOffsetMs());
        assertEquals(List.of("day.ogg", "rain.ogg"), edited.musicIds());
        assertThrows(IllegalArgumentException.class,
                () -> AreaEdits.withShuffle(area("village", "day.ogg"), true));
    }

    @Test
    void boxesAreAddedAndRemovedByTheirNumber() {
        AreaDefinition one = area("hall", "day.ogg");
        AreaPosition a = new AreaPosition(11, 60, 0);
        AreaPosition b = new AreaPosition(30, 80, 4);
        AreaDefinition two = AreaEdits.addBox(one, a, b);

        assertEquals(List.of(new AreaBox(a, b)), two.extraBoxes());
        assertEquals(one, AreaEdits.removeBox(two, 2));
        AreaDefinition corridorOnly = AreaEdits.removeBox(two, 1);
        assertEquals(a, corridorOnly.pos1());
        assertEquals(List.of(), corridorOnly.extraBoxes());
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.removeBox(one, 1));
        assertThrows(IllegalArgumentException.class, () -> AreaEdits.removeBox(two, 3));
        assertThrows(IllegalArgumentException.class,
                () -> AreaEdits.addBox(one.withWholeDimension(true), a, b));
    }

    private static String id(Optional<AreaDefinition> area) {
        return area.map(AreaDefinition::id).orElse("(none)");
    }

    private static AreaDefinition area(String id, String musicId) {
        return AreaEdits.single(
                id, OVERWORLD, new AreaPosition(0, 60, 0), new AreaPosition(10, 80, 10), musicId);
    }
}
