package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmMixerEngineTest {
    @TempDir
    Path tempDir;

    @Test
    void keepsStreamPositionWhenAdjacentAreasUseTheSameMusicId() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("track.wav"), new short[]{1000, 2000, 3000});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(PlaybackState.playing("first", "track.wav", 1.0f, false, 0, 0));
        assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

        engine.apply(PlaybackState.playing("second", "track.wav", 0.5f, false, 0, 0));
        assertEquals(1000, firstLeftSample(engine.renderFrames(1, 1.0f)));

        engine.close();
    }

    @Test
    void crossfadesOldAndNewTracksAtTheSameTime() throws Exception {
        Path root = tempDir.resolve("music");
        short[] positive = constantFrames(50_000, (short) 10_000);
        short[] negative = constantFrames(50_000, (short) -10_000);
        writeWav(root.resolve("old.wav"), positive);
        writeWav(root.resolve("new.wav"), negative);
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(PlaybackState.playing("old", "old.wav", 1.0f, false, 0, 1000));
        engine.renderFrames(1, 1.0f);
        engine.apply(PlaybackState.playing("new", "new.wav", 1.0f, false, 1000, 1000));

        engine.renderFrames(22_050, 1.0f);
        int halfWaySample = firstLeftSample(engine.renderFrames(1, 1.0f));

        assertEquals(0, halfWaySample, 2);
        engine.close();
    }

    @Test
    void loopsByReopeningTheStreamAtEndOfFile() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("loop.wav"), new short[]{1000, 2000});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));
        engine.apply(PlaybackState.playing("loop", "loop.wav", 1.0f, true, 0, 0));

        byte[] rendered = engine.renderFrames(3, 1.0f);

        assertEquals(1000, leftSample(rendered, 0));
        assertEquals(2000, leftSample(rendered, 1));
        assertEquals(1000, leftSample(rendered, 2));
        engine.close();
    }

    @Test
    void completedNonLoopTrackStaysSilentWhenIdenticalStateIsReapplied() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{4321});
        MusicLibrary library = MusicLibrary.scan(root);
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), library);
        PlaybackState state = PlaybackState.playing("once", "once.wav", 1.0f, false, 0, 0);

        engine.apply(state);
        assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));

        engine.setMusicLibrary(library);
        engine.apply(state);

        assertEquals(0, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    @Test
    void completedNonLoopTrackRestartsWhenReloadChangesItToLooping() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("once.wav"), new short[]{4321});
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        engine.apply(PlaybackState.playing("once", "once.wav", 1.0f, false, 0, 0));
        assertEquals(4321, firstLeftSample(engine.renderFrames(2, 1.0f)));

        engine.apply(PlaybackState.playing("once", "once.wav", 1.0f, true, 0, 0));

        assertEquals(4321, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    @Test
    void fifthRapidTransitionDoesNotHardEvictAnAudibleTrack() throws Exception {
        Path root = tempDir.resolve("music");
        writeWav(root.resolve("track0.wav"), constantFrames(16, (short) 10_000));
        for (int index = 1; index < 5; index++) {
            writeWav(root.resolve("track" + index + ".wav"), constantFrames(16, (short) 0));
        }
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        for (int index = 0; index < 4; index++) {
            engine.apply(PlaybackState.playing(
                    "area" + index, "track" + index + ".wav", 1.0f, true, 0, 10_000
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(PlaybackState.playing("area4", "track4.wav", 1.0f, true, 0, 10_000));

        assertTrue(firstLeftSample(engine.renderFrames(1, 1.0f)) > 9_000);
        engine.close();
    }

    @Test
    void fifthRapidTransitionStartsAfterOneBoundedSoftFade() throws Exception {
        Path root = tempDir.resolve("music");
        for (int index = 0; index < 4; index++) {
            writeWav(root.resolve("track" + index + ".wav"), constantFrames(4096, (short) 0));
        }
        writeWav(root.resolve("track4.wav"), constantFrames(4096, (short) 5000));
        PcmMixerEngine engine = new PcmMixerEngine(new AudioStreamFactory(), MusicLibrary.scan(root));

        for (int index = 0; index < 4; index++) {
            engine.apply(PlaybackState.playing(
                    "area" + index, "track" + index + ".wav", 1.0f, true, 0, 60_000
            ));
            engine.renderFrames(1, 1.0f);
        }

        engine.apply(PlaybackState.playing("area4", "track4.wav", 1.0f, true, 0, 60_000));
        engine.renderFrames(1024, 1.0f);

        assertEquals(5000, firstLeftSample(engine.renderFrames(1, 1.0f)));
        engine.close();
    }

    private static short[] constantFrames(int count, short sample) {
        short[] frames = new short[count];
        java.util.Arrays.fill(frames, sample);
        return frames;
    }

    private static void writeWav(Path path, short[] monoSamples) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] pcm = new byte[monoSamples.length * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        for (int frame = 0; frame < monoSamples.length; frame++) {
            int offset = frame * AudioStreamFactory.MIX_FORMAT.getFrameSize();
            PcmMath.writeLittleEndian(pcm, offset, monoSamples[frame]);
            PcmMath.writeLittleEndian(pcm, offset + 2, monoSamples[frame]);
        }
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), AudioStreamFactory.MIX_FORMAT, monoSamples.length)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static int firstLeftSample(byte[] pcm) {
        return leftSample(pcm, 0);
    }

    private static int leftSample(byte[] pcm, int frame) {
        return PcmMath.readLittleEndian(pcm, frame * AudioStreamFactory.MIX_FORMAT.getFrameSize());
    }
}
