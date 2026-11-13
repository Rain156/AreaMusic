package datura.areamusic.fabric.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FabricClientConfigTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingConfigDefaultsToFullVolumeWithoutBreakingStartup() throws Throwable {
        Class<?> type = configType();
        Object config = load(type, temporaryDirectory);

        assertEquals(1.0, volume(type, config), 0.0001);
        assertFalse(Files.exists(configFile(type, temporaryDirectory)));
    }

    @Test
    void updatesAreClampedSavedAndReloadedFromTheDedicatedFile() throws Throwable {
        Class<?> type = configType();
        Path unnormalizedDirectory = temporaryDirectory
                .resolve("config")
                .resolve("child")
                .resolve("..");
        Object config = load(type, unnormalizedDirectory);
        Method setVolume = requiredMethod(type, "setVolume", double.class);

        invoke(setVolume, config, 0.37);
        Path file = configFile(type, temporaryDirectory.resolve("config"));
        assertEquals("volume=0.37\n", Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(0.37, volume(type, load(type, temporaryDirectory.resolve("config"))), 0.0001);

        invoke(setVolume, config, -0.5);
        assertEquals(0.0, volume(type, config), 0.0001);
        assertEquals("volume=0.0\n", Files.readString(file, StandardCharsets.UTF_8));

        invoke(setVolume, config, 1.5);
        assertEquals(1.0, volume(type, config), 0.0001);
        assertEquals("volume=1.0\n", Files.readString(file, StandardCharsets.UTF_8));
    }

    @Test
    void malformedOrNonFiniteFilesSafelyFallBackToDefault() throws Throwable {
        Class<?> type = configType();
        Path file = configFile(type, temporaryDirectory);
        for (String malformed : new String[]{
                "garbage\n",
                "volume=not-a-number\n",
                "volume=NaN\n",
                "volume=Infinity\n",
                "volume=0.4\nextra=true\n"
        }) {
            Files.writeString(file, malformed, StandardCharsets.UTF_8);
            assertEquals(1.0, volume(type, load(type, temporaryDirectory)), 0.0001,
                    "must recover from: " + malformed);
        }
    }

    @Test
    void finiteFileValuesAreClampedToTheSliderRange() throws Throwable {
        Class<?> type = configType();
        Path file = configFile(type, temporaryDirectory);

        Files.writeString(file, "volume=-4.0\n", StandardCharsets.UTF_8);
        assertEquals(0.0, volume(type, load(type, temporaryDirectory)), 0.0001);

        Files.writeString(file, "volume=4.0\n", StandardCharsets.UTF_8);
        assertEquals(1.0, volume(type, load(type, temporaryDirectory)), 0.0001);
    }

    @Test
    void programmaticNonFiniteUpdatesAreRejectedWithoutOverwritingTheFile() throws Throwable {
        Class<?> type = configType();
        Object config = load(type, temporaryDirectory);
        Method setVolume = requiredMethod(type, "setVolume", double.class);
        invoke(setVolume, config, 0.25);
        Path file = configFile(type, temporaryDirectory);
        String before = Files.readString(file, StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> invoke(setVolume, config, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> invoke(setVolume, config, Double.POSITIVE_INFINITY));

        assertEquals(before, Files.readString(file, StandardCharsets.UTF_8));
        assertEquals(0.25, volume(type, config), 0.0001);
    }

    @Test
    void unreadableConfigPathFallsBackWithoutBreakingStartup() throws Throwable {
        Class<?> type = configType();
        Path file = configFile(type, temporaryDirectory);
        Files.createDirectory(file);

        Object config = load(type, temporaryDirectory);

        assertEquals(1.0, volume(type, config), 0.0001);
    }

    private static Class<?> configType() {
        try {
            return Class.forName("datura.areamusic.fabric.config.FabricClientConfig");
        } catch (ClassNotFoundException missing) {
            return fail("FabricClientConfig is missing", missing);
        }
    }

    private static Object load(Class<?> type, Path configDirectory) throws Throwable {
        return invoke(requiredMethod(type, "load", Path.class), null, configDirectory);
    }

    private static double volume(Class<?> type, Object config) throws Throwable {
        return (Double) invoke(requiredMethod(type, "volume"), config);
    }

    private static Path configFile(Class<?> type, Path configDirectory) throws Exception {
        Field fileName = type.getField("FILE_NAME");
        return configDirectory.toAbsolutePath().normalize().resolve((String) fileName.get(null));
    }

    private static Method requiredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException missing) {
            return fail("required method is missing: " + type.getName() + "#" + name, missing);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }
}
