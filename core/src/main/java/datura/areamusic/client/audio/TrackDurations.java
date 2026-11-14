package datura.areamusic.client.audio;

import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * How long tracks are, for drawing a timeline. Compressed formats do not say, so the first
 * question about a file decodes it once in the background; until that is done the answer is
 * "not known yet". Asking is cheap and safe to do every frame.
 */
public final class TrackDurations {
    private static final int UNREADABLE = -1;

    private final AudioStreamFactory streamFactory;
    private final Executor background;
    /** Milliseconds per file; absent while unknown, {@link #UNREADABLE} when it cannot be read. */
    private final Map<Path, Integer> known = new ConcurrentHashMap<>();
    private final Map<Path, Boolean> measuring = new ConcurrentHashMap<>();

    public TrackDurations(AudioStreamFactory streamFactory, Executor background) {
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        this.background = Objects.requireNonNull(background, "background");
    }

    /** The length in milliseconds, or empty while it is being measured or cannot be read. */
    public OptionalInt millis(Path file) {
        Path key = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Integer millis = known.get(key);
        if (millis != null) {
            return millis == UNREADABLE ? OptionalInt.empty() : OptionalInt.of(millis);
        }
        if (measuring.putIfAbsent(key, Boolean.TRUE) == null) {
            background.execute(() -> {
                known.put(key, measure(key));
                measuring.remove(key);
            });
        }
        return OptionalInt.empty();
    }

    /** Forgets everything, for when files may have changed. */
    public void clear() {
        known.clear();
    }

    private int measure(Path file) {
        try (AudioInputStream stream = streamFactory.open(file)) {
            long frames = stream.getFrameLength();
            if (frames == AudioSystem.NOT_SPECIFIED) {
                frames = countFrames(stream);
            }
            long millis = frames * 1000L / AudioStreamFactory.SAMPLE_RATE;
            return millis <= 0 || millis > Integer.MAX_VALUE ? UNREADABLE : (int) millis;
        } catch (Exception exception) {
            return UNREADABLE;
        }
    }

    private static long countFrames(AudioInputStream stream) throws java.io.IOException {
        int frameSize = AudioStreamFactory.MIX_FORMAT.getFrameSize();
        byte[] buffer = new byte[frameSize * 4096];
        long bytes = 0;
        int zeroReads = 0;
        while (true) {
            int read = stream.read(buffer, 0, buffer.length);
            if (read < 0) {
                return bytes / frameSize;
            }
            if (read == 0 && ++zeroReads > 64) {
                throw new java.io.IOException("Decoder stalled");
            }
            if (read > 0) {
                zeroReads = 0;
                bytes += read;
            }
        }
    }
}
