package datura.areamusic.net;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaJsonCodec;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.RejectReason;
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
import datura.areamusic.sync.MusicFingerprint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Binary form of {@link SyncMessage}. Decoding never trusts a length it has not bounded. */
public final class SyncCodec {
    /** Vanilla's limit for a client-to-server custom payload. */
    public static final int MAX_SERVERBOUND_BYTES = 32_767;
    /** Target size for one clientbound message; vanilla allows 1 MiB. */
    public static final int CLIENTBOUND_BATCH_BYTES = 256 * 1024;
    private static final int MAX_STRING_BYTES = 1 << 20;
    private static final int MAX_LIST_SIZE = 65_536;
    private static final int MAX_CHUNK_BYTES = 1 << 20;
    private static final AreaJsonCodec AREAS = new AreaJsonCodec();

    private SyncCodec() {
    }

    public static byte[] encode(SyncMessage message) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            write(out, message);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
        return bytes.toByteArray();
    }

    public static SyncMessage.Clientbound decodeClientbound(byte[] data) {
        if (decode(data) instanceof SyncMessage.Clientbound message) {
            return message;
        }
        throw new IllegalArgumentException("Not a clientbound AreaMusic message");
    }

    public static SyncMessage.Serverbound decodeServerbound(byte[] data) {
        if (decode(data) instanceof SyncMessage.Serverbound message) {
            return message;
        }
        throw new IllegalArgumentException("Not a serverbound AreaMusic message");
    }

    private static SyncMessage decode(byte[] data) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            SyncMessage message = read(in);
            if (in.available() > 0) {
                throw new IllegalArgumentException("Trailing bytes after AreaMusic message");
            }
            return message;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalArgumentException("Malformed AreaMusic message", exception);
        }
    }

    private static void write(DataOutputStream out, SyncMessage message) throws IOException {
        if (message instanceof Hello hello) {
            out.writeByte(1);
            out.writeBoolean(hello.canEdit());
            out.writeBoolean(hello.distributeMusic());
            out.writeLong(hello.maxUploadBytes());
        } else if (message instanceof Manifest manifest) {
            out.writeByte(2);
            writeFingerprints(out, manifest.entries());
        } else if (message instanceof EditorSnapshot snapshot) {
            out.writeByte(3);
            out.writeLong(snapshot.revision());
            out.writeInt(snapshot.areas().size());
            for (AreaDefinition area : snapshot.areas()) {
                writeArea(out, area);
            }
            out.writeBoolean(snapshot.first());
            out.writeBoolean(snapshot.last());
        } else if (message instanceof LibraryList library) {
            out.writeByte(4);
            writeFingerprints(out, library.entries());
            out.writeBoolean(library.first());
            out.writeBoolean(library.last());
        } else if (message instanceof AreaDelta delta) {
            out.writeByte(5);
            out.writeLong(delta.revision());
            writeOp(out, delta.op());
        } else if (message instanceof EditReply reply) {
            out.writeByte(6);
            out.writeInt(reply.requestId());
            out.writeBoolean(reply.applied());
            out.writeBoolean(reply.changed());
            out.writeBoolean(reply.inverse() != null);
            if (reply.inverse() != null) {
                writeOp(out, reply.inverse());
            }
            out.writeByte(reply.reason() == null ? -1 : reply.reason().ordinal());
            writeString(out, reply.detail());
        } else if (message instanceof MusicChunk chunk) {
            out.writeByte(7);
            writeString(out, chunk.sha256());
            out.writeLong(chunk.offset());
            writeBytes(out, chunk.data());
            out.writeBoolean(chunk.last());
        } else if (message instanceof MusicUnavailable unavailable) {
            out.writeByte(8);
            writeString(out, unavailable.sha256());
        } else if (message instanceof UploadReply reply) {
            out.writeByte(9);
            out.writeInt(reply.uploadId());
            out.writeByte(reply.status().ordinal());
            writeString(out, reply.detail());
        } else if (message instanceof EditorOpen) {
            out.writeByte(64);
        } else if (message instanceof EditorClose) {
            out.writeByte(65);
        } else if (message instanceof EditRequest request) {
            out.writeByte(66);
            out.writeInt(request.requestId());
            writeOp(out, request.op());
        } else if (message instanceof MusicRequest request) {
            out.writeByte(67);
            writeString(out, request.sha256());
        } else if (message instanceof UploadBegin begin) {
            out.writeByte(68);
            out.writeInt(begin.uploadId());
            writeString(out, begin.fileName());
            out.writeLong(begin.size());
            writeString(out, begin.sha256());
        } else if (message instanceof UploadChunk chunk) {
            out.writeByte(69);
            out.writeInt(chunk.uploadId());
            out.writeLong(chunk.offset());
            writeBytes(out, chunk.data());
            out.writeBoolean(chunk.last());
        } else if (message instanceof UploadCancel cancel) {
            out.writeByte(70);
            out.writeInt(cancel.uploadId());
        } else {
            throw new IllegalArgumentException("Unknown message: " + message);
        }
    }

    private static SyncMessage read(DataInputStream in) throws IOException {
        int type = in.readUnsignedByte();
        return switch (type) {
            case 1 -> new Hello(in.readBoolean(), in.readBoolean(), in.readLong());
            case 2 -> new Manifest(readFingerprints(in));
            case 3 -> {
                long revision = in.readLong();
                int count = readCount(in);
                List<AreaDefinition> areas = new ArrayList<>(Math.min(count, 1024));
                for (int index = 0; index < count; index++) {
                    areas.add(readArea(in));
                }
                yield new EditorSnapshot(revision, areas, in.readBoolean(), in.readBoolean());
            }
            case 4 -> new LibraryList(readFingerprints(in), in.readBoolean(), in.readBoolean());
            case 5 -> new AreaDelta(in.readLong(), readOp(in));
            case 6 -> {
                int requestId = in.readInt();
                boolean applied = in.readBoolean();
                boolean changed = in.readBoolean();
                EditOp inverse = in.readBoolean() ? readOp(in) : null;
                int reason = in.readByte();
                yield new EditReply(
                        requestId, applied, changed, inverse,
                        reason < 0 ? null : RejectReason.values()[reason], readString(in));
            }
            case 7 -> new MusicChunk(readString(in), in.readLong(), readBytes(in), in.readBoolean());
            case 8 -> new MusicUnavailable(readString(in));
            case 9 -> new UploadReply(
                    in.readInt(), UploadStatus.values()[in.readUnsignedByte()], readString(in));
            case 64 -> new EditorOpen();
            case 65 -> new EditorClose();
            case 66 -> new EditRequest(in.readInt(), readOp(in));
            case 67 -> new MusicRequest(readString(in));
            case 68 -> new UploadBegin(in.readInt(), readString(in), in.readLong(), readString(in));
            case 69 -> new UploadChunk(in.readInt(), in.readLong(), readBytes(in), in.readBoolean());
            case 70 -> new UploadCancel(in.readInt());
            default -> throw new IllegalArgumentException("Unknown message type " + type);
        };
    }

    private static void writeOp(DataOutputStream out, EditOp op) throws IOException {
        if (op instanceof EditOp.Put put) {
            out.writeByte(0);
            writeArea(out, put.area());
        } else {
            out.writeByte(1);
            writeString(out, op.areaId());
        }
    }

    private static EditOp readOp(DataInputStream in) throws IOException {
        int kind = in.readUnsignedByte();
        return switch (kind) {
            case 0 -> new EditOp.Put(readArea(in));
            case 1 -> new EditOp.Remove(readString(in));
            default -> throw new IllegalArgumentException("Unknown edit kind " + kind);
        };
    }

    private static void writeArea(DataOutputStream out, AreaDefinition area) throws IOException {
        writeString(out, area.id());
        writeString(out, AREAS.write(area));
    }

    private static AreaDefinition readArea(DataInputStream in) throws IOException {
        String id = readString(in);
        return AREAS.read(id, new StringReader(readString(in)));
    }

    private static void writeFingerprints(DataOutputStream out, List<MusicFingerprint> entries)
            throws IOException {
        out.writeInt(entries.size());
        for (MusicFingerprint entry : entries) {
            writeString(out, entry.musicId());
            out.writeLong(entry.size());
            writeString(out, entry.sha256());
        }
    }

    private static List<MusicFingerprint> readFingerprints(DataInputStream in) throws IOException {
        int count = readCount(in);
        List<MusicFingerprint> entries = new ArrayList<>(Math.min(count, 1024));
        for (int index = 0; index < count; index++) {
            entries.add(new MusicFingerprint(readString(in), in.readLong(), readString(in)));
        }
        return entries;
    }

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_LIST_SIZE) {
            throw new IllegalArgumentException("List size out of range: " + count);
        }
        return count;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in, MAX_STRING_BYTES), StandardCharsets.UTF_8);
    }

    private static void writeBytes(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        return readBytes(in, MAX_CHUNK_BYTES);
    }

    private static byte[] readBytes(DataInputStream in, int limit) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > limit || length > in.available()) {
            throw new IllegalArgumentException("Length out of range: " + length);
        }
        return in.readNBytes(length);
    }
}
