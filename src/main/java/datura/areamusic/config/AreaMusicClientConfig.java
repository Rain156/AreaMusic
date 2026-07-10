package datura.areamusic.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class AreaMusicClientConfig {
    public static final String FILE_NAME = "areamusic-client.toml";
    public static final AreaMusicClientConfig INSTANCE = create();

    private final ModConfigSpec spec;
    private final ModConfigSpec.DoubleValue volume;

    private AreaMusicClientConfig(ModConfigSpec spec, ModConfigSpec.DoubleValue volume) {
        this.spec = spec;
        this.volume = volume;
    }

    static AreaMusicClientConfig create() {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        ModConfigSpec.DoubleValue volume = builder
                .comment("Independent AreaMusic volume from 0.0 to 1.0")
                .defineInRange("volume", 1.0, 0.0, 1.0);
        return new AreaMusicClientConfig(builder.build(), volume);
    }

    public ModConfigSpec spec() {
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
