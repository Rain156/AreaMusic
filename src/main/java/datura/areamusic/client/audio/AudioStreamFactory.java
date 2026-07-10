package datura.areamusic.client.audio;

import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import javazoom.spi.vorbis.sampled.convert.VorbisFormatConversionProvider;
import javazoom.spi.vorbis.sampled.file.VorbisAudioFileReader;
import org.jflac.sound.spi.FlacAudioFileReader;
import org.jflac.sound.spi.FlacFormatConversionProvider;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.spi.AudioFileReader;
import javax.sound.sampled.spi.FormatConversionProvider;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

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
        Decoder decoder = decoderFor(path);
        AudioInputStream encoded = decoder == null
                ? AudioSystem.getAudioInputStream(path.toFile())
                : decoder.reader().getAudioInputStream(path.toFile());
        AudioInputStream current = encoded;
        AudioFormat sourceFormat = current.getFormat();
        try {
            if (MIX_FORMAT.matches(sourceFormat)) {
                return current;
            }
            if (decoder != null) {
                AudioFormat decodedFormat = chooseDecodedPcmFormat(decoder.converter(), sourceFormat);
                if (decodedFormat != null) {
                    current = decoder.converter().getAudioInputStream(decodedFormat, current);
                    sourceFormat = current.getFormat();
                    if (MIX_FORMAT.matches(sourceFormat)) {
                        return current;
                    }
                }
            }
            if (AudioSystem.isConversionSupported(MIX_FORMAT, sourceFormat)) {
                return AudioSystem.getAudioInputStream(MIX_FORMAT, current);
            }
            throw unsupported(path, sourceFormat);
        } catch (UnsupportedAudioFileException exception) {
            current.close();
            throw exception;
        } catch (IllegalArgumentException exception) {
            current.close();
            UnsupportedAudioFileException unsupported = unsupported(path, sourceFormat);
            unsupported.initCause(exception);
            throw unsupported;
        }
    }

    private static AudioFormat chooseDecodedPcmFormat(
            FormatConversionProvider converter,
            AudioFormat sourceFormat
    ) {
        if (sourceFormat.getSampleRate() > 0.0f && sourceFormat.getChannels() > 0) {
            AudioFormat nativePcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    sourceFormat.getChannels(),
                    sourceFormat.getChannels() * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            if (converter.isConversionSupported(nativePcmFormat, sourceFormat)) {
                return nativePcmFormat;
            }
        }
        return Arrays.stream(converter.getTargetFormats(AudioFormat.Encoding.PCM_SIGNED, sourceFormat))
                .filter(format -> format.getSampleRate() > 0.0f && format.getChannels() > 0)
                .min(Comparator
                        .comparingInt((AudioFormat format) -> format.getSampleSizeInBits() == 16 ? 0 : 1)
                        .thenComparingInt(format -> format.isBigEndian() ? 1 : 0))
                .orElse(null);
    }

    private static Decoder decoderFor(Path path) {
        String fileName = path.getFileName().toString();
        int dot = fileName.lastIndexOf('.');
        String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return switch (extension) {
            case "mp3" -> new Decoder(new MpegAudioFileReader(), new MpegFormatConversionProvider());
            case "ogg" -> new Decoder(new VorbisAudioFileReader(), new VorbisFormatConversionProvider());
            case "flac" -> new Decoder(new FlacAudioFileReader(), new FlacFormatConversionProvider());
            default -> null;
        };
    }

    private static UnsupportedAudioFileException unsupported(Path path, AudioFormat sourceFormat) {
        return new UnsupportedAudioFileException(
                "Cannot convert " + sourceFormat + " to " + MIX_FORMAT + " for " + path
        );
    }

    private record Decoder(AudioFileReader reader, FormatConversionProvider converter) {
    }
}
