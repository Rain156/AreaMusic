package datura.areamusic.net;

import datura.areamusic.editor.EditorModel;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.net.SyncMessage.AreaDelta;
import datura.areamusic.net.SyncMessage.EditReply;
import datura.areamusic.net.SyncMessage.EditorSnapshot;
import datura.areamusic.net.SyncMessage.Hello;
import datura.areamusic.net.SyncMessage.LibraryList;
import datura.areamusic.net.SyncMessage.Manifest;
import datura.areamusic.net.SyncMessage.MusicChunk;
import datura.areamusic.net.SyncMessage.MusicRequest;
import datura.areamusic.net.SyncMessage.MusicUnavailable;
import datura.areamusic.net.SyncMessage.UploadBegin;
import datura.areamusic.net.SyncMessage.UploadCancel;
import datura.areamusic.net.SyncMessage.UploadChunk;
import datura.areamusic.net.SyncMessage.UploadReply;
import datura.areamusic.sync.AudioProbe;
import datura.areamusic.sync.IncomingTransfer;
import datura.areamusic.sync.MusicCache;
import datura.areamusic.sync.MusicFingerprint;
import datura.areamusic.sync.OutgoingTransfer;
import datura.areamusic.sync.Sha256;
import datura.areamusic.sync.TransferException;
import datura.areamusic.sync.TransferLimits;
import datura.areamusic.sync.UploadNames;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Executor;

/**
 * Client half of the editor and music distribution. All public methods are client-thread
 * confined; hashing and probing run on {@code io} and come back through {@code clientThread}.
 */
public final class ClientSync {
    private static final int UPLOAD_CHUNKS_PER_TICK = 4;

    private final Host host;
    private final MusicCache cache;
    private final Path libraryRoot;
    private final Executor io;
    private final Executor clientThread;
    private final EditorModel editor;

    private boolean serverPresent;
    private boolean canEdit;
    private boolean distributeMusic;
    private long maxUploadBytes;
    private long generation;
    private MusicLibrary localLibrary;
    private List<MusicFingerprint> needed = List.of();
    private final Map<String, Path> resolved = new HashMap<>();
    private final Set<String> failed = new HashSet<>();
    private final Deque<MusicFingerprint> queue = new ArrayDeque<>();
    private Download active;
    private Map<String, Path> delivered = Map.of();
    private boolean swept;
    private int nextUploadId = 1;
    private final Map<Integer, Upload> uploads = new LinkedHashMap<>();

    public ClientSync(
            Host host, Path libraryRoot, Path cacheRoot, Executor io, Executor clientThread
    ) {
        this.host = Objects.requireNonNull(host, "host");
        this.libraryRoot = Objects.requireNonNull(libraryRoot, "libraryRoot").toAbsolutePath().normalize();
        this.io = Objects.requireNonNull(io, "io");
        this.clientThread = Objects.requireNonNull(clientThread, "clientThread");
        cache = new MusicCache(this.libraryRoot, cacheRoot);
        localLibrary = MusicLibrary.empty(this.libraryRoot);
        editor = new EditorModel(host::send);
    }

    public EditorModel editor() {
        return editor;
    }

    /** Whether the server runs a version of the mod that speaks this protocol. */
    public boolean serverPresent() {
        return serverPresent;
    }

    public boolean canEdit() {
        return serverPresent && canEdit;
    }

    public boolean distributeMusic() {
        return serverPresent && distributeMusic;
    }

    public long maxUploadBytes() {
        return maxUploadBytes;
    }

    public void disconnect() {
        generation++;
        serverPresent = false;
        canEdit = false;
        distributeMusic = false;
        needed = List.of();
        resolved.clear();
        failed.clear();
        queue.clear();
        closeActive();
        for (Upload upload : new ArrayList<>(uploads.values())) {
            failUpload(upload, "disconnected");
        }
        swept = false;
        editor.reset();
    }

    /**
     * Takes the result of scanning the player's own music folder and returns the library to play
     * from right now. If server-pinned tracks need re-checking, {@link Host#libraryChanged} follows.
     */
    public MusicLibrary setLocalLibrary(MusicLibrary library) {
        localLibrary = Objects.requireNonNull(library, "library");
        if (!needed.isEmpty()) {
            resolveNeeded();
        }
        MusicLibrary built = buildLibrary();
        delivered = files(built);
        return built;
    }

    public void onMessage(SyncMessage.Clientbound message) {
        if (message instanceof Hello hello) {
            serverPresent = true;
            canEdit = hello.canEdit();
            distributeMusic = hello.distributeMusic();
            maxUploadBytes = hello.maxUploadBytes();
            if (!canEdit && editor.isOpen()) {
                editor.reset();
            }
        } else if (message instanceof Manifest manifest) {
            needed = manifest.entries();
            failed.clear();
            resolveNeeded();
        } else if (message instanceof EditorSnapshot snapshot) {
            editor.onSnapshot(snapshot);
        } else if (message instanceof LibraryList list) {
            editor.onLibrary(list);
        } else if (message instanceof AreaDelta delta) {
            editor.onDelta(delta);
        } else if (message instanceof EditReply reply) {
            editor.onReply(reply);
        } else if (message instanceof MusicChunk chunk) {
            onChunk(chunk);
        } else if (message instanceof MusicUnavailable unavailable) {
            if (active != null && active.fingerprint.sha256().equals(unavailable.sha256())) {
                failed.add(unavailable.sha256());
                closeActive();
                startNextDownload();
            }
        } else if (message instanceof UploadReply reply) {
            onUploadReply(reply);
        }
    }

    /** Sends the next chunks of any running upload. Call once per client tick. */
    public void tick() {
        for (Upload upload : new ArrayList<>(uploads.values())) {
            if (upload.transfer == null) {
                continue;
            }
            try {
                for (int sent = 0; sent < UPLOAD_CHUNKS_PER_TICK && !upload.transfer.finished(); sent++) {
                    OutgoingTransfer.Chunk chunk = upload.transfer.next();
                    host.send(new UploadChunk(upload.id, chunk.offset(), chunk.data(), chunk.last()));
                }
                upload.sent = upload.transfer.offset();
            } catch (IOException exception) {
                host.send(new UploadCancel(upload.id));
                failUpload(upload, "read_failed");
            }
        }
    }

    /** Moves these tracks to the front of the download queue, e.g. the area the player is in. */
    public void prioritize(Collection<String> musicIds) {
        List<MusicFingerprint> urgent = new ArrayList<>();
        for (MusicFingerprint fingerprint : queue) {
            if (musicIds.contains(fingerprint.musicId())) {
                urgent.add(fingerprint);
            }
        }
        for (int index = urgent.size() - 1; index >= 0; index--) {
            queue.remove(urgent.get(index));
            queue.addFirst(urgent.get(index));
        }
    }

    /** True while the track is expected from the server but not here yet. */
    public boolean awaiting(String musicId) {
        for (MusicFingerprint fingerprint : needed) {
            if (fingerprint.musicId().equals(musicId)) {
                return !resolved.containsKey(fingerprint.sha256()) && !failed.contains(fingerprint.sha256());
            }
        }
        return false;
    }

    /** Download progress across these tracks, or empty when none of them is being awaited. */
    public OptionalInt downloadPercent(Collection<String> musicIds) {
        long total = 0;
        long done = 0;
        boolean awaitingAny = false;
        Set<String> counted = new HashSet<>();
        for (MusicFingerprint fingerprint : needed) {
            if (!musicIds.contains(fingerprint.musicId()) || !counted.add(fingerprint.sha256())) {
                continue;
            }
            total += fingerprint.size();
            if (resolved.containsKey(fingerprint.sha256())) {
                done += fingerprint.size();
            } else if (!failed.contains(fingerprint.sha256())) {
                awaitingAny = true;
                if (active != null && active.fingerprint.sha256().equals(fingerprint.sha256())) {
                    done += active.transfer.received();
                }
            }
        }
        if (!awaitingAny || total == 0) {
            return OptionalInt.empty();
        }
        return OptionalInt.of((int) Math.min(99, done * 100 / total));
    }

    /**
     * Uploads an audio file to the server's library. The listener is called on the client thread
     * exactly once with either a MusicID or a rejection code.
     */
    public int upload(Path file, UploadListener listener) {
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(listener, "listener");
        Upload upload = new Upload(nextUploadId++, file, listener);
        uploads.put(upload.id, upload);
        long limit = maxUploadBytes;
        long startedIn = generation;
        io.execute(() -> {
            String rejection = null;
            String sha256 = null;
            long size = 0;
            try {
                UploadNames.sanitize(file.getFileName().toString());
                size = Files.size(file);
                if (size <= 0 || size > limit) {
                    rejection = "too_large";
                } else if (!AudioProbe.isDecodable(file)) {
                    rejection = "not_audio";
                } else {
                    sha256 = Sha256.of(file);
                }
            } catch (IllegalArgumentException exception) {
                rejection = "bad_name";
            } catch (IOException exception) {
                rejection = "read_failed";
            }
            String finalRejection = rejection;
            String finalSha256 = sha256;
            long finalSize = size;
            clientThread.execute(() -> {
                if (startedIn != generation || !uploads.containsKey(upload.id)) {
                    return;
                }
                if (finalRejection != null) {
                    failUpload(upload, finalRejection);
                    return;
                }
                upload.size = finalSize;
                upload.sha256 = finalSha256;
                host.send(new UploadBegin(
                        upload.id, file.getFileName().toString(), finalSize, finalSha256));
            });
        });
        return upload.id;
    }

    /** Uploads in progress, oldest first. */
    public List<UploadView> uploads() {
        List<UploadView> views = new ArrayList<>(uploads.size());
        for (Upload upload : uploads.values()) {
            views.add(new UploadView(
                    upload.id, upload.file.getFileName().toString(),
                    upload.size <= 0 ? 0f : (float) upload.sent / upload.size));
        }
        return views;
    }

    private void onUploadReply(UploadReply reply) {
        Upload upload = uploads.get(reply.uploadId());
        if (upload == null) {
            return;
        }
        switch (reply.status()) {
            case ACCEPTED -> {
                try {
                    upload.transfer = new OutgoingTransfer(upload.file, TransferLimits.UPLOAD_CHUNK_BYTES);
                } catch (IOException exception) {
                    host.send(new UploadCancel(upload.id));
                    failUpload(upload, "read_failed");
                }
            }
            case REJECTED -> failUpload(upload, reply.detail());
            case COMPLETE -> {
                uploads.remove(upload.id);
                upload.closeTransfer();
                String musicId = reply.detail();
                MusicFingerprint fingerprint = new MusicFingerprint(musicId, upload.size, upload.sha256);
                long startedIn = generation;
                // Keep a copy under its hash so this client never downloads its own upload back.
                io.execute(() -> {
                    try {
                        Path cached = cache.cachePath(fingerprint);
                        if (!Files.exists(cached)) {
                            Files.createDirectories(cached.getParent());
                            Path temporary = cache.newTemporaryPath(fingerprint);
                            Files.copy(upload.file, temporary);
                            Files.move(temporary, cached);
                        }
                    } catch (IOException ignored) {
                        // Falling back to a normal download is fine.
                    }
                    clientThread.execute(() -> {
                        if (startedIn == generation) {
                            upload.listener.finished(musicId, null);
                        }
                    });
                });
            }
        }
    }

    private void failUpload(Upload upload, String rejection) {
        uploads.remove(upload.id);
        upload.closeTransfer();
        upload.listener.finished(null, rejection);
    }

    private void resolveNeeded() {
        long startedIn = ++generation;
        List<MusicFingerprint> wanted = needed;
        queue.clear();
        closeActive();
        boolean sweep = !swept;
        swept = true;
        io.execute(() -> {
            if (sweep) {
                try {
                    // Unfinished downloads from an earlier session; none is in use right now.
                    cache.sweepTemporaryFiles();
                } catch (IOException ignored) {
                    // Leftovers are harmless; they are never treated as cache hits.
                }
            }
            Map<String, Path> found = new HashMap<>();
            for (MusicFingerprint fingerprint : wanted) {
                if (found.containsKey(fingerprint.sha256())) {
                    continue;
                }
                try {
                    cache.find(fingerprint).ifPresent(path -> found.put(fingerprint.sha256(), path));
                } catch (IOException ignored) {
                    // Treated as missing; it will be downloaded.
                }
            }
            clientThread.execute(() -> {
                if (startedIn != generation) {
                    return;
                }
                resolved.clear();
                resolved.putAll(found);
                Set<String> queued = new HashSet<>();
                for (MusicFingerprint fingerprint : wanted) {
                    if (!resolved.containsKey(fingerprint.sha256()) && queued.add(fingerprint.sha256())) {
                        queue.add(fingerprint);
                    }
                }
                deliver();
                startNextDownload();
            });
        });
    }

    private void startNextDownload() {
        while (active == null && distributeMusic) {
            MusicFingerprint next = queue.poll();
            if (next == null) {
                return;
            }
            if (failed.contains(next.sha256()) || resolved.containsKey(next.sha256())) {
                continue;
            }
            try {
                active = new Download(next, new IncomingTransfer(
                        cache.newTemporaryPath(next), next.size(), next.sha256()));
                host.send(new MusicRequest(next.sha256()));
            } catch (IOException exception) {
                failed.add(next.sha256());
            }
        }
    }

    private void onChunk(MusicChunk chunk) {
        if (active == null || !active.fingerprint.sha256().equals(chunk.sha256())) {
            return;
        }
        MusicFingerprint fingerprint = active.fingerprint;
        try {
            active.transfer.accept(chunk.offset(), chunk.data(), chunk.last());
            if (!active.transfer.complete()) {
                return;
            }
            Path stored = active.transfer.moveTo(cache.cachePath(fingerprint));
            closeActive();
            resolved.put(fingerprint.sha256(), stored);
            deliver();
        } catch (TransferException | IOException exception) {
            failed.add(fingerprint.sha256());
            closeActive();
        }
        startNextDownload();
    }

    /** Local files, overridden by whatever the server's manifest pins for a MusicID. */
    private MusicLibrary buildLibrary() {
        Map<String, Path> files = new TreeMap<>();
        for (String musicId : localLibrary.ids()) {
            files.put(musicId, localLibrary.find(musicId).orElseThrow());
        }
        for (MusicFingerprint fingerprint : needed) {
            Path path = resolved.get(fingerprint.sha256());
            if (path != null) {
                files.put(fingerprint.musicId(), path);
            } else {
                files.remove(fingerprint.musicId());
            }
        }
        return MusicLibrary.of(libraryRoot, files);
    }

    /** Tells the host about the current library unless it is the one it already has. */
    private void deliver() {
        MusicLibrary built = buildLibrary();
        Map<String, Path> files = files(built);
        if (!files.equals(delivered)) {
            delivered = files;
            host.libraryChanged(built);
        }
    }

    private static Map<String, Path> files(MusicLibrary library) {
        Map<String, Path> files = new HashMap<>();
        for (String musicId : library.ids()) {
            files.put(musicId, library.find(musicId).orElseThrow());
        }
        return files;
    }

    private void closeActive() {
        if (active != null) {
            try {
                active.transfer.close();
            } catch (IOException ignored) {
                // The sweep on next start removes any leftover.
            }
            active = null;
        }
    }

    public interface Host {
        void send(SyncMessage.Serverbound message);

        /** The set of playable files changed after the fact, e.g. a download finished. */
        void libraryChanged(MusicLibrary library);
    }

    @FunctionalInterface
    public interface UploadListener {
        /** Exactly one argument is non-null. */
        void finished(String musicId, String rejection);
    }

    public record UploadView(int id, String fileName, float progress) {
    }

    private record Download(MusicFingerprint fingerprint, IncomingTransfer transfer) {
    }

    private static final class Upload {
        private final int id;
        private final Path file;
        private final UploadListener listener;
        private long size;
        private long sent;
        private String sha256;
        private OutgoingTransfer transfer;

        private Upload(int id, Path file, UploadListener listener) {
            this.id = id;
            this.file = file;
            this.listener = listener;
        }

        private void closeTransfer() {
            if (transfer != null) {
                try {
                    transfer.close();
                } catch (IOException ignored) {
                    // Only a read handle.
                }
                transfer = null;
            }
        }
    }
}
