package datura.areamusic.area;

import java.util.List;
import java.util.Objects;

public final class AreaPalette {
    private static final List<Integer> COLORS = List.of(
            0x1D9E75, 0x7F77DD, 0xD85A30, 0x378ADD, 0xD4537E, 0xBA7517, 0x639922, 0xE24B4A
    );

    private AreaPalette() {
    }

    public static List<Integer> colors() {
        return COLORS;
    }

    public static int colorFor(String areaId) {
        Objects.requireNonNull(areaId, "areaId");
        return COLORS.get(Math.floorMod(areaId.hashCode(), COLORS.size()));
    }
}
