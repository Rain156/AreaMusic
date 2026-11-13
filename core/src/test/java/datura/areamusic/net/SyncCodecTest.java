package datura.areamusic.net;

import datura.areamusic.area.AreaDefinition;
import datura.areamusic.area.AreaPosition;
import datura.areamusic.edit.AreaEdits;
import datura.areamusic.edit.EditOp;
import datura.areamusic.edit.RejectReason;
import datura.areamusic.net.SyncMessage.UploadStatus;
import datura.areamusic.sync.MusicFingerprint;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncCodecTest {
    private static final String SHA = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
    private static final AreaDefinition AREA = AreaEdits.single(
            "village", "minecraft:overworld",
            new AreaPosition(1, 2, 3), new AreaPosition(-4, 5, 6), "村庄/day theme.ogg"
    ).withDisplayName("村庄 🎵").withColor(0x1D9E75).withEnabled(false);
    private static final MusicFingerprint FINGERPRINT = new MusicFingerprint("村庄/day theme.ogg", 12345, SHA);

    @Test
    void clientboundMessagesRoundTrip() {
        List<SyncMessage.Clientbound> messages = List.of(
                new SyncMessage.Hello(true, false, 1L << 25),
                new SyncMessage.Manifest(List.of(FINGERPRINT)),
                new SyncMessage.Manifest(List.of()),
                new SyncMessage.EditorSnapshot(9, List.of(AREA, AREA.withPriority(3)), true, false),
                new SyncMessage.LibraryList(List.of(FINGERPRINT), false, true),
                new SyncMessage.AreaDelta(10, new EditOp.Put(AREA)),
                new SyncMessage.AreaDelta(11, new EditOp.Remove("village")),
                new SyncMessage.EditReply(7, true, true, new EditOp.Remove("village"), null, ""),
                new SyncMessage.EditReply(8, false, false, null, RejectReason.UNKNOWN_MUSIC, "gone.ogg"),
                new SyncMessage.MusicUnavailable(SHA),
                new SyncMessage.UploadReply(3, UploadStatus.COMPLETE, "day theme.ogg")
        );

        for (SyncMessage.Clientbound message : messages) {
            assertEquals(message, SyncCodec.decodeClientbound(SyncCodec.encode(message)));
        }
    }

    @Test
    void serverboundMessagesRoundTrip() {
        List<SyncMessage.Serverbound> messages = List.of(
                new SyncMessage.EditorOpen(),
                new SyncMessage.EditorClose(),
                new SyncMessage.EditRequest(5, new EditOp.Put(AREA)),
                new SyncMessage.MusicRequest(SHA),
                new SyncMessage.UploadBegin(2, "C:\\Music\\Day Theme.OGG", 99, SHA),
                new SyncMessage.UploadCancel(2)
        );

        for (SyncMessage.Serverbound message : messages) {
            assertEquals(message, SyncCodec.decodeServerbound(SyncCodec.encode(message)));
        }
    }

    @Test
    void chunksKeepTheirBytes() {
        byte[] data = new byte[30_000];
        Arrays.fill(data, (byte) 0x5A);

        SyncMessage.MusicChunk down = (SyncMessage.MusicChunk) SyncCodec.decodeClientbound(
                SyncCodec.encode(new SyncMessage.MusicChunk(SHA, 64, data, true)));
        byte[] upBytes = SyncCodec.encode(new SyncMessage.UploadChunk(1, 128, data, false));
        SyncMessage.UploadChunk up = (SyncMessage.UploadChunk) SyncCodec.decodeServerbound(upBytes);

        assertArrayEquals(data, down.data());
        assertEquals(64, down.offset());
        assertTrue(down.last());
        assertArrayEquals(data, up.data());
        assertEquals(128, up.offset());
        assertTrue(upBytes.length <= SyncCodec.MAX_SERVERBOUND_BYTES);
    }

    @Test
    void aMessageIsOnlyAcceptedInItsOwnDirection() {
        byte[] hello = SyncCodec.encode(new SyncMessage.Hello(true, true, 1));
        byte[] open = SyncCodec.encode(new SyncMessage.EditorOpen());

        assertThrows(IllegalArgumentException.class, () -> SyncCodec.decodeServerbound(hello));
        assertThrows(IllegalArgumentException.class, () -> SyncCodec.decodeClientbound(open));
    }

    @Test
    void malformedBytesAreRejectedWithoutHugeAllocations() {
        byte[] valid = SyncCodec.encode(new SyncMessage.MusicRequest(SHA));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        byte[] truncated = Arrays.copyOf(valid, valid.length - 3);
        byte[] hugeLength = {67, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        byte[] hugeList = {2, 0x7F, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF};
        byte[] badArea = SyncCodec.encode(new SyncMessage.EditRequest(1, new EditOp.Remove("x")));
        badArea[5] = 0;

        for (byte[] data : List.of(new byte[0], new byte[]{99}, trailing, truncated, hugeLength, badArea)) {
            assertThrows(IllegalArgumentException.class, () -> SyncCodec.decodeServerbound(data));
        }
        assertThrows(IllegalArgumentException.class, () -> SyncCodec.decodeClientbound(hugeList));
    }
}
