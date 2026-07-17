package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
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

    @Test
    void temporaryZeroReadsDoNotInsertSilentFrames() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Files.write(root.resolve("scripted.wav"), new byte[0]);
        byte[] expected = new byte[2 * AudioStreamFactory.MIX_FORMAT.getFrameSize()];
        PcmMath.writeLittleEndian(expected, 0, 1234);
        PcmMath.writeLittleEndian(expected, 2, -2345);
        PcmMath.writeLittleEndian(expected, 4, 3456);
        PcmMath.writeLittleEndian(expected, 6, -4567);
        AudioInputStream stream = new ScriptedZeroReadAudioInputStream(expected, 3, false);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(PlaybackState.playing("scripted", "scripted.wav", 1.0f, false, 0, 0));

            assertArrayEquals(expected, engine.renderFrames(2, 1.0f));
        }
    }

    @Test
    void persistentZeroReadsFailAsDecodeError() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Files.write(root.resolve("stalled.wav"), new byte[0]);
        AudioInputStream stream = new ScriptedZeroReadAudioInputStream(new byte[0], 0, true);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new FixedAudioStreamFactory(stream), MusicLibrary.scan(root))) {
            engine.apply(PlaybackState.playing("stalled", "stalled.wav", 1.0f, false, 0, 0));

            PcmMixerEngine.AudioPlaybackException exception = assertTimeoutPreemptively(
                    Duration.ofSeconds(2),
                    () -> assertThrows(
                            PcmMixerEngine.AudioPlaybackException.class,
                            () -> engine.renderFrames(2, 1.0f)
                    )
            );
            assertEquals(AudioFailure.Kind.DECODE, exception.failure().kind());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }

    @Test
    void oggTemporaryZeroReadsDoNotAddRenderedBlocks() throws Exception {
        Path root = tempDir.resolve("music");
        Files.createDirectories(root);
        Path source = root.resolve("test.ogg");
        InputStream fixture = getClass().getResourceAsStream("/datura/areamusic/audio/test.ogg");
        assertNotNull(fixture);
        try (fixture) {
            Files.copy(fixture, source);
        }

        DecodedFrameCount decodeResult;
        try (AudioInputStream decoded = new AudioStreamFactory().open(source)) {
            decodeResult = countDecodedFrames(decoded);
        }
        assertTrue(decodeResult.frames() > 0);
        assertTrue(decodeResult.zeroReads() > 0, "test.ogg must exercise temporary zero-byte reads");
        long expectedBlocks = (decodeResult.frames() + 1023) / 1024;
        int renderedBlocks = 0;
        int renderGuard = Math.toIntExact(expectedBlocks + 64);

        try (PcmMixerEngine engine = new PcmMixerEngine(
                new AudioStreamFactory(), MusicLibrary.scan(root))) {
            engine.apply(PlaybackState.playing("ogg", "test.ogg", 1.0f, false, 0, 0));
            while (engine.hasTracks() && renderedBlocks < renderGuard) {
                engine.renderFrames(1024, 1.0f);
                renderedBlocks++;
            }
            assertFalse(engine.hasTracks(), "Mixer did not reach the end of test.ogg within the render guard");
        }

        assertEquals(expectedBlocks, renderedBlocks);
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

    private static DecodedFrameCount countDecodedFrames(AudioInputStream stream) throws IOException {
        byte[] buffer = new byte[8192];
        long decodedBytes = 0;
        int consecutiveZeroReads = 0;
        int zeroReads = 0;
        while (true) {
            int read = stream.read(buffer);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                consecutiveZeroReads++;
                zeroReads++;
                if (consecutiveZeroReads > 64) {
                    throw new IOException("Decoder exceeded 64 consecutive zero-byte reads");
                }
                continue;
            }
            consecutiveZeroReads = 0;
            decodedBytes += read;
        }
        assertEquals(0, decodedBytes % stream.getFormat().getFrameSize());
        return new DecodedFrameCount(decodedBytes / stream.getFormat().getFrameSize(), zeroReads);
    }

    private static int firstLeftSample(byte[] pcm) {
        return leftSample(pcm, 0);
    }

    private static int leftSample(byte[] pcm, int frame) {
        return PcmMath.readLittleEndian(pcm, frame * AudioStreamFactory.MIX_FORMAT.getFrameSize());
    }

    private record DecodedFrameCount(long frames, int zeroReads) {
    }

    private static final class FixedAudioStreamFactory extends AudioStreamFactory {
        private final AudioInputStream stream;

        private FixedAudioStreamFactory(AudioInputStream stream) {
            this.stream = stream;
        }

        @Override
        public AudioInputStream open(Path path) {
            return stream;
        }
    }

    private static final class ScriptedZeroReadAudioInputStream extends AudioInputStream {
        private int zeroReadsRemaining;
        private final boolean zeroForever;

        private ScriptedZeroReadAudioInputStream(byte[] pcm, int zeroReadsBeforeData, boolean zeroForever) {
            super(
                    new ByteArrayInputStream(pcm),
                    AudioStreamFactory.MIX_FORMAT,
                    pcm.length / AudioStreamFactory.MIX_FORMAT.getFrameSize()
            );
            this.zeroReadsRemaining = zeroReadsBeforeData;
            this.zeroForever = zeroForever;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted while simulating zero-byte reads");
            }
            if (zeroForever) {
                return 0;
            }
            if (zeroReadsRemaining > 0) {
                zeroReadsRemaining--;
                return 0;
            }
            return super.read(buffer, offset, length);
        }
    }
}
