package datura.areamusic.network;

import datura.areamusic.AreaMusic;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ClientboundPlaybackState(long revision, PlaybackState state) implements CustomPacketPayload {
    private static final int MAX_AREA_ID_LENGTH = 64;
    private static final int MAX_MUSIC_ID_LENGTH = 1024;
    public static final Type<ClientboundPlaybackState> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AreaMusic.MOD_ID, "playback_state")
    );
    public static final StreamCodec<FriendlyByteBuf, ClientboundPlaybackState> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ClientboundPlaybackState decode(FriendlyByteBuf buffer) {
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

        @Override
        public void encode(FriendlyByteBuf buffer, ClientboundPlaybackState value) {
            buffer.writeVarLong(value.revision());
            PlaybackState state = value.state();
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
    };

    public ClientboundPlaybackState {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
        Objects.requireNonNull(state, "state");
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
