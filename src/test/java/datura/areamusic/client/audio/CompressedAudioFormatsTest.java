package datura.areamusic.client.audio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.AudioInputStream;
import java.net.URL;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressedAudioFormatsTest {
    @ParameterizedTest
    @ValueSource(strings = {"test.mp3", "test.ogg", "test.flac"})
    void bundledServiceProvidersDecodeSupportedCompressedFormats(String fileName) throws Exception {
        URL resource = getClass().getResource("/datura/areamusic/audio/" + fileName);
        assertNotNull(resource);

        try (AudioInputStream decoded = new AudioStreamFactory().open(Path.of(resource.toURI()))) {
            assertEquals(AudioStreamFactory.MIX_FORMAT, decoded.getFormat());
            assertTrue(decoded.readNBytes(AudioStreamFactory.MIX_FORMAT.getFrameSize()).length > 0);
        }
    }
}
