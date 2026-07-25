package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

import java.util.List;

/**
 * Mutable PCM playback state that is driven by an owning audio thread.
 *
 * <p>Mutable implementations are confined to a single owner thread. Asynchronous workers may
 * complete preparation futures, but attaching their results and every other engine-state mutation
 * must happen only through calls made by that owner thread.</p>
 */
public interface PcmPlaybackEngine extends AutoCloseable {
    void setMusicLibrary(MusicLibrary musicLibrary);

    void apply(long revision, PlaybackState state);

    byte[] renderFrames(int frameCount, float masterGain);

    List<AudioFailure> drainFailures();

    boolean hasWork();

    PlaybackState currentState();

    @Override
    void close();
}
