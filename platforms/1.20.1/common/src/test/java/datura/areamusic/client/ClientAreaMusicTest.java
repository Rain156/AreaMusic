package datura.areamusic.client;

import datura.areamusic.client.audio.AudioFailure;
import datura.areamusic.client.audio.ClientAudioMixer;
import datura.areamusic.client.audio.PcmAudioMixer;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.network.chat.Component;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientAreaMusicTest {
    @TempDir
    Path tempDir;

    @Test
    void implementsTheLoaderNeutralPacketHandlerContract() {
        assertTrue(ClientPacketHandler.class.isAssignableFrom(ClientAreaMusic.class));
    }

    @Test
    void scanMigratesTheLegacyDirectoryAndReturnsTheLowercaseRoot() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("track.ogg"), "fixture");

        MusicLibrary library = ClientAreaMusic.scan(gameDirectory);

        assertEquals(gameDirectory.toAbsolutePath().normalize().resolve("areamusic"), library.root());
        assertEquals(Set.of("track.ogg"), library.ids());
        assertEquals(Set.of("areamusic"), exactChildNames(gameDirectory));
    }

    @Test
    void scanReportsMigrationFailureWithoutCreatingAFallbackDirectory() throws Exception {
        Path gameDirectory = tempDir.resolve("game");
        Files.createDirectories(gameDirectory);
        Path legacy = gameDirectory.resolve("AreaMusic");
        Files.writeString(legacy, "not a directory");

        RuntimeException failure = assertThrows(RuntimeException.class,
                () -> ClientAreaMusic.scan(gameDirectory));

        assertInstanceOf(IOException.class, failure.getCause());
        assertEquals("not a directory", Files.readString(legacy));
        assertEquals(Set.of("AreaMusic"), exactChildNames(gameDirectory));
    }

    @Test
    void tickUsesInjectedIndependentVolumeAndClientState() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        client.masterVolume = 0.5f;
        client.paused = true;
        FakeMixer mixer = new FakeMixer();
        ClientAreaMusic service = service(
                tempDir.resolve("game"),
                () -> 0.4,
                client,
                background,
                ignored -> MusicLibrary.empty(tempDir.resolve("scan")),
                (library, listener) -> mixer
        );

        service.connect();
        service.tick();

        assertEquals(1, mixer.startCount);
        assertEquals(0.2f, mixer.masterGain);
        assertTrue(mixer.paused);
    }

    @Test
    void normalizesTheInjectedGameDirectoryBeforeScanning() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        AtomicReference<Path> scannedDirectory = new AtomicReference<>();
        Path injected = tempDir.resolve("parent").resolve("..").resolve("game");
        service(
                injected,
                () -> 1.0,
                client,
                background,
                directory -> {
                    scannedDirectory.set(directory);
                    return MusicLibrary.empty(tempDir.resolve("scan"));
                },
                (library, listener) -> new FakeMixer()
        );

        background.runAll();

        assertEquals(injected.toAbsolutePath().normalize(), scannedDirectory.get());
    }

    @Test
    void newerScanWinsEvenWhenItsBackgroundTaskCompletesFirst() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        FakeMixer mixer = new FakeMixer();
        MusicLibrary newer = MusicLibrary.empty(tempDir.resolve("newer"));
        MusicLibrary stale = MusicLibrary.empty(tempDir.resolve("stale"));
        Deque<MusicLibrary> results = new ArrayDeque<>(List.of(newer, stale));
        ClientAreaMusic service = service(
                tempDir.resolve("game"),
                () -> 1.0,
                client,
                background,
                ignored -> results.removeFirst(),
                (library, listener) -> mixer
        );
        service.connect();

        background.runAt(1);
        client.mainThread.runAll();
        background.runAt(0);
        client.mainThread.runAll();

        assertEquals(List.of(newer), mixer.updatedLibraries);
    }

    @Test
    void shutdownIsIdempotentAndInvalidatesQueuedScans() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        FakeMixer mixer = new FakeMixer();
        ClientAreaMusic service = service(
                tempDir.resolve("game"),
                () -> 1.0,
                client,
                background,
                ignored -> MusicLibrary.empty(tempDir.resolve("late")),
                (library, listener) -> mixer
        );
        service.connect();

        service.shutdown();
        service.shutdown();
        background.runAll();
        client.mainThread.runAll();

        assertEquals(1, mixer.closeCount);
        assertEquals(List.of(), mixer.updatedLibraries);
    }

    @Test
    void disconnectInvalidatesQueuedScans() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        FakeMixer mixer = new FakeMixer();
        ClientAreaMusic service = service(
                tempDir.resolve("game"),
                () -> 1.0,
                client,
                background,
                ignored -> MusicLibrary.empty(tempDir.resolve("late")),
                (library, listener) -> mixer
        );
        service.connect();

        service.disconnect();
        background.runAll();
        client.mainThread.runAll();

        assertEquals(1, mixer.closeCount);
        assertEquals(List.of(), mixer.updatedLibraries);
    }

    @Test
    void audioFailuresAreDeduplicatedAndMarshalledToTheClientThread() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        FakeMixer mixer = new FakeMixer();
        AtomicReference<PcmAudioMixer.ErrorListener> errors = new AtomicReference<>();
        ClientAreaMusic service = service(
                tempDir.resolve("game"),
                () -> 1.0,
                client,
                background,
                ignored -> MusicLibrary.empty(tempDir.resolve("scan")),
                (library, listener) -> {
                    errors.set(listener);
                    return mixer;
                }
        );
        service.connect();
        AudioFailure failure = new AudioFailure(
                AudioFailure.Kind.DECODE,
                "track.ogg",
                new IOException("broken")
        );

        errors.get().onError(failure);
        errors.get().onError(failure);

        assertEquals(List.of(), client.messages);
        client.mainThread.runAll();
        assertEquals(1, client.messages.size());
    }

    @Test
    void queuedAudioFailureStillReachesThePlayerWhenShutdownRunsBeforeTheClientTask() {
        ManualExecutor background = new ManualExecutor();
        FakeClientRuntime client = new FakeClientRuntime();
        FakeMixer mixer = new FakeMixer();
        AtomicReference<PcmAudioMixer.ErrorListener> errors = new AtomicReference<>();
        ClientAreaMusic service = service(
                tempDir.resolve("game"),
                () -> 1.0,
                client,
                background,
                ignored -> MusicLibrary.empty(tempDir.resolve("scan")),
                (library, listener) -> {
                    errors.set(listener);
                    return mixer;
                }
        );
        service.connect();
        errors.get().onError(new AudioFailure(
                AudioFailure.Kind.DEVICE,
                "",
                new IOException("unavailable")
        ));

        service.shutdown();
        client.mainThread.runAll();

        assertEquals(1, client.messages.size());
    }

    private static ClientAreaMusic service(
            Path gameDirectory,
            java.util.function.DoubleSupplier volume,
            ClientAreaMusic.ClientRuntime client,
            Executor scanExecutor,
            ClientAreaMusic.LibraryScanner scanner,
            ClientAreaMusic.AudioMixerFactory mixerFactory
    ) {
        return new ClientAreaMusic(
                gameDirectory,
                volume,
                client,
                scanExecutor,
                scanner,
                mixerFactory
        );
    }

    private static Set<String> exactChildNames(Path directory) throws IOException {
        try (Stream<Path> children = Files.list(directory)) {
            return children
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toUnmodifiableSet());
        }
    }

    private static final class ManualExecutor implements Executor {
        private final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        private void runAt(int index) {
            tasks.remove(index).run();
        }

        private void runAll() {
            while (!tasks.isEmpty()) {
                runAt(0);
            }
        }
    }

    private static final class FakeClientRuntime implements ClientAreaMusic.ClientRuntime {
        private final ManualExecutor mainThread = new ManualExecutor();
        private final List<Component> messages = new ArrayList<>();
        private float masterVolume = 1.0f;
        private boolean paused;

        @Override
        public void execute(Runnable task) {
            mainThread.execute(task);
        }

        @Override
        public float masterVolume() {
            return masterVolume;
        }

        @Override
        public boolean paused() {
            return paused;
        }

        @Override
        public void displayClientMessage(Component message) {
            messages.add(message);
        }
    }

    private static final class FakeMixer implements ClientAudioMixer {
        private final List<MusicLibrary> updatedLibraries = new ArrayList<>();
        private int startCount;
        private int closeCount;
        private float masterGain = 1.0f;
        private boolean paused;

        @Override
        public void start() {
            startCount++;
        }

        @Override
        public void apply(long revision, PlaybackState state) {
        }

        @Override
        public void updateMusicLibrary(MusicLibrary musicLibrary) {
            updatedLibraries.add(musicLibrary);
        }

        @Override
        public void updateMusicLibraryAndApply(
                MusicLibrary musicLibrary,
                long revision,
                PlaybackState state
        ) {
            updatedLibraries.add(musicLibrary);
        }

        @Override
        public void setMasterGain(float gain) {
            masterGain = gain;
        }

        @Override
        public void setPaused(boolean paused) {
            this.paused = paused;
        }

        @Override
        public void close() {
            closeCount++;
        }
    }
}
