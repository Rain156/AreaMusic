package datura.areamusic.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioProbeTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"test.ogg", "test.mp3", "test.flac"})
    void realAudioIsDecodable(String resource) throws Exception {
        Path file = tempDir.resolve(resource);
        try (InputStream input = AudioProbeTest.class.getResourceAsStream(
                "/datura/areamusic/audio/" + resource)) {
            assertNotNull(input, resource);
            Files.copy(input, file);
        }

        assertTrue(AudioProbe.isDecodable(file));
    }

    @Test
    void renamedTextFileIsNotDecodable() throws Exception {
        Path file = tempDir.resolve("fake.ogg");
        Files.writeString(file, "this is not audio at all, just text pretending to be a song");

        assertFalse(AudioProbe.isDecodable(file));
    }

    @Test
    void emptyAndMissingFilesAreNotDecodable() throws Exception {
        Path empty = tempDir.resolve("empty.mp3");
        Files.createFile(empty);

        assertFalse(AudioProbe.isDecodable(empty));
        assertFalse(AudioProbe.isDecodable(tempDir.resolve("missing.flac")));
    }
}
