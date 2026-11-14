package datura.areamusic.network;

import datura.areamusic.AreaMusic;
import datura.areamusic.playback.PlaybackState;
import datura.areamusic.server.AreaMusicServer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public final class AreaMusicNetwork {
    private static final String PROTOCOL_VERSION = "5";
    private static final ClientPacketHandler NO_OP_CLIENT_HANDLER = new ClientPacketHandler() {
        @Override
        public void onPlayback(long revision, PlaybackState state) {
        }

        @Override
        public void onReload(long revision) {
        }
    };

    private static SimpleChannel channel;
    private static final AtomicReference<ClientPacketHandler> CLIENT_HANDLER =
            new AtomicReference<>(NO_OP_CLIENT_HANDLER);

    private AreaMusicNetwork() {
    }

    public static synchronized void register() {
        if (channel != null) {
            return;
        }
        channel = NetworkRegistry.newSimpleChannel(
                ResourceLocation.fromNamespaceAndPath(AreaMusic.MOD_ID, "main"),
                () -> PROTOCOL_VERSION,
                PROTOCOL_VERSION::equals,
                PROTOCOL_VERSION::equals
        );

        int messageId = 0;
        channel.registerMessage(
                messageId++,
                ClientboundPlaybackState.class,
                ClientboundPlaybackState::encode,
                ClientboundPlaybackState::decode,
                AreaMusicNetwork::handlePlaybackPacket,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        channel.registerMessage(
                messageId++,
                ClientboundReloadMusic.class,
                ClientboundReloadMusic::encode,
                ClientboundReloadMusic::decode,
                AreaMusicNetwork::handleReloadPacket,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        channel.registerMessage(
                messageId++,
                SyncToClient.class,
                (message, buffer) -> SyncBlob.encode(message.blob(), buffer),
                buffer -> new SyncToClient(SyncBlob.decodeClientbound(buffer)),
                AreaMusicNetwork::handleSyncToClient,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
        channel.registerMessage(
                messageId,
                SyncToServer.class,
                (message, buffer) -> SyncBlob.encode(message.blob(), buffer),
                buffer -> new SyncToServer(SyncBlob.decodeServerbound(buffer)),
                AreaMusicNetwork::handleSyncToServer,
                Optional.of(NetworkDirection.PLAY_TO_SERVER)
        );
    }

    public static void setClientHandler(ClientPacketHandler handler) {
        CLIENT_HANDLER.set(Objects.requireNonNull(handler, "handler"));
    }

    public static void clearClientHandler(ClientPacketHandler expected) {
        CLIENT_HANDLER.compareAndSet(
                Objects.requireNonNull(expected, "expected"),
                NO_OP_CLIENT_HANDLER
        );
    }

    public static void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
        requireChannel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new ClientboundPlaybackState(revision, state)
        );
    }

    public static void sendReload(ServerPlayer player, long revision) {
        requireChannel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new ClientboundReloadMusic(revision)
        );
    }

    public static void sendSync(ServerPlayer player, byte[] data) {
        requireChannel().send(
                PacketDistributor.PLAYER.with(() -> player),
                new SyncToClient(new SyncBlob(data))
        );
    }

    /** Client only. */
    public static void sendSyncToServer(byte[] data) {
        requireChannel().sendToServer(new SyncToServer(new SyncBlob(data)));
    }

    private static void handleSyncToClient(
            SyncToClient message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> CLIENT_HANDLER.get().onSync(message.blob().data()));
        context.setPacketHandled(true);
    }

    private static void handleSyncToServer(
            SyncToServer message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        ServerPlayer sender = context.getSender();
        if (sender != null) {
            context.enqueueWork(() -> AreaMusicServer.onSyncMessage(sender, message.blob().data()));
        }
        context.setPacketHandled(true);
    }

    static void handleClientPlayback(ClientboundPlaybackState message) {
        CLIENT_HANDLER.get().onPlayback(message.revision(), message.state());
    }

    static void handleClientReload(ClientboundReloadMusic message) {
        CLIENT_HANDLER.get().onReload(message.revision());
    }

    private static void handlePlaybackPacket(
            ClientboundPlaybackState message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleClientPlayback(message));
        context.setPacketHandled(true);
    }

    private static void handleReloadPacket(
            ClientboundReloadMusic message,
            Supplier<NetworkEvent.Context> contextSupplier
    ) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> handleClientReload(message));
        context.setPacketHandled(true);
    }

    private static SimpleChannel requireChannel() {
        if (channel == null) {
            throw new IllegalStateException("AreaMusic network channel has not been registered");
        }
        return channel;
    }

    /** Bytes of one clientbound sync message. */
    public record SyncToClient(SyncBlob blob) {
    }

    /** Bytes of one serverbound sync message. */
    public record SyncToServer(SyncBlob blob) {
    }
}
