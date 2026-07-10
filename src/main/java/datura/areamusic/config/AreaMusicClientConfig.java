package datura.areamusic.config;

import net.minecraftforge.common.ForgeConfigSpec;

public final class AreaMusicClientConfig {
    public static final String FILE_NAME = "areamusic-client.toml";
    public static final AreaMusicClientConfig INSTANCE = create();

    private final ForgeConfigSpec spec;
    private final ForgeConfigSpec.DoubleValue volume;

    private AreaMusicClientConfig(ForgeConfigSpec spec, ForgeConfigSpec.DoubleValue volume) {
        this.spec = spec;
        this.volume = volume;
    }

    static AreaMusicClientConfig create() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        ForgeConfigSpec.DoubleValue volume = builder
                .comment("Independent AreaMusic volume from 0.0 to 1.0")
                .defineInRange("volume", 1.0, 0.0, 1.0);
        return new AreaMusicClientConfig(builder.build(), volume);
    }

    public ForgeConfigSpec spec() {
        return spec;
    }

    public double volume() {
        return volume.get();
    }

    public void setVolume(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("AreaMusic volume must be finite");
        }
        volume.set(Math.max(0.0, Math.min(1.0, value)));
        volume.save();
    }
}
