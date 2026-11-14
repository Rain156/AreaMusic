package datura.areamusic.net;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.edit.AreaEditService;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.EditResult;
import datura.areamusic.edit.RejectReason;
import datura.areamusic.editor.EditorModel;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.sync.IncomingTransfer;
import datura.areamusic.sync.MusicFingerprinter;
import datura.areamusic.sync.UploadFinisher;
import datura.areamusic.ui.EditorPanel;
import datura.areamusic.ui.PanelHost;
import datura.areamusic.ui.PanelUi;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Runs the real server and client halves against each other through the real codec. */
class SyncLoopbackTest {
    private static final AreaPosition A = new AreaPosition(0, 60, 0);
    private static final AreaPosition B = new AreaPosition(10, 80, 10);

    @TempDir
    Path tempDir;
    private Server server;

    @BeforeEach
    void setUp() throws Exception {
        server = new Server(tempDir.resolve("server"), ServerSync.Settings.DEFAULT);
    }

    @Test
    void joiningDownloadsNeededMusicIntoTheCache() throws Exception {
        byte[] day = server.addMusic("day.ogg", 100_000);
        server.addMusic("unused.ogg", 50_000);
        server.rescan();
        server.edits.apply(new EditOp.Put(area("village", "day.ogg")));
        server.sync.areaChanged(new EditOp.Put(area("village", "day.ogg")), server.edits.revision());
        Client client = server.join("alice", false);

        pump(1);
        assertTrue(client.sync.awaiting("day.ogg"));
        OptionalInt partial = client.sync.downloadPercent(List.of("day.ogg"));
        pump(20);

        assertTrue(partial.isPresent() && partial.getAsInt() < 100, partial.toString());
        assertEquals(Set.of("day.ogg"), client.library.ids());
        Path stored = client.library.find("day.ogg").orElseThrow();
        assertTrue(stored.startsWith(client.cacheRoot), stored.toString());
        assertArrayEquals(day, Files.readAllBytes(stored));
        assertFalse(client.sync.awaiting("day.ogg"));
        assertEquals(OptionalInt.empty(), client.sync.downloadPercent(List.of("day.ogg")));
        assertEquals(1, client.sentOfType(SyncMessage.MusicRequest.class));
    }

    @Test
    void scanningTheLocalFolderMidDownloadDoesNotLoseTheDownload() throws Exception {
        byte[] day = server.addMusic("day.ogg", 2_000_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client client = server.join("alice", false);
        pump(2);
        assertTrue(client.sync.awaiting("day.ogg"));

        // The scan of the player's own folder finishing a moment after joining, as it does the
        // first time the game is started with the mod.
        client.scanLocal();
        pump(40);

        assertArrayEquals(day, Files.readAllBytes(client.library.find("day.ogg").orElseThrow()));
        assertFalse(client.sync.awaiting("day.ogg"));
        assertEquals(1, client.sentOfType(SyncMessage.MusicRequest.class));
    }

    @Test
    void aChangedManifestMidDownloadDoesNotLoseTheDownload() throws Exception {
        byte[] day = server.addMusic("day.ogg", 2_000_000);
        byte[] night = server.addMusic("night.ogg", 50_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client client = server.join("alice", false);
        pump(2);
        assertTrue(client.sync.awaiting("day.ogg"));

        server.put(area("cave", "night.ogg"));
        pump(40);

        assertArrayEquals(day, Files.readAllBytes(client.library.find("day.ogg").orElseThrow()));
        assertArrayEquals(night, Files.readAllBytes(client.library.find("night.ogg").orElseThrow()));
        assertEquals(2, client.sentOfType(SyncMessage.MusicRequest.class));
    }

    @Test
    void aTrackDroppedAndWantedAgainMidDownloadStillArrives() throws Exception {
        byte[] day = server.addMusic("day.ogg", 2_000_000);
        server.addMusic("night.ogg", 50_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client client = server.join("alice", false);
        pump(2);
        assertTrue(client.sync.awaiting("day.ogg"));

        // The area switches to another track and back while the first one is still coming in.
        server.put(area("village", "night.ogg"));
        pump(1);
        assertFalse(client.sync.awaiting("day.ogg"));
        server.put(area("village", "day.ogg"));
        pump(60);

        assertArrayEquals(day, Files.readAllBytes(client.library.find("day.ogg").orElseThrow()));
        assertFalse(client.sync.awaiting("day.ogg"));
    }

    @Test
    void aDownloadTheServerStartsOverIsStartedOverHereToo() throws Exception {
        byte[] day = server.addMusic("day.ogg", 2_000_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client client = server.join("alice", false);
        pump(2);
        String sha256 = client.lastReceived(SyncMessage.MusicChunk.class).sha256();

        // A second request for the file being sent makes the server begin again from byte zero,
        // while this client has already taken in the first part.
        server.sync.onMessage("alice", new SyncMessage.MusicRequest(sha256));
        pump(40);

        assertArrayEquals(day, Files.readAllBytes(client.library.find("day.ogg").orElseThrow()));
    }

    @Test
    void matchingLocalFileIsUsedWithoutDownloading() throws Exception {
        byte[] day = server.addMusic("village/day.ogg", 40_000);
        server.rescan();
        server.put(area("village", "village/day.ogg"));
        Client client = server.newClient("alice", false);
        Files.createDirectories(client.libraryRoot.resolve("village"));
        Files.write(client.libraryRoot.resolve("village/day.ogg"), day);
        client.scanLocal();
        server.connect(client);

        pump(5);

        assertEquals(
                client.libraryRoot.resolve("village/day.ogg"),
                client.library.find("village/day.ogg").orElseThrow());
        assertEquals(0, client.sentOfType(SyncMessage.MusicRequest.class));
    }

    @Test
    void localFileWithOtherContentIsReplacedByTheServersVersion() throws Exception {
        byte[] day = server.addMusic("day.ogg", 40_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client client = server.newClient("alice", false);
        Files.write(client.libraryRoot.resolve("day.ogg"), new byte[40_000]);
        Files.write(client.libraryRoot.resolve("mine.ogg"), new byte[10]);
        client.scanLocal();
        server.connect(client);

        pump(1);
        assertEquals(Set.of("mine.ogg"), client.library.ids());
        pump(10);

        assertArrayEquals(day, Files.readAllBytes(client.library.find("day.ogg").orElseThrow()));
        assertEquals(Set.of("day.ogg", "mine.ogg"), client.library.ids());
    }

    @Test
    void newAreaMakesEveryClientFetchItsMusic() throws Exception {
        server.addMusic("day.ogg", 20_000);
        byte[] night = server.addMusic("night.ogg", 20_000);
        server.rescan();
        Client client = server.join("alice", false);
        pump(3);
        assertEquals(Set.of(), client.library.ids());

        server.put(area("cave", "night.ogg"));
        pump(10);

        assertArrayEquals(night, Files.readAllBytes(client.library.find("night.ogg").orElseThrow()));
    }

    @Test
    void editorsSeeEachOthersChangesAndCanUndo() throws Exception {
        server.addMusic("day.ogg", 1_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client alice = server.join("alice", true);
        Client bob = server.join("bob", true);
        EditorModel aliceEditor = alice.sync.editor();
        EditorModel bobEditor = bob.sync.editor();
        aliceEditor.open();
        bobEditor.open();
        pump(3);

        assertTrue(aliceEditor.ready());
        assertEquals(List.of("village"), ids(aliceEditor));
        assertEquals(List.of("day.ogg"), aliceEditor.musicIds());

        aliceEditor.startDraft("minecraft:overworld");
        aliceEditor.draftCorner(1, A);
        assertFalse(aliceEditor.draft().complete());
        aliceEditor.draftCorner(2, B);
        String created = aliceEditor.completeDraft("day.ogg").orElseThrow();
        assertEquals("day", created);
        assertEquals("day", aliceEditor.selectedId());
        assertNull(aliceEditor.draft());
        pump(3);

        assertEquals(List.of("day", "village"), ids(bobEditor));
        assertEquals(List.of("day", "village"), server.edits.areas().stream().map(AreaDefinition::id).toList());

        aliceEditor.apply(area -> AreaEdits.withVolume(area.withDisplayName("村庄"), 0.5f));
        pump(3);
        assertEquals("村庄", bobEditor.find("day").orElseThrow().displayName());
        assertEquals(0.5f, AreaEdits.volumeOf(server.edits.find("day").orElseThrow()));

        aliceEditor.undo();
        pump(3);
        assertEquals("", bobEditor.find("day").orElseThrow().displayName());
        aliceEditor.undo();
        pump(3);
        assertEquals(List.of("village"), ids(bobEditor));
        assertFalse(aliceEditor.canUndo());
        aliceEditor.redo();
        aliceEditor.redo();
        pump(3);
        assertEquals("村庄", server.edits.find("day").orElseThrow().displayName());
        assertFalse(aliceEditor.canRedo());

        bobEditor.select("day");
        bobEditor.deleteSelected();
        pump(3);
        assertEquals(List.of("village"), ids(aliceEditor));
        assertNull(aliceEditor.selectedId());
    }

    @Test
    void rejectedEditIsRolledBackAndReported() throws Exception {
        server.addMusic("day.ogg", 1_000);
        server.rescan();
        Client alice = server.join("alice", true);
        EditorModel editor = alice.sync.editor();
        editor.open();
        pump(3);

        editor.draftAround("minecraft:overworld", new AreaPosition(0, 64, 0), EditorModel.DEFAULT_AROUND_RADIUS);
        editor.completeDraft("not-in-library.ogg");
        assertEquals(1, editor.areas().size());
        pump(3);

        assertEquals(List.of(), editor.areas());
        assertEquals(RejectReason.UNKNOWN_MUSIC, editor.lastError().orElseThrow().reason());
        assertEquals("not-in-library.ogg", editor.lastError().orElseThrow().detail());
        assertFalse(editor.canUndo());
        assertEquals(List.of(), server.edits.areas());
    }

    @Test
    void playersWithoutPermissionCannotOpenOrEdit() throws Exception {
        server.addMusic("day.ogg", 1_000);
        server.rescan();
        Client mallory = server.join("mallory", false);
        pump(2);

        assertFalse(mallory.sync.canEdit());
        mallory.sync.editor().open();
        mallory.send(new SyncMessage.EditRequest(1, new EditOp.Put(area("hack", "day.ogg"))));
        mallory.send(new SyncMessage.UploadBegin(1, "x.ogg", 10, "0".repeat(64)));
        pump(3);

        assertFalse(mallory.sync.editor().ready());
        assertEquals(List.of(), server.edits.areas());
        SyncMessage.EditReply reply = mallory.lastReceived(SyncMessage.EditReply.class);
        assertEquals(RejectReason.NOT_ALLOWED, reply.reason());
        assertEquals("not_allowed", mallory.lastReceived(SyncMessage.UploadReply.class).detail());
    }

    @Test
    void uploadInstallsTheFileAndIsDeduplicatedNextTime() throws Exception {
        Client alice = server.join("alice", true);
        alice.sync.editor().open();
        pump(2);
        Path source = alice.root.resolve("My Song.OGG");
        try (InputStream input = SyncLoopbackTest.class.getResourceAsStream("/datura/areamusic/audio/test.ogg")) {
            assertNotNull(input);
            Files.copy(input, source);
        }
        List<String> outcomes = new ArrayList<>();

        alice.sync.upload(source, (musicId, rejection) -> outcomes.add(musicId + "|" + rejection));
        assertEquals(1, alice.sync.uploads().size());
        pump(30);

        assertEquals(List.of("My Song.ogg|null"), outcomes);
        assertEquals(List.of(), alice.sync.uploads());
        assertArrayEquals(Files.readAllBytes(source), Files.readAllBytes(server.musicRoot.resolve("My Song.ogg")));
        assertEquals(List.of("My Song.ogg"), alice.sync.editor().musicIds());
        try (var leftovers = Files.list(server.uploadTemp)) {
            assertEquals(0, leftovers.count());
        }

        int chunksBefore = alice.sentOfType(SyncMessage.UploadChunk.class);
        alice.sync.upload(source, (musicId, rejection) -> outcomes.add(musicId + "|" + rejection));
        pump(5);
        assertEquals(List.of("My Song.ogg|null", "My Song.ogg|null"), outcomes);
        assertEquals(chunksBefore, alice.sentOfType(SyncMessage.UploadChunk.class));

        alice.sync.editor().draftAround("minecraft:overworld", A, 4);
        alice.sync.editor().completeDraft("My Song.ogg");
        pump(5);
        assertEquals(0, alice.sentOfType(SyncMessage.MusicRequest.class));
        assertTrue(alice.library.find("My Song.ogg").orElseThrow().startsWith(alice.cacheRoot));
    }

    @Test
    void uploadsThatAreNotPlayableAudioAreRefusedBeforeSending() throws Exception {
        Client alice = server.join("alice", true);
        pump(2);
        Path fake = alice.root.resolve("fake.ogg");
        Files.writeString(fake, "definitely not audio");
        Path wrongType = alice.root.resolve("notes.txt");
        Files.writeString(wrongType, "text");
        List<String> outcomes = new ArrayList<>();

        alice.sync.upload(fake, (musicId, rejection) -> outcomes.add(String.valueOf(rejection)));
        alice.sync.upload(wrongType, (musicId, rejection) -> outcomes.add(String.valueOf(rejection)));
        alice.sync.upload(alice.root.resolve("missing.ogg"), (musicId, rejection) -> outcomes.add(String.valueOf(rejection)));
        pump(3);

        assertEquals(List.of("not_audio", "bad_name", "read_failed"), outcomes);
        assertEquals(0, alice.sentOfType(SyncMessage.UploadBegin.class));
    }

    @Test
    void serverEnforcesTheUploadSizeLimit() throws Exception {
        server = new Server(tempDir.resolve("small"), new ServerSync.Settings(true, 1_000, 1L << 30, 128 * 1024));
        Client alice = server.join("alice", true);
        pump(2);

        alice.send(new SyncMessage.UploadBegin(1, "big.ogg", 1_001, "0".repeat(64)));
        alice.send(new SyncMessage.UploadBegin(2, "../evil.exe", 10, "0".repeat(64)));
        pump(2);

        List<SyncMessage.UploadReply> replies = alice.received(SyncMessage.UploadReply.class);
        assertEquals(List.of("too_large", "bad_name"), replies.stream().map(SyncMessage.UploadReply::detail).toList());
        assertEquals(1_000, alice.sync.maxUploadBytes());
    }

    @Test
    void withDistributionOffNothingIsOfferedOrServed() throws Exception {
        server = new Server(tempDir.resolve("off"), new ServerSync.Settings(false, 1_000, 1L << 30, 128 * 1024));
        server.addMusic("day.ogg", 1_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client alice = server.newClient("alice", true);
        Files.write(alice.libraryRoot.resolve("day.ogg"), new byte[5]);
        alice.scanLocal();
        server.connect(alice);
        pump(2);

        assertFalse(alice.sync.distributeMusic());
        assertEquals(alice.libraryRoot.resolve("day.ogg"), alice.library.find("day.ogg").orElseThrow());
        alice.send(new SyncMessage.MusicRequest(server.sync.manifest().find("day.ogg").orElseThrow().sha256()));
        pump(2);
        assertEquals(1, alice.received(SyncMessage.MusicUnavailable.class).size());
    }

    @Test
    void leavingMidDownloadLeavesNoPartialFiles() throws Exception {
        server.addMusic("day.ogg", 2_000_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        Client alice = server.join("alice", false);
        pump(2);
        assertTrue(alice.sync.awaiting("day.ogg"));

        alice.sync.disconnect();
        server.leave(alice);

        if (Files.isDirectory(alice.cacheRoot)) {
            try (var files = Files.list(alice.cacheRoot)) {
                assertEquals(0, files.count());
            }
        }
        assertFalse(alice.sync.serverPresent());
    }

    private void pump(int ticks) {
        for (int tick = 0; tick < ticks; tick++) {
            server.sync.tick();
            boolean moved = true;
            while (moved) {
                moved = false;
                for (Client client : server.clients.values()) {
                    client.sync.tick();
                    moved |= client.deliver();
                }
            }
        }
    }

    private static List<String> ids(EditorModel editor) {
        return editor.areas().stream().map(AreaDefinition::id).toList();
    }

    @Test
    void aTypedNameStaysWithItsAreaWhenTheSameClickSelectsAnotherOrClosesThePanel() throws Exception {
        server.addMusic("day.ogg", 1_000);
        server.rescan();
        server.put(area("village", "day.ogg"));
        server.put(area("cave", "day.ogg"));
        Client alice = server.join("alice", true);
        EditorModel editor = alice.sync.editor();
        editor.open();
        pump(3);
        editor.select("village");
        ScriptedHost host = new ScriptedHost();
        EditorPanel panel = new EditorPanel(alice.sync, host);

        // The click on the other row is also what commits the name being typed.
        ScriptedUi ui = new ScriptedUi();
        ui.typedName = "Plaza";
        ui.clickedRow = "area:cave";
        panel.build(ui);
        pump(3);

        assertEquals("Plaza", editor.find("village").orElseThrow().displayName());
        assertEquals("", editor.find("cave").orElseThrow().displayName());
        assertEquals("cave", editor.selectedId());

        ui = new ScriptedUi();
        ui.typedName = "Mine";
        ui.close = true;
        panel.build(ui);
        pump(3);

        assertEquals("Mine", editor.find("cave").orElseThrow().displayName());
        assertEquals(1, host.finished);
    }

    private static final class ScriptedHost implements PanelHost {
        private int finished;

        @Override
        public String text(String key, Object... args) {
            return key;
        }

        @Override
        public String playerDimension() {
            return "minecraft:overworld";
        }

        @Override
        public AreaPosition playerPosition() {
            return A;
        }

        @Override
        public void teleportTo(String areaId) {
        }

        @Override
        public void pickCornersInWorld() {
        }

        @Override
        public void finishEditing() {
            finished++;
        }

        @Override
        public boolean crosshairPicking() {
            return true;
        }
    }

    /** A panel front end that reports one frame's worth of scripted input and draws nothing. */
    private static final class ScriptedUi implements PanelUi {
        private String typedName;
        private String clickedRow;
        private boolean close;

        @Override
        public int header(String title, List<String> actions) {
            return close ? actions.size() - 1 : -1;
        }

        @Override
        public String textInput(String id, String label, String value, int maxLength) {
            return id.equals("name") ? typedName : null;
        }

        @Override
        public boolean listRow(String id, String label, String meta, boolean selected, int swatch) {
            return id.equals(clickedRow);
        }

        @Override
        public void section(String text) {
        }

        @Override
        public void label(String text) {
        }

        @Override
        public void hint(String text) {
        }

        @Override
        public void notice(String text, boolean error) {
        }

        @Override
        public void space() {
        }

        @Override
        public void sameRow() {
        }

        @Override
        public boolean primaryButton(String id, String label) {
            return false;
        }

        @Override
        public boolean button(String id, String label) {
            return false;
        }

        @Override
        public boolean quietButton(String id, String label) {
            return false;
        }

        @Override
        public boolean dangerButton(String id, String label) {
            return false;
        }

        @Override
        public void steps(List<String> labels, int current) {
        }

        @Override
        public Integer intInput(String id, String label, int value, int min, int max) {
            return null;
        }

        @Override
        public Float slider(String id, String label, float value, float min, float max, String format) {
            return null;
        }

        @Override
        public Boolean checkbox(String id, String label, boolean value) {
            return null;
        }

        @Override
        public Integer choice(String id, List<String> options, int selected) {
            return null;
        }

        @Override
        public String combo(String id, String label, List<String> options, String selected) {
            return null;
        }

        @Override
        public Integer swatches(String id, List<Integer> colors, int selected) {
            return null;
        }

        @Override
        public boolean collapsing(String id, String label, boolean openByDefault) {
            return false;
        }

        @Override
        public void progress(String label, float fraction) {
        }
    }

    private static AreaDefinition area(String id, String musicId) {
        return AreaEdits.single(id, "minecraft:overworld", A, B, musicId);
    }

    private static final class Server implements ServerSync.Host<String> {
        private final Path musicRoot;
        private final Path uploadTemp;
        private final Path clientsRoot;
        private final Map<String, Client> clients = new HashMap<>();
        private final MusicFingerprinter fingerprinter = new MusicFingerprinter();
        private final AreaEditService edits;
        private final ServerSync<String> sync;
        private MusicLibrary library;

        private Server(Path root, ServerSync.Settings settings) throws IOException {
            musicRoot = Files.createDirectories(root.resolve("areamusic"));
            uploadTemp = Files.createDirectories(root.resolve("uploads"));
            clientsRoot = Files.createDirectories(root.resolve("clients"));
            library = MusicLibrary.scan(musicRoot);
            edits = new AreaEditService(List.of(), 0, id -> library.contains(id));
            sync = new ServerSync<>(this, settings);
        }

        private byte[] addMusic(String musicId, int size) throws IOException {
            byte[] content = new byte[size];
            new Random(musicId.hashCode()).nextBytes(content);
            Path file = musicRoot.resolve(musicId);
            Files.createDirectories(file.getParent());
            Files.write(file, content);
            return content;
        }

        private void rescan() throws IOException {
            library = MusicLibrary.scan(musicRoot);
            sync.reloaded(fingerprinter.fingerprint(library));
        }

        private void put(AreaDefinition area) {
            EditResult result = applyEdit("console", new EditOp.Put(area));
            assertTrue(result.applied(), String.valueOf(result.reason()));
        }

        private Client newClient(String name, boolean canEdit) throws IOException {
            return new Client(this, name, canEdit, Files.createDirectories(clientsRoot.resolve(name)));
        }

        private void connect(Client client) {
            clients.put(client.name, client);
            sync.onJoin(client.name);
        }

        private Client join(String name, boolean canEdit) throws IOException {
            Client client = newClient(name, canEdit);
            client.scanLocal();
            connect(client);
            return client;
        }

        private void leave(Client client) {
            sync.onLeave(client.name);
            clients.remove(client.name);
        }

        @Override
        public void send(String player, SyncMessage.Clientbound message) {
            Client client = clients.get(player);
            if (client != null) {
                client.inbox.add(SyncCodec.encode(message));
            }
        }

        @Override
        public boolean canEdit(String player) {
            Client client = clients.get(player);
            return client != null && client.canEdit;
        }

        @Override
        public EditResult applyEdit(String player, EditOp op) {
            EditResult result = edits.apply(op);
            if (result.applied() && result.changed()) {
                sync.areaChanged(op, result.revision());
            }
            return result;
        }

        @Override
        public List<AreaDefinition> areas() {
            return edits.areas();
        }

        @Override
        public long revision() {
            return edits.revision();
        }

        @Override
        public MusicLibrary library() {
            return library;
        }

        @Override
        public Path newUploadTemporaryPath(String extension) {
            return uploadTemp.resolve("." + UUID.randomUUID() + "." + extension);
        }

        @Override
        public void finishUpload(String player, int uploadId, IncomingTransfer transfer, String musicIdHint) {
            try {
                Set<String> taken = new java.util.HashSet<>();
                library.ids().forEach(id -> taken.add(id.toLowerCase(Locale.ROOT)));
                String musicId = UploadFinisher.install(transfer, musicIdHint, musicRoot, taken::contains);
                rescan();
                sync.uploadFinished(player, uploadId, musicId, null);
            } catch (IllegalArgumentException exception) {
                sync.uploadFinished(player, uploadId, null, exception.getMessage());
            } catch (IOException exception) {
                throw new UncheckedIOException(exception);
            }
        }
    }

    private static final class Client implements ClientSync.Host {
        private final Server server;
        private final String name;
        private final boolean canEdit;
        private final Path root;
        private final Path libraryRoot;
        private final Path cacheRoot;
        private final Deque<byte[]> inbox = new ArrayDeque<>();
        private final List<SyncMessage.Serverbound> sent = new ArrayList<>();
        private final List<SyncMessage.Clientbound> received = new ArrayList<>();
        private final ClientSync sync;
        private MusicLibrary library;

        private Client(Server server, String name, boolean canEdit, Path root) throws IOException {
            this.server = server;
            this.name = name;
            this.canEdit = canEdit;
            this.root = root;
            libraryRoot = Files.createDirectories(root.resolve("areamusic"));
            cacheRoot = root.resolve("areamusic-cache");
            library = MusicLibrary.empty(libraryRoot);
            sync = new ClientSync(this, libraryRoot, cacheRoot, Runnable::run, Runnable::run);
        }

        private void scanLocal() throws IOException {
            library = sync.setLocalLibrary(MusicLibrary.scan(libraryRoot));
        }

        private boolean deliver() {
            boolean any = false;
            for (byte[] data = inbox.poll(); data != null; data = inbox.poll()) {
                SyncMessage.Clientbound message = SyncCodec.decodeClientbound(data);
                received.add(message);
                sync.onMessage(message);
                any = true;
            }
            return any;
        }

        private int sentOfType(Class<?> type) {
            return (int) sent.stream().filter(type::isInstance).count();
        }

        private <T> List<T> received(Class<T> type) {
            return received.stream().filter(type::isInstance).map(type::cast).toList();
        }

        private <T> T lastReceived(Class<T> type) {
            List<T> all = received(type);
            return all.get(all.size() - 1);
        }

        @Override
        public void send(SyncMessage.Serverbound message) {
            sent.add(message);
            byte[] data = SyncCodec.encode(message);
            assertTrue(data.length <= SyncCodec.MAX_SERVERBOUND_BYTES, "serverbound payload too large");
            server.sync.onMessage(name, SyncCodec.decodeServerbound(data));
        }

        @Override
        public void libraryChanged(MusicLibrary resolved) {
            library = resolved;
        }
    }
}
