package datura.areamusic.client.audio;

import javax.sound.sampled.AudioInputStream;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

interface AudioStreamPreparation extends AutoCloseable {
    CompletableFuture<AudioInputStream> prepare(Path path, long frameOffset);

    @Override
    void close();
}
