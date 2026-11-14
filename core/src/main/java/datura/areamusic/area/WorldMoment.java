package datura.areamusic.area;

/** What the sky is doing where a player stands, as far as areas care. */
public record WorldMoment(boolean day, boolean raining, boolean thundering) {
    /** A sunny day: what conditions are checked against when nothing better is known. */
    public static final WorldMoment CLEAR_DAY = new WorldMoment(true, false, false);
    private static final long TICKS_PER_DAY = 24_000L;
    /** The tick of the day at which beds become usable. */
    private static final long NIGHTFALL = 13_000L;

    /**
     * @param dayTime the world's time of day in ticks, as the game counts it (0 is sunrise)
     */
    public static WorldMoment at(long dayTime, boolean raining, boolean thundering) {
        return new WorldMoment(Math.floorMod(dayTime, TICKS_PER_DAY) < NIGHTFALL, raining, thundering);
    }
}
