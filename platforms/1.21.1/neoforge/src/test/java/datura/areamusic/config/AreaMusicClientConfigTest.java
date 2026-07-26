package datura.areamusic.config;

import com.electronwill.nightconfig.core.CommentedConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.IConfigSpec;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforgespi.language.IModInfo;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AreaMusicClientConfigTest {
    @Test
    void defaultsToFullVolumeAndPersistsFiniteClampedUpdates() {
        AreaMusicClientConfig config = AreaMusicClientConfig.create();
        CommentedConfig data = CommentedConfig.inMemory();
        attach(config, data);
        try {
            assertEquals(1.0, config.volume(), 0.0001);
            config.setVolume(0.37);
            assertEquals(0.37, config.volume(), 0.0001);
            assertEquals(0.37, data.<Double>get("volume"), 0.0001);

            config.setVolume(-0.5);
            assertEquals(0.0, config.volume(), 0.0001);
            config.setVolume(1.5);
            assertEquals(1.0, config.volume(), 0.0001);
            assertThrows(IllegalArgumentException.class, () -> config.setVolume(Double.NaN));
        } finally {
            config.spec().acceptConfig(null);
        }
    }

    @Test
    void declaresTheDedicatedClientConfigFile() {
        assertEquals("areamusic-client.toml", AreaMusicClientConfig.FILE_NAME);
    }

    private static void attach(AreaMusicClientConfig config, CommentedConfig data) {
        ModConfigSpec spec = config.spec();
        spec.correct(data);
        try {
            ModContainer container = new ModContainer((IModInfo) Proxy.newProxyInstance(
                    AreaMusicClientConfigTest.class.getClassLoader(),
                    new Class<?>[]{IModInfo.class},
                    (proxy, method, arguments) -> "getModId".equals(method.getName())
                            ? "areamusic" : null
            )) {
                @Override
                public IEventBus getEventBus() {
                    return null;
                }
            };
            Constructor<ModConfig> modConfigConstructor = ModConfig.class.getDeclaredConstructor(
                    ModConfig.Type.class, IConfigSpec.class, ModContainer.class,
                    String.class, ReentrantLock.class
            );
            modConfigConstructor.setAccessible(true);
            ModConfig modConfig = modConfigConstructor.newInstance(
                    ModConfig.Type.CLIENT, spec, container, AreaMusicClientConfig.FILE_NAME,
                    new ReentrantLock()
            );
            Class<?> loadedConfigClass = Class.forName("net.neoforged.fml.config.LoadedConfig");
            Constructor<?> loadedConfigConstructor = loadedConfigClass.getDeclaredConstructor(
                    CommentedConfig.class, Path.class, ModConfig.class
            );
            loadedConfigConstructor.setAccessible(true);
            spec.acceptConfig((IConfigSpec.ILoadedConfig) loadedConfigConstructor.newInstance(
                    data, null, modConfig
            ));
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("could not attach a real NeoForge loaded config", failure);
        }
    }
}
