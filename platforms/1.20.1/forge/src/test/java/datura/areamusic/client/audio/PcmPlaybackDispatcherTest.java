package datura.areamusic.client.audio;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PcmPlaybackDispatcherTest {
    @TempDir
    Path tempDir;

    @Test
    void existingEnginesImplementTheSharedPlaybackContract() {
        assertTrue(PcmPlaybackEngine.class.isAssignableFrom(PcmMixerEngine.class));
        assertTrue(PcmPlaybackEngine.class.isAssignableFrom(PlaylistPcmEngine.class));
    }

    @Test
    void routesEachModeAndStoppedStateToBothDelegatesWithTheSameRevision() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);
        PlaybackState parallelState = parallelState("parallel");
        PlaybackState playlistState = playlistState("playlist");

        dispatcher.apply(7L, parallelState);

        assertEquals(List.of(new ApplyCall(7L, parallelState)), parallel.applications);
        assertEquals(
                List.of(new ApplyCall(7L, PlaybackState.stopped())),
                playlist.applications
        );
        assertEquals(parallelState, dispatcher.currentState());

        parallel.applications.clear();
        playlist.applications.clear();
        dispatcher.apply(8L, playlistState);

        assertEquals(
                List.of(new ApplyCall(8L, PlaybackState.stopped())),
                parallel.applications
        );
        assertEquals(List.of(new ApplyCall(8L, playlistState)), playlist.applications);
        assertEquals(playlistState, dispatcher.currentState());

        parallel.applications.clear();
        playlist.applications.clear();
        dispatcher.apply(9L, PlaybackState.stopped());

        ApplyCall stopped = new ApplyCall(9L, PlaybackState.stopped());
        assertEquals(List.of(stopped), parallel.applications);
        assertEquals(List.of(stopped), playlist.applications);
        assertEquals(PlaybackState.stopped(), dispatcher.currentState());
    }

    @Test
    void rejectsInvalidApplyBeforeMutatingEitherDelegate() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertThrows(
                IllegalArgumentException.class,
                () -> dispatcher.apply(-1L, parallelState("area"))
        );
        assertThrows(NullPointerException.class, () -> dispatcher.apply(1L, null));

        assertTrue(parallel.applications.isEmpty());
        assertTrue(playlist.applications.isEmpty());
        assertEquals(PlaybackState.stopped(), dispatcher.currentState());
    }

    @Test
    void crossModeSwitchRendersBothWorkingDelegatesAndSaturatesSignedSamples() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        parallel.work = true;
        playlist.work = true;
        parallel.rendered = pcm(30_000, -30_000);
        playlist.rendered = pcm(10_000, -10_000);
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        dispatcher.apply(11L, parallelState("parallel"));
        dispatcher.apply(12L, playlistState("playlist"));
        byte[] rendered = dispatcher.renderFrames(1, 1.0f);

        assertEquals(1, parallel.renderCalls);
        assertEquals(1, playlist.renderCalls);
        assertEquals(32_767, PcmMath.readLittleEndian(rendered, 0));
        assertEquals(-32_768, PcmMath.readLittleEndian(rendered, 2));
    }

    @Test
    void returnsExactSilentAndZeroFrameBuffersWithoutRenderingIdleDelegates() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertArrayEquals(new byte[12], dispatcher.renderFrames(3, 0.5f));
        assertEquals(0, parallel.renderCalls);
        assertEquals(0, playlist.renderCalls);

        parallel.work = true;
        parallel.rendered = new byte[0];
        assertArrayEquals(new byte[0], dispatcher.renderFrames(0, 1.0f));
        assertEquals(1, parallel.renderCalls);
        assertEquals(0, playlist.renderCalls);
    }

    @Test
    void validatesRenderArgumentsBeforeCallingEitherDelegate() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        parallel.work = true;
        playlist.work = true;
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertThrows(IllegalArgumentException.class, () -> dispatcher.renderFrames(-1, 1.0f));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.renderFrames(1, -0.1f));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.renderFrames(1, 1.1f));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.renderFrames(1, Float.NaN));
        assertThrows(
                ArithmeticException.class,
                () -> dispatcher.renderFrames(Integer.MAX_VALUE, 1.0f)
        );

        assertEquals(0, parallel.hasWorkCalls);
        assertEquals(0, playlist.hasWorkCalls);
        assertEquals(0, parallel.renderCalls);
        assertEquals(0, playlist.renderCalls);
    }

    @Test
    void rejectsWrongSizedOrUnalignedDelegateOutput() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        parallel.work = true;
        parallel.rendered = new byte[8];
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertThrows(IllegalStateException.class, () -> dispatcher.renderFrames(1, 1.0f));

        FakeEngine alignedParallel = new FakeEngine();
        FakeEngine unalignedPlaylist = new FakeEngine();
        unalignedPlaylist.work = true;
        unalignedPlaylist.rendered = new byte[6];
        PcmPlaybackDispatcher second = new PcmPlaybackDispatcher(
                alignedParallel, unalignedPlaylist
        );

        assertThrows(IllegalStateException.class, () -> second.renderFrames(1, 1.0f));
    }

    @Test
    void forwardsLibraryToBothAfterValidatingIt() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);
        MusicLibrary library = MusicLibrary.empty(tempDir.resolve("music"));

        assertThrows(NullPointerException.class, () -> dispatcher.setMusicLibrary(null));
        assertEquals(0, parallel.libraryCalls);
        assertEquals(0, playlist.libraryCalls);

        dispatcher.setMusicLibrary(library);

        assertSame(library, parallel.musicLibrary);
        assertSame(library, playlist.musicLibrary);
        assertEquals(1, parallel.libraryCalls);
        assertEquals(1, playlist.libraryCalls);
    }

    @Test
    void drainsFailuresInParallelThenPlaylistOrder() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        AudioFailure first = failure("parallel-one.wav");
        AudioFailure second = failure("parallel-two.wav");
        AudioFailure third = failure("playlist.wav");
        parallel.failures.addAll(List.of(first, second));
        playlist.failures.add(third);
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertEquals(List.of(first, second, third), dispatcher.drainFailures());
        assertTrue(dispatcher.drainFailures().isEmpty());
        assertEquals(2, parallel.drainCalls);
        assertEquals(2, playlist.drainCalls);
    }

    @Test
    void reportsWorkWhenEitherDelegateStillHasWork() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertFalse(dispatcher.hasWork());
        playlist.work = true;
        assertTrue(dispatcher.hasWork());
        playlist.work = false;
        parallel.work = true;
        assertTrue(dispatcher.hasWork());
    }

    @Test
    void closeIsIdempotentAndStillClosesPlaylistWhenParallelCloseFails() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        RuntimeException failure = new IllegalStateException("parallel close failed");
        parallel.closeFailure = failure;
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);

        assertSame(failure, assertThrows(RuntimeException.class, dispatcher::close));
        assertEquals(1, parallel.closeCalls);
        assertEquals(1, playlist.closeCalls);

        dispatcher.close();

        assertEquals(1, parallel.closeCalls);
        assertEquals(1, playlist.closeCalls);
    }

    @Test
    void rejectsEveryOperationAfterClose() {
        FakeEngine parallel = new FakeEngine();
        FakeEngine playlist = new FakeEngine();
        PcmPlaybackDispatcher dispatcher = new PcmPlaybackDispatcher(parallel, playlist);
        dispatcher.close();

        assertThrows(
                IllegalStateException.class,
                () -> dispatcher.setMusicLibrary(MusicLibrary.empty(tempDir.resolve("music")))
        );
        assertThrows(
                IllegalStateException.class,
                () -> dispatcher.apply(1L, PlaybackState.stopped())
        );
        assertThrows(IllegalStateException.class, () -> dispatcher.renderFrames(0, 1.0f));
        assertThrows(IllegalStateException.class, dispatcher::drainFailures);
        assertThrows(IllegalStateException.class, dispatcher::hasWork);
        assertThrows(IllegalStateException.class, dispatcher::currentState);
    }

    private static PlaybackState parallelState(String areaId) {
        return PlaybackState.playing(
                areaId,
                List.of(new AreaTrackDefinition("parallel.wav", 0, 1.0f, true, 0, 0)),
                false
        );
    }

    private static PlaybackState playlistState(String areaId) {
        return PlaybackState.playingPlaylistLoop(
                areaId, List.of("playlist.wav"), 1.0f, 0, 0, false
        );
    }

    private static AudioFailure failure(String musicId) {
        return new AudioFailure(
                AudioFailure.Kind.DECODE,
                musicId,
                new IllegalStateException(musicId)
        );
    }

    private static byte[] pcm(int... samples) {
        byte[] bytes = new byte[samples.length * 2];
        for (int index = 0; index < samples.length; index++) {
            PcmMath.writeLittleEndian(bytes, index * 2, samples[index]);
        }
        return bytes;
    }

    private record ApplyCall(long revision, PlaybackState state) {
    }

    private static final class FakeEngine implements PcmPlaybackEngine {
        private final List<ApplyCall> applications = new ArrayList<>();
        private final List<AudioFailure> failures = new ArrayList<>();
        private MusicLibrary musicLibrary;
        private byte[] rendered = new byte[0];
        private boolean work;
        private int libraryCalls;
        private int renderCalls;
        private int hasWorkCalls;
        private int drainCalls;
        private int closeCalls;
        private RuntimeException closeFailure;
        private PlaybackState currentState = PlaybackState.stopped();

        @Override
        public void setMusicLibrary(MusicLibrary musicLibrary) {
            this.musicLibrary = musicLibrary;
            libraryCalls++;
        }

        @Override
        public void apply(long revision, PlaybackState state) {
            applications.add(new ApplyCall(revision, state));
            currentState = state;
        }

        @Override
        public byte[] renderFrames(int frameCount, float masterGain) {
            renderCalls++;
            return rendered;
        }

        @Override
        public List<AudioFailure> drainFailures() {
            drainCalls++;
            List<AudioFailure> drained = List.copyOf(failures);
            failures.clear();
            return drained;
        }

        @Override
        public boolean hasWork() {
            hasWorkCalls++;
            return work;
        }

        @Override
        public PlaybackState currentState() {
            return currentState;
        }

        @Override
        public void close() {
            closeCalls++;
            if (closeFailure != null) {
                throw closeFailure;
            }
        }
    }
}
