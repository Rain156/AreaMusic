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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmAudioMixerTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersSubmittedPlaybackStateOnTheOwnedAudioThread() throws Exception {
        Path root = tempDir.resolve("music");
        writeConstantWav(root.resolve("thread.wav"), (short) 1200, 4096);
        FakeOutput output = new FakeOutput();
        List<Throwable> errors = new CopyOnWriteArrayList<>();
        PcmAudioMixer mixer = new PcmAudioMixer(
                MusicLibrary.scan(root),
                () -> output,
                (code, error) -> errors.add(error)
        );

        mixer.start();
        mixer.apply(PlaybackState.playing("area", "thread.wav", 1.0f, true, 0, 0));

        assertTrue(output.firstWrite.await(2, TimeUnit.SECONDS));
        assertEquals(1200, PcmMath.readLittleEndian(output.firstBlock.get(), 0));
        assertTrue(errors.isEmpty());
        mixer.close();
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
                new ByteArrayInputStream(pcm), AudioStreamFactory.MIX_FORMAT, frames)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
    }

    private static final class FakeOutput implements PcmAudioMixer.AudioOutput {
        private final CountDownLatch firstWrite = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicReference<byte[]> firstBlock = new AtomicReference<>();

        @Override
        public void start() {
        }

        @Override
        public void stop() {
        }

        @Override
        public void write(byte[] pcm) throws InterruptedException {
            if (firstBlock.compareAndSet(null, pcm.clone())) {
                firstWrite.countDown();
            }
            closed.await(2, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
