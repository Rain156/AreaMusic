package datura.areamusic.client.audio;

import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressedAudioFormatsTest {
    private static final int SOURCE_SAMPLE_RATE = 48_000;

    @TempDir
    Path tempDir;

    @ParameterizedTest
    @ValueSource(strings = {"test.mp3", "test.ogg", "test.flac"})
    void decodesCompressedFormatsWhenServiceLoaderResourcesAreHidden(String fileName) throws Exception {
        URL resource = getClass().getResource("/datura/areamusic/audio/" + fileName);
        assertNotNull(resource);
        Path source = tempDir.resolve(fileName);
        try (InputStream input = resource.openStream()) {
            Files.copy(input, source);
        }

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(new ServiceResourceBlockingClassLoader(original));
        try {
            try (AudioInputStream decoded = new AudioStreamFactory().open(source)) {
                assertEquals(AudioStreamFactory.MIX_FORMAT, decoded.getFormat());
                assertTrue(decoded.readNBytes(AudioStreamFactory.MIX_FORMAT.getFrameSize()).length > 0);
            }
        } finally {
            thread.setContextClassLoader(original);
        }
    }

    @Test
    void decodesEntireFlacStreamInFrameAlignedChunks() throws Exception {
        URL resource = getClass().getResource("/datura/areamusic/audio/test.flac");
        assertNotNull(resource);
        Path source = tempDir.resolve("test.flac");
        try (InputStream input = resource.openStream()) {
            Files.copy(input, source);
        }

        long totalBytes = 0;
        try (AudioInputStream decoded = new AudioStreamFactory().open(source)) {
            assertEquals(AudioStreamFactory.MIX_FORMAT, decoded.getFormat());
            byte[] buffer = new byte[4096];
            int read;
            while ((read = decoded.read(buffer)) != -1) {
                assertTrue(read > 0);
                assertEquals(0, read % decoded.getFormat().getFrameSize());
                totalBytes += read;
            }
        }
        assertTrue(totalBytes > 0);
    }

    @Test
    void preservesDurationWhenCompressedSourceUsesDifferentSampleRate() throws Exception {
        URL resource = getClass().getResource("/datura/areamusic/audio/test-48000.mp3.b64");
        assertNotNull(resource);

        byte[] frame;
        try (InputStream input = resource.openStream()) {
            frame = Base64.getMimeDecoder().decode(input.readAllBytes());
        }
        byte[] mp3 = new byte[frame.length * 32];
        for (int offset = 0; offset < mp3.length; offset += frame.length) {
            System.arraycopy(frame, 0, mp3, offset, frame.length);
        }
        Path source = tempDir.resolve("source-48000.mp3");
        Files.write(source, mp3);

        long nativeFrames;
        MpegFormatConversionProvider converter = new MpegFormatConversionProvider();
        try (AudioInputStream encoded = new MpegAudioFileReader().getAudioInputStream(source.toFile())) {
            AudioFormat encodedFormat = encoded.getFormat();
            assertEquals(SOURCE_SAMPLE_RATE, encodedFormat.getSampleRate());
            assertEquals(2, encodedFormat.getChannels());
            assertTrue(converter.isConversionSupported(AudioStreamFactory.MIX_FORMAT, encodedFormat));
            AudioFormat nativePcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    SOURCE_SAMPLE_RATE,
                    16,
                    encodedFormat.getChannels(),
                    encodedFormat.getChannels() * 2,
                    SOURCE_SAMPLE_RATE,
                    false
            );
            assertTrue(converter.isConversionSupported(nativePcmFormat, encodedFormat));
            try (AudioInputStream nativePcm = converter.getAudioInputStream(nativePcmFormat, encoded)) {
                nativeFrames = readFrames(nativePcm);
            }
        }
        assertTrue(nativeFrames > 0);

        long mixerFrames;
        try (AudioInputStream mixerPcm = new AudioStreamFactory().open(source)) {
            assertEquals(AudioStreamFactory.MIX_FORMAT, mixerPcm.getFormat());
            mixerFrames = readFrames(mixerPcm);
        }

        double nativeDuration = nativeFrames / (double) SOURCE_SAMPLE_RATE;
        double mixerDuration = mixerFrames / (double) AudioStreamFactory.SAMPLE_RATE;
        assertEquals(nativeDuration, mixerDuration, 0.0001);
    }

    private static long readFrames(AudioInputStream stream) throws IOException {
        long bytes = 0;
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            bytes += read;
        }
        assertEquals(0, bytes % stream.getFormat().getFrameSize());
        return bytes / stream.getFormat().getFrameSize();
    }

    private static final class ServiceResourceBlockingClassLoader extends ClassLoader {
        private ServiceResourceBlockingClassLoader(ClassLoader parent) {
            super(parent);
        }

        @Override
        public Enumeration<URL> getResources(String name) throws IOException {
            if (name.startsWith("META-INF/services/")) {
                return Collections.emptyEnumeration();
            }
            return super.getResources(name);
        }
    }
}
