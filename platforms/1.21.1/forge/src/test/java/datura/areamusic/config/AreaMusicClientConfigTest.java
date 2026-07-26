package datura.areamusic.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AreaMusicClientConfigTest {
    @Test
    void defaultsToFullVolumeAndWritesUpdatesToConfigData() {
        AreaMusicClientConfig config = AreaMusicClientConfig.create();
        CommentedConfig data = CommentedConfig.inMemory();
        attach(config, data);
        try {
            assertEquals(1.0, config.volume(), 0.0001);

            config.setVolume(0.37);

            assertEquals(0.37, config.volume(), 0.0001);
            assertEquals(0.37, data.<Double>get("volume"), 0.0001);
        } finally {
            config.spec().acceptConfig(null);
        }
    }

    @Test
    void programmaticUpdatesAreClampedToTheSliderRange() {
        AreaMusicClientConfig config = AreaMusicClientConfig.create();
        CommentedConfig data = CommentedConfig.inMemory();
        attach(config, data);
        try {
            config.setVolume(-0.5);
            assertEquals(0.0, config.volume(), 0.0001);

            config.setVolume(1.5);
            assertEquals(1.0, config.volume(), 0.0001);
        } finally {
            config.spec().acceptConfig(null);
        }
    }

    @Test
    void rejectsNonFiniteVolumeUpdates() {
        AreaMusicClientConfig config = AreaMusicClientConfig.create();
        CommentedConfig data = CommentedConfig.inMemory();
        attach(config, data);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> config.setVolume(Double.NaN)
            );
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> config.setVolume(Double.POSITIVE_INFINITY)
            );
        } finally {
            config.spec().acceptConfig(null);
        }
    }

    @Test
    void usesTheDedicatedClientConfigFile() {
        assertEquals("areamusic-client.toml", AreaMusicClientConfig.FILE_NAME);
    }

    private static void attach(AreaMusicClientConfig config, CommentedConfig data) {
        ForgeConfigSpec spec = config.spec();
        spec.correct(data);
        spec.acceptConfig(data);
    }
}
