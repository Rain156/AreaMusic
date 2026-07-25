package datura.areamusic.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public record ClientboundReloadMusic(long revision) {
    public ClientboundReloadMusic {
        if (revision < 0) {
            throw new IllegalArgumentException("Revision must not be negative");
        }
    }

    public static void encode(ClientboundReloadMusic message, FriendlyByteBuf buffer) {
        buffer.writeVarLong(message.revision);
    }

    public static ClientboundReloadMusic decode(FriendlyByteBuf buffer) {
        return new ClientboundReloadMusic(buffer.readVarLong());
    }

    public static void handle(ClientboundReloadMusic message, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> AreaMusicNetwork.handleClientReload(message));
        context.setPacketHandled(true);
    }
}
