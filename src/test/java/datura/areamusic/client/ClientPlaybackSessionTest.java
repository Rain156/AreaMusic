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
    void unversionedLibraryScanReappliesTheCurrentCompatibleUpdate() {
        MusicLibrary oldLibrary = MusicLibrary.empty(tempDir.resolve("old"));
        MusicLibrary scannedLibrary = MusicLibrary.empty(tempDir.resolve("scanned"));
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(oldLibrary, ignored -> mixer);
        PlaybackState state = playing("area", "track.mp3", 1.0f, true, 0, 0);
        session.connect();
        session.apply(5L, state);
        mixer.appliedStates.clear();
        mixer.events.clear();

        session.finishReload(scannedLibrary);

        assertEquals(List.of(new AppliedState(5L, state)), mixer.appliedStates);
        assertEquals(List.of("library", "apply:5"), mixer.events);
        session.close();
    }

    @Test
    void completedReloadWaitsForPlaybackStateCompatibleWithTheNewLibrary() {
        MusicLibrary oldLibrary = MusicLibrary.empty(tempDir.resolve("old"));
        MusicLibrary newLibrary = MusicLibrary.empty(tempDir.resolve("new"));
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(oldLibrary, ignored -> mixer);
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState newState = playing("new", "new.mp3", 1.0f, true, 0, 0);
        session.connect();
        session.apply(5L, oldState);
        mixer.appliedStates.clear();
        mixer.events.clear();

        assertTrue(session.beginReload(6L));
        session.finishReload(newLibrary);

        assertTrue(mixer.libraryUpdated);
        assertTrue(mixer.appliedStates.isEmpty());
        assertEquals(List.of("library"), mixer.events);

        session.apply(6L, newState);

        assertEquals(List.of(new AppliedState(6L, newState)), mixer.appliedStates);
        assertEquals(List.of("library", "apply:6"), mixer.events);
        session.close();
    }

    @Test
    void newerCompletedReloadDoesNotPromoteAnIntermediateHeldUpdate() {
        MusicLibrary oldLibrary = MusicLibrary.empty(tempDir.resolve("old"));
        MusicLibrary newestLibrary = MusicLibrary.empty(tempDir.resolve("newest"));
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(oldLibrary, ignored -> mixer);
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState intermediateState = playing(
                "intermediate", "intermediate.mp3", 1.0f, true, 0, 0
        );
        PlaybackState newestState = playing("newest", "newest.mp3", 1.0f, true, 0, 0);
        session.connect();
        session.apply(5L, oldState);
        mixer.appliedStates.clear();
        mixer.events.clear();

        assertTrue(session.beginReload(6L));
        session.apply(6L, intermediateState);
        assertTrue(session.beginReload(7L));
        session.finishReload(newestLibrary);

        assertTrue(mixer.libraryUpdated);
        assertTrue(mixer.appliedStates.isEmpty());
        assertEquals(List.of("library"), mixer.events);

        session.apply(7L, newestState);

        assertEquals(List.of(new AppliedState(7L, newestState)), mixer.appliedStates);
        assertEquals(List.of("library", "apply:7"), mixer.events);
        session.close();
    }

    @Test
    void newerFailedReloadKeepsTheLastUpdateCompatibleWithTheInstalledLibrary() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("old")), ignored -> mixer
        );
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState intermediateState = playing(
                "intermediate", "intermediate.mp3", 1.0f, true, 0, 0
        );
        PlaybackState newestState = playing("newest", "newest.mp3", 1.0f, true, 0, 0);
        session.connect();
        session.apply(5L, oldState);
        mixer.appliedStates.clear();
        mixer.events.clear();

        assertTrue(session.beginReload(6L));
        session.apply(6L, intermediateState);
        assertTrue(session.beginReload(7L));
        session.failReload();

        assertFalse(mixer.libraryUpdated);
        assertEquals(List.of(new AppliedState(5L, oldState)), mixer.appliedStates);
        assertEquals(List.of("apply:5"), mixer.events);

        session.apply(7L, newestState);

        assertEquals(List.of(
                new AppliedState(5L, oldState),
                new AppliedState(7L, newestState)
        ), mixer.appliedStates);
        assertEquals(List.of("apply:5", "apply:7"), mixer.events);
        session.close();
    }

    @Test
    void failedReloadReappliesOnlyARevisionPairedWithItsPlaybackState() {
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("music")), ignored -> mixer
        );
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState newState = playing("new", "new.mp3", 1.0f, true, 0, 0);
        session.connect();
        session.apply(5L, oldState);
        mixer.appliedStates.clear();
        mixer.events.clear();

        assertTrue(session.beginReload(6L));
        session.failReload();

        assertFalse(mixer.libraryUpdated);
        assertEquals(List.of(new AppliedState(5L, oldState)), mixer.appliedStates);
        assertEquals(List.of("apply:5"), mixer.events);

        session.apply(6L, newState);

        assertEquals(List.of(
                new AppliedState(5L, oldState),
                new AppliedState(6L, newState)
        ), mixer.appliedStates);
        session.close();
    }

    @Test
    void connectDuringReloadAppliesOnlyTheLatestCompletePlaybackUpdate() {
        MusicLibrary newLibrary = MusicLibrary.empty(tempDir.resolve("new"));
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("old")), ignored -> mixer
        );
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState newState = playing("new", "new.mp3", 1.0f, true, 0, 0);

        session.apply(5L, oldState);
        assertTrue(session.beginReload(6L));
        session.connect();

        assertEquals(List.of(new AppliedState(5L, oldState)), mixer.appliedStates);

        session.apply(6L, newState);

        assertEquals(List.of(new AppliedState(5L, oldState)), mixer.appliedStates);

        session.finishReload(newLibrary);

        assertEquals(List.of(
                new AppliedState(5L, oldState),
                new AppliedState(6L, newState)
        ), mixer.appliedStates);
        assertEquals(List.of("apply:5", "library", "apply:6"), mixer.events);
        session.close();
    }

    @Test
    void connectDuringReloadDefersThePendingRevisionUntilTheLibraryUpdate() {
        MusicLibrary newLibrary = MusicLibrary.empty(tempDir.resolve("new"));
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(
                MusicLibrary.empty(tempDir.resolve("old")), ignored -> mixer
        );
        PlaybackState newState = playing("new", "new.mp3", 1.0f, true, 0, 0);

        assertTrue(session.beginReload(6L));
        session.apply(6L, newState);
        session.connect();

        assertTrue(mixer.appliedStates.isEmpty());
        assertTrue(mixer.events.isEmpty());

        session.finishReload(newLibrary);

        assertEquals(List.of(new AppliedState(6L, newState)), mixer.appliedStates);
        assertEquals(List.of("library", "apply:6"), mixer.events);
        session.close();
    }

    @Test
    void connectAfterNewerCompletedReloadDoesNotApplyOlderOrIntermediateUpdates() {
        MusicLibrary oldLibrary = MusicLibrary.empty(tempDir.resolve("old"));
        MusicLibrary newestLibrary = MusicLibrary.empty(tempDir.resolve("newest"));
        List<MusicLibrary> connectedLibraries = new ArrayList<>();
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(oldLibrary, library -> {
            connectedLibraries.add(library);
            return mixer;
        });
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState intermediateState = playing(
                "intermediate", "intermediate.mp3", 1.0f, true, 0, 0
        );
        PlaybackState newestState = playing("newest", "newest.mp3", 1.0f, true, 0, 0);

        session.apply(5L, oldState);
        assertTrue(session.beginReload(6L));
        session.apply(6L, intermediateState);
        assertTrue(session.beginReload(7L));
        session.finishReload(newestLibrary);
        session.connect();

        assertEquals(List.of(newestLibrary), connectedLibraries);
        assertTrue(mixer.appliedStates.isEmpty());

        session.apply(7L, newestState);

        assertEquals(List.of(new AppliedState(7L, newestState)), mixer.appliedStates);
        session.close();
    }

    @Test
    void connectAfterNewerFailedReloadUsesOnlyTheInstalledLibraryFallback() {
        MusicLibrary oldLibrary = MusicLibrary.empty(tempDir.resolve("old"));
        List<MusicLibrary> connectedLibraries = new ArrayList<>();
        FakeMixer mixer = new FakeMixer();
        ClientPlaybackSession session = new ClientPlaybackSession(oldLibrary, library -> {
            connectedLibraries.add(library);
            return mixer;
        });
        PlaybackState oldState = playing("old", "old.mp3", 1.0f, true, 0, 0);
        PlaybackState intermediateState = playing(
                "intermediate", "intermediate.mp3", 1.0f, true, 0, 0
        );
        PlaybackState newestState = playing("newest", "newest.mp3", 1.0f, true, 0, 0);

        session.apply(5L, oldState);
        assertTrue(session.beginReload(6L));
        session.apply(6L, intermediateState);
        assertTrue(session.beginReload(7L));
        session.failReload();
        session.connect();

        assertEquals(List.of(oldLibrary), connectedLibraries);
        assertEquals(List.of(new AppliedState(5L, oldState)), mixer.appliedStates);

        session.apply(7L, newestState);

        assertEquals(List.of(
                new AppliedState(5L, oldState),
                new AppliedState(7L, newestState)
        ), mixer.appliedStates);
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
