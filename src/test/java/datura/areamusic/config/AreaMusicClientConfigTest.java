package datura.areamusic.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.bus.api.BusBuilder;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ConfigTracker;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.IModBusEvent;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforgespi.language.IModInfo;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AreaMusicClientConfigTest {
    @Test
    void defaultsToFullVolumeAndWritesUpdatesToConfigData() {
        AreaMusicClientConfig config = AreaMusicClientConfig.create();
        CommentedConfig data = attach(config);
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
        attach(config);
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
    void usesTheDedicatedClientConfigFile() {
        assertEquals("areamusic-client.toml", AreaMusicClientConfig.FILE_NAME);
    }

    private static CommentedConfig attach(AreaMusicClientConfig config) {
        ModConfigSpec spec = config.spec();
        ConfigTracker tracker = new ConfigTracker();
        ModConfig modConfig = tracker.registerConfig(
                ModConfig.Type.CLIENT,
                spec,
                new TestModContainer(),
                AreaMusicClientConfig.FILE_NAME
        );
        ConfigTracker.acceptSyncedConfig(modConfig, new byte[0]);
        return modConfig.getLoadedConfig().config();
    }

    private static final class TestModContainer extends ModContainer {
        private final IEventBus eventBus;

        private TestModContainer() {
            super((IModInfo) Proxy.newProxyInstance(
                    IModInfo.class.getClassLoader(),
                    new Class<?>[]{IModInfo.class},
                    (proxy, method, args) -> "getModId".equals(method.getName()) ? "areamusic-test" : null
            ));
            eventBus = BusBuilder.builder().markerType(IModBusEvent.class).build();
            eventBus.start();
        }

        @Override
        public IEventBus getEventBus() {
            return eventBus;
        }
    }
}
