package datura.areamusic.client;

public final class AreaMusicVolume {
    private AreaMusicVolume() {
    }

    public static float effectiveGain(float masterVolume, double areaMusicVolume) {
        if (!Float.isFinite(masterVolume) || !Double.isFinite(areaMusicVolume)) {
            throw new IllegalArgumentException("Volume values must be finite");
        }
        float boundedMaster = Math.max(0.0f, Math.min(1.0f, masterVolume));
        float boundedAreaMusic = (float) Math.max(0.0, Math.min(1.0, areaMusicVolume));
        return boundedMaster * boundedAreaMusic;
    }
}
