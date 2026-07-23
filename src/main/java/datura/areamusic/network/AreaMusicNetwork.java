package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Objects;

public final class AreaMusicNetwork {
    private static final String PROTOCOL_VERSION = "2";
    private static final ClientHandler NO_OP_CLIENT_HANDLER = new ClientHandler() {
        @Override
        public void onPlayback(long revision, PlaybackState state) {
        }

        @Override
        public void onReload(long revision) {
        }
    };

    private static volatile ClientHandler clientHandler = NO_OP_CLIENT_HANDLER;

    private AreaMusicNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
        registrar.playToClient(
                ClientboundPlaybackState.TYPE,
                ClientboundPlaybackState.STREAM_CODEC,
                (message, context) -> handleClientPlayback(message)
        );
        registrar.playToClient(
                ClientboundReloadMusic.TYPE,
                ClientboundReloadMusic.STREAM_CODEC,
                (message, context) -> handleClientReload(message)
        );
    }

    public static void setClientHandler(ClientHandler handler) {
        clientHandler = Objects.requireNonNull(handler, "handler");
    }

    public static void clearClientHandler() {
        clientHandler = NO_OP_CLIENT_HANDLER;
    }

    public static void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
        PacketDistributor.sendToPlayer(player, new ClientboundPlaybackState(revision, state));
    }

    public static void sendReload(ServerPlayer player, long revision) {
        PacketDistributor.sendToPlayer(player, new ClientboundReloadMusic(revision));
    }

    static void handleClientPlayback(ClientboundPlaybackState message) {
        clientHandler.onPlayback(message.revision(), message.state());
    }

    static void handleClientReload(ClientboundReloadMusic message) {
        clientHandler.onReload(message.revision());
    }

    public interface ClientHandler {
        void onPlayback(long revision, PlaybackState state);

        void onReload(long revision);
    }
}
