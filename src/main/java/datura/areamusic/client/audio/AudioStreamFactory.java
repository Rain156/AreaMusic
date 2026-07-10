package datura.areamusic.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;

public class AudioStreamFactory {
    public static final int SAMPLE_RATE = 44_100;
    public static final AudioFormat MIX_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE,
            16,
            2,
            4,
            SAMPLE_RATE,
            false
    );

    public AudioInputStream open(Path path) throws UnsupportedAudioFileException, IOException {
        AudioInputStream encoded = AudioSystem.getAudioInputStream(path.toFile());
        AudioFormat sourceFormat = encoded.getFormat();
        if (AudioSystem.isConversionSupported(MIX_FORMAT, sourceFormat)) {
            return AudioSystem.getAudioInputStream(MIX_FORMAT, encoded);
        }

        AudioFormat decodedFormat = chooseDecodedPcmFormat(sourceFormat);
        if (decodedFormat == null) {
            encoded.close();
            throw unsupported(path, sourceFormat);
        }

        AudioInputStream decoded = AudioSystem.getAudioInputStream(decodedFormat, encoded);
        if (MIX_FORMAT.matches(decoded.getFormat())) {
            return decoded;
        }
        if (!AudioSystem.isConversionSupported(MIX_FORMAT, decoded.getFormat())) {
            decoded.close();
            throw unsupported(path, sourceFormat);
        }
        return AudioSystem.getAudioInputStream(MIX_FORMAT, decoded);
    }

    private static AudioFormat chooseDecodedPcmFormat(AudioFormat sourceFormat) {
        return Arrays.stream(AudioSystem.getTargetFormats(AudioFormat.Encoding.PCM_SIGNED, sourceFormat))
                .filter(format -> format.getSampleRate() > 0.0f && format.getChannels() > 0)
                .min(Comparator
                        .comparingInt((AudioFormat format) -> format.getSampleSizeInBits() == 16 ? 0 : 1)
                        .thenComparingInt(format -> format.isBigEndian() ? 1 : 0))
                .orElse(null);
    }

    private static UnsupportedAudioFileException unsupported(Path path, AudioFormat sourceFormat) {
        return new UnsupportedAudioFileException(
                "Cannot convert " + sourceFormat + " to " + MIX_FORMAT + " for " + path
        );
    }
}
