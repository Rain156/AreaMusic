package datura.areamusic.network;

import datura.areamusic.AreaMusic;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class AreaMusicNetwork {
    private static final String PROTOCOL_VERSION = "3";
    private static final ClientHandler NO_OP_CLIENT_HANDLER = new ClientHandler() {
        @Override
        public void onPlayback(long revision, PlaybackState state) {
        }

        @Override
        public void onReload(long revision) {
        }
    };

    private static SimpleChannel channel;
    private static volatile ClientHandler clientHandler = NO_OP_CLIENT_HANDLER;

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
                messageId,
                ClientboundReloadMusic.class,
                ClientboundReloadMusic::encode,
                ClientboundReloadMusic::decode,
                AreaMusicNetwork::handleReloadPacket,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT)
        );
    }

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = Objects.requireNonNull(handler, "handler");
    }

    public static void clearClientHandler() {
        clientHandler = NO_OP_CLIENT_HANDLER;
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

    static void handleClientPlayback(ClientboundPlaybackState message) {
        clientHandler.onPlayback(message.revision(), message.state());
    }

    static void handleClientReload(ClientboundReloadMusic message) {
        clientHandler.onReload(message.revision());
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

    public interface ClientHandler {
        void onPlayback(long revision, PlaybackState state);

        void onReload(long revision);
    }
}
