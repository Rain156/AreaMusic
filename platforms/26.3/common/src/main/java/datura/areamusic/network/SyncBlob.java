package datura.areamusic.network;

import net.minecraft.network.FriendlyByteBuf;

import java.util.Objects;

/** Opaque bytes of one {@code SyncMessage}; the single payload shape each loader registers. */
public record SyncBlob(byte[] data) {
    private static final int MAX_CLIENTBOUND_BYTES = 1_000_000;
    private static final int MAX_SERVERBOUND_BYTES = 32_700;

    public SyncBlob {
        Objects.requireNonNull(data, "data");
    }

    public static void encode(SyncBlob blob, FriendlyByteBuf buffer) {
        buffer.writeByteArray(blob.data);
    }

    public static SyncBlob decodeClientbound(FriendlyByteBuf buffer) {
        return new SyncBlob(buffer.readByteArray(MAX_CLIENTBOUND_BYTES));
    }

    public static SyncBlob decodeServerbound(FriendlyByteBuf buffer) {
        return new SyncBlob(buffer.readByteArray(MAX_SERVERBOUND_BYTES));
    }
}
