package datura.areamusic.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioStreamFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void convertsMonoLowRateWavToTheMixerFormat() throws Exception {
        AudioFormat sourceFormat = new AudioFormat(22_050.0f, 16, 1, true, false);
        byte[] samples = new byte[2205 * sourceFormat.getFrameSize()];
        Path wav = tempDir.resolve("source.wav");
        try (AudioInputStream source = new AudioInputStream(
                new ByteArrayInputStream(samples), sourceFormat, 2205)) {
            AudioSystem.write(source, AudioFileFormat.Type.WAVE, wav.toFile());
        }

        try (AudioInputStream decoded = new AudioStreamFactory().open(wav)) {
            assertEquals(AudioStreamFactory.MIX_FORMAT, decoded.getFormat());
            long totalBytes = 0;
            byte[] buffer = new byte[4096];
            int read;
            while ((read = decoded.read(buffer)) != -1) {
                assertTrue(read > 0);
                assertEquals(0, read % decoded.getFormat().getFrameSize());
                totalBytes += read;
            }
            assertTrue(totalBytes > 0);
        }
    }
}
