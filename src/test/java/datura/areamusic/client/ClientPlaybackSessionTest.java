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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
        assertTrue(mixers.get(0).appliedStates.isEmpty());

        session.disconnect();
        assertTrue(mixers.get(0).closed);

        session.connect();
        assertEquals(2, mixers.size());
        assertTrue(mixers.get(1).started);
        assertTrue(mixers.get(1).appliedStates.isEmpty());
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
        mixer.events.clear();

        assertTrue(session.beginReload(7));
        session.apply(7, state);

        assertTrue(mixer.appliedStates.isEmpty());
        assertFalse(mixer.libraryUpdated);

        session.finishReload(newLibrary);

        assertTrue(mixer.libraryUpdated);
        assertEquals(List.of(new AppliedState(7L, state)), mixer.appliedStates);
        assertEquals(List.of("library", "apply:7"), mixer.events);
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

    @Test
    void connectWithoutAnyRevisionStartsTheMixerWithoutApplyingState() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> mixer
        );

        session.connect();

        assertTrue(mixer.started);
        assertTrue(mixer.appliedStates.isEmpty());
        session.close();
    }

    @Test
    void stateReceivedBeforeConnectIsAppliedWithItsRevisionAfterStart() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> mixer
        );
        PlaybackState state = playing("area", "track.mp3", 1.0f, true, 0, 0);

        session.apply(8L, state);
        session.connect();

        assertEquals(List.of(new AppliedState(8L, state)), mixer.appliedStates);
        session.close();
    }

    @Test
    void stalePlaybackRevisionIsIgnoredWithoutReachingTheMixer() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> mixer
        );
        PlaybackState latest = playing("latest", "latest.mp3", 1.0f, true, 0, 0);
        PlaybackState stale = playing("stale", "stale.mp3", 1.0f, true, 0, 0);
        session.connect();
        mixer.appliedStates.clear();

        session.apply(9L, latest);
        session.apply(8L, stale);

        assertEquals(List.of(new AppliedState(9L, latest)), mixer.appliedStates);
        session.close();
    }

    @Test
    void failedReloadReappliesTheLatestRevisionWithoutUpdatingTheLibrary() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> mixer
        );
        PlaybackState state = playing("area", "track.mp3", 1.0f, true, 0, 0);
        session.connect();
        mixer.appliedStates.clear();
        mixer.events.clear();

        assertTrue(session.beginReload(9L));
        session.apply(9L, state);
        session.failReload();

        assertFalse(mixer.libraryUpdated);
        assertEquals(List.of(new AppliedState(9L, state)), mixer.appliedStates);
        assertEquals(List.of("apply:9"), mixer.events);
        session.close();
    }

    @Test
    void disconnectDropsRevisionStateBeforeTheNextMixerIsCreated() {
        List<FakeMixer> mixers = new ArrayList<>();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> {
                    FakeMixer mixer = new FakeMixer();
                    mixers.add(mixer);
                    return mixer;
                }
        );
        session.apply(12L, playing("area", "track.mp3", 1.0f, true, 0, 0));
        session.connect();
        session.disconnect();

        session.connect();

        assertEquals(2, mixers.size());
        assertTrue(mixers.get(1).appliedStates.isEmpty());
        session.close();
    }

    @Test
    void rejectedNullStateDoesNotAdvanceTheLatestRevisionStamp() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> mixer
        );
        PlaybackState valid = playing("area", "track.mp3", 1.0f, true, 0, 0);

        session.apply(5L, valid);
        assertThrows(NullPointerException.class, () -> session.apply(6L, null));
        session.connect();

        assertEquals(List.of(new AppliedState(5L, valid)), mixer.appliedStates);
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
        private final List<AppliedState> appliedStates = new ArrayList<>();
        private final List<String> events = new ArrayList<>();

        @Override
        public void start() {
            started = true;
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            appliedStates.add(new AppliedState(revision, state));
            events.add("apply:" + revision);
        }

        @Override
        public void updateMusicLibrary(MusicLibrary musicLibrary) {
            libraryUpdated = true;
            events.add("library");
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

    private record AppliedState(long revision, PlaybackState state) {
    }
}
