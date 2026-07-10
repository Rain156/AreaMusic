package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.Objects;
import java.util.function.Supplier;

public record ClientboundPlaybackState(long revision, PlaybackState state) {
    private static final int MAX_AREA_ID_LENGTH = 64;
    private static final int MAX_MUSIC_ID_LENGTH = 1024;

    public ClientboundPlaybackState {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        Objects.requireNonNull(state, "state");
    }

    public static void encode(ClientboundPlaybackState message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.revision);
        PlaybackState state = message.state;
        buffer.writeBoolean(state.playing());
        if (!state.playing()) {
            return;
        }
        buffer.writeUtf(state.areaId(), MAX_AREA_ID_LENGTH);
        buffer.writeUtf(state.musicId(), MAX_MUSIC_ID_LENGTH);
        buffer.writeFloat(state.volume());
        buffer.writeBoolean(state.loop());
        buffer.writeVarInt(state.fadeInMs());
        buffer.writeVarInt(state.fadeOutMs());
    }

    public static ClientboundPlaybackState decode(FriendlyByteBuf buffer) {
        long revision = buffer.readVarLong();
        if (!buffer.readBoolean()) {
            return new ClientboundPlaybackState(revision, PlaybackState.stopped());
        }
        PlaybackState state = PlaybackState.playing(
                buffer.readUtf(MAX_AREA_ID_LENGTH),
                buffer.readUtf(MAX_MUSIC_ID_LENGTH),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readVarInt(),
                buffer.readVarInt()
        );
        return new ClientboundPlaybackState(revision, state);
    }

    public static void handle(ClientboundPlaybackState message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> AreaMusicNetwork.handleClientPlayback(message));
        context.setPacketHandled(true);
    }
}
