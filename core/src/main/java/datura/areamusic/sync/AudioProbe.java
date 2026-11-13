package datura.areamusic.sync;

import datura.areamusic.client.audio.AudioStreamFactory;

import javax.sound.sampled.AudioInputStream;
import java.nio.file.Path;

/** Checks that a file is audio this mod can actually play, not just a file with the right name. */
public final class AudioProbe {
    private AudioProbe() {
    }

    public static boolean isDecodable(Path file) {
        try (AudioInputStream stream = new AudioStreamFactory().open(file)) {
            int frameSize = Math.max(1, stream.getFormat().getFrameSize());
            byte[] buffer = new byte[frameSize * 256];
            for (int attempt = 0; attempt < 64; attempt++) {
                int read = stream.read(buffer);
                if (read > 0) {
                    return true;
                }
                if (read < 0) {
                    return false;
                }
            }
            return false;
        } catch (Exception | LinkageError exception) {
            return false;
        }
    }
}
