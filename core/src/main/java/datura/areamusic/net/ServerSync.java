package datura.areamusic.net;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.EditResult;
import datura.areamusic.edit.RejectReason;
import datura.areamusic.music.MusicLibrary;
import datura.areamusic.net.SyncMessage.AreaDelta;
import datura.areamusic.net.SyncMessage.EditReply;
import datura.areamusic.net.SyncMessage.EditRequest;
import datura.areamusic.net.SyncMessage.EditorClose;
import datura.areamusic.net.SyncMessage.EditorOpen;
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
import datura.areamusic.net.SyncMessage.UploadStatus;
import datura.areamusic.sync.IncomingTransfer;
import datura.areamusic.sync.MusicFingerprint;
import datura.areamusic.sync.MusicManifest;
import datura.areamusic.sync.OutgoingTransfer;
import datura.areamusic.sync.TransferBudget;
import datura.areamusic.sync.TransferException;
import datura.areamusic.sync.TransferLimits;
import datura.areamusic.sync.UploadNames;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Server half of the editor and music distribution, independent of any loader. Every method must
 * be called on the server thread.
 *
 * @param <P> how the host identifies a player
 */
public final class ServerSync<P> {
    private static final int MAX_QUEUED_DOWNLOADS = 256;
    private static final int SNAPSHOT_ENTRY_ESTIMATE = 96;
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private final Host<P> host;
    private final Settings settings;
    private final Map<P, PlayerState> players = new HashMap<>();
    private MusicManifest fullManifest = MusicManifest.empty();
    private MusicManifest neededManifest = MusicManifest.empty();

    public ServerSync(Host<P> host, Settings settings) {
        this.host = Objects.requireNonNull(host, "host");
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public void onJoin(P player) {
        players.put(player, new PlayerState(settings));
        host.send(player, hello(player));
        host.send(player, new Manifest(neededManifest.entries()));
    }

    public void onLeave(P player) {
        PlayerState state = players.remove(player);
        if (state != null) {
            state.close();
        }
    }

    /** Closes every open transfer; the instance must not be used afterwards. */
    public void close() {
        players.values().forEach(PlayerState::close);
        players.clear();
    }

    public void onMessage(P player, SyncMessage.Serverbound message) {
        PlayerState state = players.get(player);
        if (state == null) {
            return;
        }
        if (message instanceof EditorOpen) {
            openEditor(player, state);
        } else if (message instanceof EditorClose) {
            state.subscribed = false;
        } else if (message instanceof EditRequest request) {
            edit(player, request);
        } else if (message instanceof MusicRequest request) {
            requestMusic(player, state, request.sha256());
        } else if (message instanceof UploadBegin begin) {
            beginUpload(player, state, begin);
        } else if (message instanceof UploadChunk chunk) {
            uploadChunk(player, state, chunk);
        } else if (message instanceof UploadCancel cancel) {
            if (state.upload != null && state.upload.id == cancel.uploadId()) {
                state.closeUpload();
            }
        }
    }

    /** Serves queued downloads within each player's byte budget. Call once per server tick. */
    public void tick() {
        for (Map.Entry<P, PlayerState> entry : players.entrySet()) {
            serveDownloads(entry.getKey(), entry.getValue());
        }
    }

    /** The host applied {@code op}, from an editor or a command. */
    public void areaChanged(EditOp op, long revision) {
        AreaDelta delta = new AreaDelta(revision, op);
        players.forEach((player, state) -> {
            if (state.subscribed) {
                host.send(player, delta);
            }
        });
        refreshNeededManifest();
    }

    /** Areas and library were replaced wholesale, as after a reload or a finished upload. */
    public void reloaded(MusicManifest manifest) {
        fullManifest = Objects.requireNonNull(manifest, "manifest");
        neededManifest = needed();
        Manifest message = new Manifest(neededManifest.entries());
        players.forEach((player, state) -> {
            host.send(player, message);
            if (state.subscribed) {
                sendEditorState(player);
            }
        });
    }

    /** A player's permission may have changed; tells them and drops editors that lost access. */
    public void refreshPermissions() {
        players.forEach((player, state) -> {
            boolean canEdit = host.canEdit(player);
            if (canEdit != state.lastCanEdit) {
                host.send(player, hello(player));
                if (!canEdit) {
                    state.subscribed = false;
                    state.closeUpload();
                }
            }
        });
    }

    /** Reports the outcome of {@link Host#finishUpload}; {@code rejection} is null on success. */
    public void uploadFinished(P player, int uploadId, String musicId, String rejection) {
        if (!players.containsKey(player)) {
            return;
        }
        host.send(player, rejection == null
                ? new UploadReply(uploadId, UploadStatus.COMPLETE, musicId)
                : new UploadReply(uploadId, UploadStatus.REJECTED, rejection));
    }

    public MusicManifest manifest() {
        return fullManifest;
    }

    private Hello hello(P player) {
        boolean canEdit = host.canEdit(player);
        PlayerState state = players.get(player);
        if (state != null) {
            state.lastCanEdit = canEdit;
        }
        return new Hello(canEdit, settings.distributeMusic(), settings.maxUploadBytes());
    }

    private void openEditor(P player, PlayerState state) {
        if (!host.canEdit(player)) {
            state.subscribed = false;
            host.send(player, hello(player));
            return;
        }
        state.subscribed = true;
        sendEditorState(player);
    }

    private void sendEditorState(P player) {
        long revision = host.revision();
        List<AreaDefinition> areas = host.areas();
        List<AreaDefinition> batch = new ArrayList<>();
        int batchBytes = 0;
        boolean first = true;
        for (AreaDefinition area : areas) {
            int size = SNAPSHOT_ENTRY_ESTIMATE + SyncCodec.encode(new AreaDelta(0, new EditOp.Put(area))).length;
            if (!batch.isEmpty() && batchBytes + size > SyncCodec.CLIENTBOUND_BATCH_BYTES) {
                host.send(player, new EditorSnapshot(revision, batch, first, false));
                first = false;
                batch = new ArrayList<>();
                batchBytes = 0;
            }
            batch.add(area);
            batchBytes += size;
        }
        host.send(player, new EditorSnapshot(revision, batch, first, true));

        List<MusicFingerprint> entries = fullManifest.entries();
        int perMessage = 1024;
        if (entries.isEmpty()) {
            host.send(player, new LibraryList(List.of(), true, true));
        }
        for (int start = 0; start < entries.size(); start += perMessage) {
            int end = Math.min(entries.size(), start + perMessage);
            host.send(player, new LibraryList(
                    entries.subList(start, end), start == 0, end == entries.size()));
        }
    }

    private void edit(P player, EditRequest request) {
        if (!host.canEdit(player)) {
            host.send(player, new EditReply(
                    request.requestId(), false, false, null, RejectReason.NOT_ALLOWED, ""));
            return;
        }
        EditResult result = host.applyEdit(player, request.op());
        host.send(player, new EditReply(
                request.requestId(), result.applied(), result.changed(),
                result.inverse(), result.reason(), result.detail()));
    }

    private void refreshNeededManifest() {
        MusicManifest updated = needed();
        if (updated.equals(neededManifest)) {
            return;
        }
        neededManifest = updated;
        Manifest message = new Manifest(updated.entries());
        players.keySet().forEach(player -> host.send(player, message));
    }

    private MusicManifest needed() {
        if (!settings.distributeMusic()) {
            return MusicManifest.empty();
        }
        Set<String> musicIds = new HashSet<>();
        for (AreaDefinition area : host.areas()) {
            musicIds.addAll(area.musicIds());
        }
        return fullManifest.subset(musicIds);
    }

    private void requestMusic(P player, PlayerState state, String sha256) {
        Optional<MusicFingerprint> fingerprint = settings.distributeMusic()
                ? fullManifest.findBySha256(sha256)
                : Optional.empty();
        if (fingerprint.isEmpty() || state.queued.size() >= MAX_QUEUED_DOWNLOADS) {
            host.send(player, new MusicUnavailable(sha256));
            return;
        }
        if (state.queued.add(sha256)) {
            state.downloads.add(fingerprint.get());
        }
    }

    private void serveDownloads(P player, PlayerState state) {
        state.budget.refill();
        while (true) {
            if (state.active == null) {
                MusicFingerprint next = state.downloads.poll();
                if (next == null) {
                    return;
                }
                state.activeFingerprint = next;
                try {
                    Path file = host.library().find(next.musicId())
                            .orElseThrow(() -> new IOException("No longer in the library"));
                    state.active = new OutgoingTransfer(file, TransferLimits.DOWNLOAD_CHUNK_BYTES);
                    if (state.active.size() != next.size()) {
                        throw new IOException("File changed since it was fingerprinted");
                    }
                } catch (IOException exception) {
                    failDownload(player, state);
                    continue;
                }
            }
            if (!state.budget.tryTake(TransferLimits.DOWNLOAD_CHUNK_BYTES)) {
                return;
            }
            try {
                OutgoingTransfer.Chunk chunk = state.active.next();
                host.send(player, new MusicChunk(
                        state.activeFingerprint.sha256(), chunk.offset(), chunk.data(), chunk.last()));
                if (chunk.last()) {
                    state.finishDownload();
                }
            } catch (IOException exception) {
                failDownload(player, state);
            }
        }
    }

    private void failDownload(P player, PlayerState state) {
        String sha256 = state.activeFingerprint.sha256();
        state.finishDownload();
        host.send(player, new MusicUnavailable(sha256));
    }

    private void beginUpload(P player, PlayerState state, UploadBegin begin) {
        String rejection = null;
        String musicIdHint = null;
        if (!host.canEdit(player)) {
            rejection = "not_allowed";
        } else if (!settings.distributeMusic()) {
            rejection = "disabled";
        } else if (state.upload != null) {
            rejection = "busy";
        } else if (begin.size() <= 0 || begin.size() > settings.maxUploadBytes()) {
            rejection = "too_large";
        } else if (!SHA_256.matcher(begin.sha256()).matches()) {
            rejection = "corrupt";
        } else {
            try {
                musicIdHint = UploadNames.sanitize(begin.fileName());
            } catch (IllegalArgumentException exception) {
                rejection = "bad_name";
            }
        }
        if (rejection != null) {
            host.send(player, new UploadReply(begin.uploadId(), UploadStatus.REJECTED, rejection));
            return;
        }
        Optional<MusicFingerprint> existing = fullManifest.findBySha256(begin.sha256());
        if (existing.isPresent()) {
            host.send(player, new UploadReply(
                    begin.uploadId(), UploadStatus.COMPLETE, existing.get().musicId()));
            return;
        }
        long libraryBytes = fullManifest.entries().stream().mapToLong(MusicFingerprint::size).sum();
        if (libraryBytes + begin.size() > settings.maxLibraryBytes()) {
            host.send(player, new UploadReply(begin.uploadId(), UploadStatus.REJECTED, "library_full"));
            return;
        }
        try {
            String extension = musicIdHint.substring(musicIdHint.lastIndexOf('.') + 1);
            IncomingTransfer transfer = new IncomingTransfer(
                    host.newUploadTemporaryPath(extension), begin.size(), begin.sha256());
            state.upload = new Upload(begin.uploadId(), transfer, musicIdHint);
            host.send(player, new UploadReply(begin.uploadId(), UploadStatus.ACCEPTED, ""));
        } catch (IOException exception) {
            host.send(player, new UploadReply(begin.uploadId(), UploadStatus.REJECTED, "server_error"));
        }
    }

    private void uploadChunk(P player, PlayerState state, UploadChunk chunk) {
        Upload upload = state.upload;
        if (upload == null || upload.id != chunk.uploadId()) {
            return;
        }
        try {
            upload.transfer.accept(chunk.offset(), chunk.data(), chunk.last());
        } catch (TransferException | IOException exception) {
            state.closeUpload();
            host.send(player, new UploadReply(chunk.uploadId(), UploadStatus.REJECTED, "corrupt"));
            return;
        }
        if (upload.transfer.complete()) {
            state.upload = null;
            host.finishUpload(player, upload.id, upload.transfer, upload.musicIdHint);
        }
    }

    public interface Host<P> {
        void send(P player, SyncMessage.Clientbound message);

        boolean canEdit(P player);

        /**
         * Validates, applies and persists the edit, then calls {@link ServerSync#areaChanged} if it
         * changed anything.
         */
        EditResult applyEdit(P player, EditOp op);

        List<AreaDefinition> areas();

        long revision();

        MusicLibrary library();

        /** A fresh path outside the music library whose name ends in {@code .extension}. */
        Path newUploadTemporaryPath(String extension);

        /**
         * Takes ownership of a fully received, hash-verified upload. The host installs it off
         * thread, rescans the library, then calls {@link ServerSync#reloaded} and
         * {@link ServerSync#uploadFinished} on the server thread.
         */
        void finishUpload(P player, int uploadId, IncomingTransfer transfer, String musicIdHint);
    }

    public record Settings(
            boolean distributeMusic, long maxUploadBytes, long maxLibraryBytes, long downloadBytesPerTick
    ) {
        public static final Settings DEFAULT = new Settings(
                true, TransferLimits.DEFAULT_MAX_UPLOAD_BYTES, 1L << 30, 128 * 1024);

        public Settings {
            if (maxUploadBytes <= 0 || maxLibraryBytes <= 0 || downloadBytesPerTick <= 0) {
                throw new IllegalArgumentException("Limits must be positive");
            }
        }
    }

    private record Upload(int id, IncomingTransfer transfer, String musicIdHint) {
    }

    private static final class PlayerState {
        private final TransferBudget budget;
        private final Deque<MusicFingerprint> downloads = new ArrayDeque<>();
        private final Set<String> queued = new HashSet<>();
        private boolean subscribed;
        private boolean lastCanEdit;
        private OutgoingTransfer active;
        private MusicFingerprint activeFingerprint;
        private Upload upload;

        private PlayerState(Settings settings) {
            long perTick = Math.max(settings.downloadBytesPerTick(), TransferLimits.DOWNLOAD_CHUNK_BYTES);
            budget = new TransferBudget(perTick, perTick * 2);
        }

        private void finishDownload() {
            queued.remove(activeFingerprint.sha256());
            closeActive();
        }

        private void closeActive() {
            if (active != null) {
                try {
                    active.close();
                } catch (IOException ignored) {
                    // Nothing useful to do: the file was only being read.
                }
            }
            active = null;
            activeFingerprint = null;
        }

        private void closeUpload() {
            if (upload != null) {
                try {
                    upload.transfer.close();
                } catch (IOException ignored) {
                    // The temporary file is retried for deletion by the next sweep.
                }
                upload = null;
            }
        }

        private void close() {
            closeActive();
            closeUpload();
            downloads.clear();
            queued.clear();
        }
    }
}
