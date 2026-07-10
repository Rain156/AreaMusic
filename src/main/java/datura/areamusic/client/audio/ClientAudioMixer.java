package datura.areamusic.client.audio;

import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;

public interface ClientAudioMixer extends AutoCloseable {
    void start();

    void apply(PlaybackState state);

    void updateMusicLibrary(MusicLibrary musicLibrary);

    void setMasterGain(float gain);

    void setPaused(boolean paused);

    @Override
    void close();
}
