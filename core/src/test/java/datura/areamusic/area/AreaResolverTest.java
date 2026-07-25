package datura.areamusic.area;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaResolverTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final AreaPosition ZERO = new AreaPosition(0, 0, 0);
    private static final AreaPosition PLAYER = new AreaPosition(5, 65, 5);

    @Test
    void returnsEmptyWhenNoAreaContainsThePlayer() {
        AreaDefinition elsewhere = area("elsewhere", 0, ZERO, new AreaPosition(1, 1, 1));

        assertTrue(AreaResolver.resolve(List.of(elsewhere), OVERWORLD, PLAYER).isEmpty());
    }

    @Test
    void normalizesUnqualifiedCandidateDimension() {
        AreaDefinition area = area("spawn", 0, ZERO, new AreaPosition(10, 80, 10));

        assertEquals("spawn", AreaResolver.resolve(List.of(area), "overworld", PLAYER).orElseThrow().id());
    }

    @Test
    void higherPriorityWinsOverSmallerVolume() {
        AreaDefinition high = area("high", 10, ZERO, new AreaPosition(20, 100, 20));
        AreaDefinition small = area("small", 5, new AreaPosition(4, 64, 4), new AreaPosition(6, 66, 6));

        assertEquals("high", AreaResolver.resolve(List.of(small, high), OVERWORLD, PLAYER).orElseThrow().id());
    }

    @Test
    void smallerAreaWinsWhenPrioritiesMatch() {
        AreaDefinition broad = area("broad", 5, ZERO, new AreaPosition(20, 100, 20));
        AreaDefinition narrow = area("narrow", 5, new AreaPosition(4, 64, 4), new AreaPosition(6, 66, 6));

        assertEquals("narrow", AreaResolver.resolve(List.of(broad, narrow), OVERWORLD, PLAYER).orElseThrow().id());
    }

    @Test
    void lexicalAreaIdProvidesStableFinalTieBreak() {
        AreaDefinition beta = area("beta", 5, new AreaPosition(4, 64, 4), new AreaPosition(6, 66, 6));
        AreaDefinition alpha = area("alpha", 5, new AreaPosition(4, 64, 4), new AreaPosition(6, 66, 6));

        assertEquals("alpha", AreaResolver.resolve(List.of(beta, alpha), OVERWORLD, PLAYER).orElseThrow().id());
    }

    private static AreaDefinition area(String id, int priority, AreaPosition min, AreaPosition max) {
        return AreaDefinition.create(
                id,
                OVERWORLD,
                min,
                max,
                List.of(new AreaTrackDefinition(id + ".ogg", 0, 1.0f, true, 2000, 2000)),
                false,
                priority
        );
    }
}
