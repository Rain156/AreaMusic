package datura.areamusic.client.audio;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sound.sampled.AudioInputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompressedAudioFormatsTest {
    @ParameterizedTest
    @ValueSource(strings = {"test.mp3", "test.ogg", "test.flac"})
    void decodesCompressedFormatsWhenServiceLoaderResourcesAreHidden(String fileName) throws Exception {
        URL resource = getClass().getResource("/datura/areamusic/audio/" + fileName);
        assertNotNull(resource);

        Thread thread = Thread.currentThread();
        ClassLoader original = thread.getContextClassLoader();
        thread.setContextClassLoader(new ServiceResourceBlockingClassLoader(original));
        try {
            try (AudioInputStream decoded = new AudioStreamFactory().open(Path.of(resource.toURI()))) {
                assertEquals(AudioStreamFactory.MIX_FORMAT, decoded.getFormat());
                assertTrue(decoded.readNBytes(AudioStreamFactory.MIX_FORMAT.getFrameSize()).length > 0);
            }
        } finally {
            thread.setContextClassLoader(original);
        }
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
