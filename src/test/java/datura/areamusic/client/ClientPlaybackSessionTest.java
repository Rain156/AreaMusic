package datura.areamusic.client;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientPlaybackSessionTest {
    @TempDir
    Path tempDir;

    @Test
    void disconnectClosesMixerAndReconnectCreatesANewOne() {
        MusicLibrary library = MusicLibrary.empty(tempDir.resolve("music"));
        List<FakeMixer> mixers = new ArrayList<>();
        ClientPlaybackSession session = new ClientPlaybackSession(library, ignored -> {
            FakeMixer mixer = new FakeMixer();
            mixers.add(mixer);
            return mixer;
        });

        session.connect();
        assertTrue(mixers.get(0).started);

        session.disconnect();
        assertTrue(mixers.get(0).closed);

        session.connect();
        assertEquals(2, mixers.size());
        assertTrue(mixers.get(1).started);
        session.close();
    }

    @Test
    void playbackForReloadRevisionWaitsForTheNewLibrary() {
        MusicLibrary oldLibrary = MusicLibrary.empty(tempDir.resolve("old"));
        MusicLibrary newLibrary = MusicLibrary.empty(tempDir.resolve("new"));
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(oldLibrary, ignored -> mixer);
        PlaybackState state = playing("area", "new.mp3", 1.0f, true, 0, 0);
        session.connect();
        mixer.appliedStates.clear();

        assertTrue(session.beginReload(7));
        session.apply(7, state);

        assertTrue(mixer.appliedStates.isEmpty());
        assertFalse(mixer.libraryUpdated);

        session.finishReload(newLibrary);

        assertTrue(mixer.libraryUpdated);
        assertEquals(List.of(state), mixer.appliedStates);
        session.close();
    }

    @Test
    void failedReloadKeepsTheCurrentLibraryForTheNextConnection() {
        MusicLibrary currentLibrary = MusicLibrary.empty(tempDir.resolve("current"));
        List<MusicLibrary> connectedLibraries = new ArrayList<>();
        ClientPlaybackSession session = new ClientPlaybackSession(currentLibrary, library -> {
            connectedLibraries.add(library);
            return new FakeMixer();
        });
        session.connect();

        assertTrue(session.beginReload(7));
        session.failReload();
        session.disconnect();
        session.connect();

        assertEquals(List.of(currentLibrary, currentLibrary), connectedLibraries);
        session.close();
    }

    private static PlaybackState playing(
            String areaId,
            String musicId,
            float volume,
            boolean loop,
            int fadeInMs,
            int fadeOutMs
    ) {
        return PlaybackState.playing(
                areaId,
                List.of(new AreaTrackDefinition(musicId, 0, volume, loop, fadeInMs, fadeOutMs)),
                false
        );
    }

    private static final class FakeMixer implements ClientAudioMixer {
        private boolean started;
        private boolean closed;
        private boolean libraryUpdated;
        private final List<PlaybackState> appliedStates = new ArrayList<>();

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void apply(PlaybackState state) {
            appliedStates.add(state);
        }

        @Override
        public void updateMusicLibrary(MusicLibrary musicLibrary) {
            libraryUpdated = true;
        }

        @Override
        public void setMasterGain(float gain) {
        }

        @Override
        public void setPaused(boolean paused) {
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
