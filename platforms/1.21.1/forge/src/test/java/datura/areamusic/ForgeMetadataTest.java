package datura.areamusic;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeMetadataTest {
    @Test
    void expandedMetadataTargetsForge52AndMinecraft1211() throws IOException {
        String metadata;
        try (InputStream input = getClass().getClassLoader()
                .getResourceAsStream("META-INF/mods.toml")) {
            assertNotNull(input, "processed Forge metadata is missing");
            metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(metadata.contains("modLoader = \"javafml\""));
        assertTrue(metadata.contains("loaderVersion = \"[52,)\""));
        assertTrue(metadata.contains("modId = \"areamusic\""));
        assertTrue(metadata.contains("version = \"0.0.1\""));
        assertTrue(metadata.contains("modId = \"forge\""));
        assertTrue(metadata.contains("versionRange = \"[52,)\""));
        assertTrue(metadata.contains("modId = \"minecraft\""));
        assertTrue(metadata.contains("versionRange = \"[1.21.1,1.22)\""));
        assertFalse(metadata.contains("${"), "metadata still contains unexpanded tokens");
    }
}
