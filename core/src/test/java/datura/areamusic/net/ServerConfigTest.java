package datura.areamusic.net;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigTest {
    @TempDir
    Path tempDir;

    @Test
    void createsTheFileWithDefaultsWhenMissing() throws Exception {
        Path file = tempDir.resolve("config").resolve(ServerConfig.FILE_NAME);

        ServerConfig config = ServerConfig.load(file);

        assertTrue(Files.readString(file).contains("distributeMusic=true"));
        assertEquals(new ServerSync.Settings(true, 32L << 20, 1024L << 20, 2560L * 1024 / 20), config.sync());
        assertEquals(ServerSync.Settings.DEFAULT, config.sync());
        assertEquals(2, config.editPermissionLevel());
        assertEquals(List.of(), config.warnings());
        assertEquals(config, ServerConfig.load(file));
    }

    @Test
    void readsCustomValues() throws Exception {
        Path file = tempDir.resolve(ServerConfig.FILE_NAME);
        Files.writeString(file, """
                distributeMusic = FALSE
                maxUploadMiB=8
                maxLibraryMiB=64
                downloadKiBPerSecondPerPlayer=100
                editPermissionLevel=4
                """);

        ServerConfig config = ServerConfig.load(file);

        assertFalse(config.sync().distributeMusic());
        assertEquals(8L << 20, config.sync().maxUploadBytes());
        assertEquals(64L << 20, config.sync().maxLibraryBytes());
        assertEquals(5120, config.sync().downloadBytesPerTick());
        assertEquals(4, config.editPermissionLevel());
    }

    @Test
    void badValuesFallBackToDefaultsWithWarnings() throws Exception {
        Path file = tempDir.resolve(ServerConfig.FILE_NAME);
        Files.writeString(file, """
                maxUploadMiB=lots
                editPermissionLevel=9
                downloadKiBPerSecondPerPlayer=0
                """);

        ServerConfig config = ServerConfig.load(file);

        assertEquals(ServerSync.Settings.DEFAULT, config.sync());
        assertEquals(2, config.editPermissionLevel());
        assertEquals(3, config.warnings().size());
    }
}
