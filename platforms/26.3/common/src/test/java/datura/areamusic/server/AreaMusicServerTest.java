package datura.areamusic.server;

import com.mojang.brigadier.CommandDispatcher;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.area.AreaStorage;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicServerTest {
    @TempDir
    Path tempDir;

    @Test
    void exposesLoaderNeutralLifecycleEntryPoints() throws Exception {
        List<Method> entryPoints = List.of(
                AreaMusicServer.class.getMethod("registerCommands", CommandDispatcher.class),
                AreaMusicServer.class.getMethod(
                        "onServerStarted",
                        MinecraftServer.class,
                        Path.class,
                        Path.class,
                        NetworkSender.class
                ),
                AreaMusicServer.class.getMethod("onServerStopping", MinecraftServer.class),
                AreaMusicServer.class.getMethod("onPlayerEndTick", ServerPlayer.class),
                AreaMusicServer.class.getMethod("onPlayerLogin", ServerPlayer.class),
                AreaMusicServer.class.getMethod("onPlayerLogout", ServerPlayer.class),
                AreaMusicServer.class.getMethod("onPlayerRespawn", ServerPlayer.class),
                AreaMusicServer.class.getMethod("onPlayerChangedDimension", ServerPlayer.class)
        );

        assertTrue(entryPoints.stream().allMatch(method -> Modifier.isPublic(method.getModifiers())));
        assertTrue(entryPoints.stream().allMatch(method -> Modifier.isStatic(method.getModifiers())));
    }

    @Test
    void storageLivesInTheWorldAndLegacyDirectoryStaysUnderConfig() {
        Path configDirectory = tempDir.resolve("config").resolve("..").resolve("config");
        Path worldRoot = tempDir.resolve("saves").resolve("My World");

        assertEquals(
                worldRoot.toAbsolutePath().normalize().resolve("areamusic").resolve("areas"),
                AreaMusicServer.storageDirectory(worldRoot)
        );
        assertEquals(
                AreaStorage.directory(configDirectory, AreaStorage.saveId("My World")),
                AreaMusicServer.legacyStorageDirectory(configDirectory, worldRoot)
        );
    }

    @Test
    void loadCandidateMigratesLegacyAreasAndKeepsAreasWithMissingMusic() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path worldRoot = tempDir.resolve("saves").resolve("world");
        Path legacy = AreaMusicServer.legacyStorageDirectory(tempDir.resolve("config"), worldRoot);
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("village.json"), """
                {
                  "schemaVersion": 1,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 0, "y": 64, "z": 0 },
                  "pos2": { "x": 10, "y": 80, "z": 10 },
                  "musicId": "not-in-library.ogg"
                }
                """);
        Files.writeString(legacy.resolve("broken.json"), "{");
        AreaStorage storage = new AreaStorage(
                AreaMusicServer.storageDirectory(worldRoot), new AreaJsonCodec());

        AreaMusicServer.ReloadCandidate candidate =
                AreaMusicServer.loadCandidate(gameDirectory, storage, legacy);

        assertEquals(List.of("village"), candidate.areas().stream().map(area -> area.id()).toList());
        assertEquals(1, candidate.skipped().size());
        assertTrue(candidate.skipped().get(0).startsWith("broken.json: "));
        assertTrue(Files.exists(storage.directory().resolve("village.json")));
        assertTrue(Files.exists(legacy.resolve("village.json")));

        AreaMusicServer.ReloadCandidate again =
                AreaMusicServer.loadCandidate(gameDirectory, storage, legacy);
        assertEquals(List.of("village"), again.areas().stream().map(area -> area.id()).toList());
        assertEquals(List.of(), again.skipped());
    }

    @Test
    void loadCandidateMigratesTheLegacyDirectoryAndUsesTheLowercaseRoot() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "fixture");
        AreaStorage storage = new AreaStorage(tempDir.resolve("areas"), new AreaJsonCodec());

        AreaMusicServer.ReloadCandidate candidate = AreaMusicServer.loadCandidate(gameDirectory, storage);

        assertEquals(gameDirectory.toAbsolutePath().normalize().resolve("areamusic"),
                candidate.musicLibrary().root());
        assertEquals(Set.of("track.ogg"), candidate.musicLibrary().ids());
        assertEquals(Set.of("areamusic"), exactChildNames(gameDirectory));
    }

    @Test
    void failedMigrationDoesNotReplaceTheCurrentSnapshotOrCreateAFallback() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Files.createDirectories(gameDirectory);
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.writeString(legacy, "not a directory");
        AreaStorage storage = new AreaStorage(tempDir.resolve("areas"), new AreaJsonCodec());
        Field stateField = AreaMusicServer.class.getDeclaredField("serverState");
        stateField.setAccessible(true);
        Object original = stateField.get(null);

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> AreaMusicServer.loadCandidate(gameDirectory, storage));

        assertInstanceOf(IOException.class, failure.getCause());
        assertSame(original, stateField.get(null));
        assertEquals("not a directory", Files.readString(legacy));
        assertEquals(Set.of("AreaMusic"), exactChildNames(gameDirectory));
    }

    private static Set<String> exactChildNames(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }
}
