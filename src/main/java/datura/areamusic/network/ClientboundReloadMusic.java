package datura.areamusic.network;

import datura.areamusic.AreaMusic;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record ClientboundReloadMusic(long revision) implements CustomPacketPayload {
    public static final Type<ClientboundReloadMusic> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(AreaMusic.MOD_ID, "reload_music")
    );
    public static final StreamCodec<FriendlyByteBuf, ClientboundReloadMusic> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public ClientboundReloadMusic decode(FriendlyByteBuf buffer) {
            return new ClientboundReloadMusic(buffer.readVarLong());
        }

        @Override
        public void encode(FriendlyByteBuf buffer, ClientboundReloadMusic value) {
            buffer.writeVarLong(value.revision());
        }
    };

    public ClientboundReloadMusic {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
