package datura.areamusic;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeMetadataTest {
    @Test
    void expandedMetadataTargetsOnlyNeoForgeAndMinecraft1211() throws Exception {
        Path root = findRepositoryRoot();
        String metadata = Files.readString(root.resolve(
                "platforms/1.21.1/neoforge/build/resources/main/META-INF/neoforge.mods.toml"
        ), StandardCharsets.UTF_8);

        assertTrue(metadata.contains("modLoader = \"javafml\""));
        assertTrue(metadata.contains("modId = \"areamusic\""));
        assertTrue(metadata.contains("version = \"0.0.1\""));
        assertTrue(metadata.contains("modId = \"neoforge\""));
        assertTrue(metadata.contains("versionRange = \"[21.1.244,)\""));
        assertTrue(metadata.contains("modId = \"minecraft\""));
        assertTrue(metadata.contains("versionRange = \"[1.21.1,1.22)\""));
        assertFalse(metadata.contains("modId = \"forge\""));
        assertFalse(metadata.toLowerCase().contains("fabric"));
        assertFalse(metadata.toLowerCase().contains("architectury"));
        assertFalse(metadata.contains("${"), "metadata still contains unexpanded tokens");
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("settings.gradle"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new AssertionError("could not locate repository root");
    }
}
