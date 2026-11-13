package datura.areamusic.net;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.RejectReason;
import datura.areamusic.sync.MusicFingerprint;

import java.util.List;
import java.util.Objects;

/**
 * Everything the editor and music distribution say over the wire. One generic payload per
 * direction carries the bytes from {@link SyncCodec}, so each loader registers a single channel.
 */
public sealed interface SyncMessage {
    /** Sent by the server. */
    sealed interface Clientbound extends SyncMessage {
    }

    /** Sent by a client. */
    sealed interface Serverbound extends SyncMessage {
    }

    /** First message after login: what this server offers this player. */
    record Hello(boolean canEdit, boolean distributeMusic, long maxUploadBytes) implements Clientbound {
    }

    /** The exact content of every track some area plays. Replaces the previous manifest. */
    record Manifest(List<MusicFingerprint> entries) implements Clientbound {
        public Manifest {
            entries = List.copyOf(entries);
        }
    }

    /** Part of the full area list for an editor. {@code first} clears what the editor had. */
    record EditorSnapshot(long revision, List<AreaDefinition> areas, boolean first, boolean last)
            implements Clientbound {
        public EditorSnapshot {
            areas = List.copyOf(areas);
        }
    }

    /** Part of the whole music library, for the editor's music picker. */
    record LibraryList(List<MusicFingerprint> entries, boolean first, boolean last)
            implements Clientbound {
        public LibraryList {
            entries = List.copyOf(entries);
        }
    }

    /** One applied change, for every open editor. */
    record AreaDelta(long revision, EditOp op) implements Clientbound {
        public AreaDelta {
            Objects.requireNonNull(op, "op");
        }
    }

    /** Answer to an {@link EditRequest}. {@code inverse} and {@code reason} may be null. */
    record EditReply(
            int requestId, boolean applied, boolean changed,
            EditOp inverse, RejectReason reason, String detail
    ) implements Clientbound {
        public EditReply {
            Objects.requireNonNull(detail, "detail");
        }
    }

    record MusicChunk(String sha256, long offset, byte[] data, boolean last) implements Clientbound {
    }

    record MusicUnavailable(String sha256) implements Clientbound {
    }

    /** {@code detail} is the MusicID when complete, or a rejection code when rejected. */
    record UploadReply(int uploadId, UploadStatus status, String detail) implements Clientbound {
    }

    enum UploadStatus {
        ACCEPTED,
        COMPLETE,
        REJECTED
    }

    record EditorOpen() implements Serverbound {
    }

    record EditorClose() implements Serverbound {
    }

    record EditRequest(int requestId, EditOp op) implements Serverbound {
        public EditRequest {
            Objects.requireNonNull(op, "op");
        }
    }

    record MusicRequest(String sha256) implements Serverbound {
    }

    record UploadBegin(int uploadId, String fileName, long size, String sha256) implements Serverbound {
    }

    record UploadChunk(int uploadId, long offset, byte[] data, boolean last) implements Serverbound {
    }

    record UploadCancel(int uploadId) implements Serverbound {
    }
}
