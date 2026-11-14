package datura.areamusic.client.audio;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TrackDurationsTest {
    @TempDir
    Path tempDir;

    @Test
    void measuresInTheBackgroundAndAnswersFromMemoryAfterwards() throws Exception {
        Path wav = tempDir.resolve("two-seconds.wav");
        writeWav(wav, 2 * AudioStreamFactory.SAMPLE_RATE + AudioStreamFactory.SAMPLE_RATE / 2);
        List<Runnable> queued = new ArrayList<>();
        TrackDurations durations = new TrackDurations(new AudioStreamFactory(), queued::add);

        assertEquals(OptionalInt.empty(), durations.millis(wav));
        assertEquals(OptionalInt.empty(), durations.millis(wav));
        assertEquals(1, queued.size(), "asking again does not measure again");

        queued.get(0).run();
        assertEquals(OptionalInt.of(2500), durations.millis(wav));
        assertEquals(1, queued.size());

        durations.clear();
        assertEquals(OptionalInt.empty(), durations.millis(wav));
        assertEquals(2, queued.size());
    }

    @Test
    void compressedFormatsAreMeasuredByDecodingThem() throws Exception {
        TrackDurations durations = new TrackDurations(new AudioStreamFactory(), Runnable::run);
        for (String name : List.of("test.ogg", "test.mp3")) {
            Path file = tempDir.resolve(name);
            try (InputStream input = TrackDurationsTest.class.getResourceAsStream(
                    "/datura/areamusic/audio/" + name)) {
                assertNotNull(input, name);
                Files.copy(input, file);
            }
            durations.millis(file);
            int millis = durations.millis(file).orElse(-1);
            assertEquals(true, millis > 100 && millis < 600_000, name + " is " + millis + " ms");
        }
    }

    @Test
    void aFileThatIsNotAudioHasNoDurationAndIsNotRetriedEveryFrame() throws Exception {
        Path junk = tempDir.resolve("junk.ogg");
        Files.writeString(junk, "not audio");
        List<Runnable> queued = new ArrayList<>();
        TrackDurations durations = new TrackDurations(new AudioStreamFactory(), queued::add);

        durations.millis(junk);
        queued.get(0).run();

        assertEquals(OptionalInt.empty(), durations.millis(junk));
        assertEquals(OptionalInt.empty(), durations.millis(tempDir.resolve("missing.ogg")));
        assertEquals(2, queued.size(), "one attempt per file");
    }

    private static void writeWav(Path path, int frames) throws Exception {
        AudioFormat format = new AudioFormat(AudioStreamFactory.SAMPLE_RATE, 16, 1, true, false);
        byte[] pcm = new byte[frames * 2];
        try (AudioInputStream stream = new AudioInputStream(new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
