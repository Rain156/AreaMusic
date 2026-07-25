package datura.areamusic.client.audio;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackMode;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioMixerProductionFactoryTest {
    @TempDir
    Path tempDir;

    @Test
    void productionFactoryPlaysSchemaV3PlaylistWavsInOrder() throws Exception {
        Path musicRoot = tempDir.resolve("music");
        writeConstantWav(musicRoot.resolve("first.wav"), (short) 1111, 2);
        writeConstantWav(musicRoot.resolve("second.wav"), (short) 2222, 2);
        MusicLibrary library = MusicLibrary.scan(musicRoot);
        AreaDefinition area = new AreaJsonCodec().read("playlist", new StringReader("""
                {
                  "schemaVersion": 3,
                  "dimension": "minecraft:overworld",
                  "pos1": { "x": 0, "y": 60, "z": 0 },
                  "pos2": { "x": 1, "y": 61, "z": 1 },
                  "playbackMode": "playlist_loop",
                  "playlist": ["first.wav", "second.wav"],
                  "volume": 1.0,
                  "fadeInMs": 0,
                  "fadeOutMs": 0,
                  "resumeOnReenter": false,
                  "priority": 0
                }
                """));
        PlaybackState state = PlaybackState.fromArea(area);
        assertEquals(PlaybackMode.PLAYLIST_LOOP, state.mode().orElseThrow());

        List<Integer> audible = new ArrayList<>();
        List<AudioFailure> failures = new ArrayList<>();
        PcmAudioMixer.AudioEngine engine = PcmAudioMixer.createEngine(library);
        try {
            engine.apply(41L, state);
            long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (audible.size() < 4 && System.nanoTime() < deadline && engine.hasWork()) {
                byte[] frame = engine.renderFrames(1, 1.0f);
                int sample = PcmMath.readLittleEndian(frame, 0);
                if (sample != 0) {
                    audible.add(sample);
                }
                failures.addAll(engine.drainFailures());
                if (audible.size() < 4) {
                    LockSupport.parkNanos(Duration.ofMillis(1).toNanos());
                }
            }
        } finally {
            failures.addAll(engine.drainFailures());
            engine.close();
        }

        assertTrue(failures.isEmpty(), () -> "Unexpected audio failures: " + failures);
        assertEquals(List.of(1111, 1111, 2222, 2222), audible);
    }

    private static void writeConstantWav(Path path, short sample, int frames) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] pcm = new byte[frames * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < frames; frame++) {
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, sample);
            PcmMath.writeLittleEndian(pcm, offset + 2, sample);
        }
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), AudioStreamFactory.MIX_FORMAT, frames
        )) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }
}
