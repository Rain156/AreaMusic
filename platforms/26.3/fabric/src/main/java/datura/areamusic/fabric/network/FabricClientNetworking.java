package datura.areamusic.fabric.network;

import datura.areamusic.fabric.network.FabricAreaMusicNetwork.PlaybackPayload;
import datura.areamusic.fabric.network.FabricAreaMusicNetwork.ReloadPayload;
import datura.areamusic.fabric.network.FabricAreaMusicNetwork.SyncToClientPayload;
import datura.areamusic.fabric.network.FabricAreaMusicNetwork.SyncToServerPayload;
import datura.areamusic.network.SyncBlob;
import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class FabricClientNetworking {
    private static final AtomicReference<ClientPacketHandler> CLIENT_HANDLER =
            new AtomicReference<>();

    private static boolean receiversRegistered;

    private FabricClientNetworking() {
    }

    public static synchronized void registerClientReceivers() {
        FabricAreaMusicNetwork.requireRegistered();
        if (receiversRegistered) {
            return;
        }

        boolean playbackRegistered = ClientPlayNetworking.registerGlobalReceiver(
                PlaybackPayload.TYPE,
                FabricClientNetworking::receivePlayback
        );
        if (!playbackRegistered) {
            throw new IllegalStateException(
                    "AreaMusic playback receiver was already registered by another owner"
            );
        }

        boolean reloadRegistered = ClientPlayNetworking.registerGlobalReceiver(
                ReloadPayload.TYPE,
                FabricClientNetworking::receiveReload
        );
        if (!reloadRegistered) {
            ClientPlayNetworking.unregisterGlobalReceiver(PlaybackPayload.TYPE.id());
            throw new IllegalStateException(
                    "AreaMusic reload receiver was already registered by another owner"
            );
        }
        ClientPlayNetworking.registerGlobalReceiver(
                SyncToClientPayload.TYPE,
                (payload, context) -> requireClientHandler().onSync(payload.blob().data())
        );
        receiversRegistered = true;
    }

    /** Sends the bytes of one serverbound sync message; dropped when the server lacks the mod. */
    public static void sendSyncToServer(byte[] data) {
        if (ClientPlayNetworking.canSend(SyncToServerPayload.TYPE.id())) {
            ClientPlayNetworking.send(new SyncToServerPayload(new SyncBlob(data)));
        }
    }

    public static void setClientHandler(ClientPacketHandler handler) {
        CLIENT_HANDLER.set(Objects.requireNonNull(handler, "handler"));
    }

    public static boolean clearClientHandler(ClientPacketHandler expected) {
        return CLIENT_HANDLER.compareAndSet(
                Objects.requireNonNull(expected, "expected"),
                null
        );
    }

    static void handleClientPlayback(ClientboundPlaybackState message) {
        ClientPacketHandler handler = requireClientHandler();
        handler.onPlayback(message.revision(), message.state());
    }

    static void handleClientReload(ClientboundReloadMusic message) {
        ClientPacketHandler handler = requireClientHandler();
        handler.onReload(message.revision());
    }

    private static void receivePlayback(
            PlaybackPayload payload,
            ClientPlayNetworking.Context context
    ) {
        handleClientPlayback(payload.message());
    }

    private static void receiveReload(
            ReloadPayload payload,
            ClientPlayNetworking.Context context
    ) {
        handleClientReload(payload.message());
    }

    private static ClientPacketHandler requireClientHandler() {
        ClientPacketHandler handler = CLIENT_HANDLER.get();
        if (handler == null) {
            throw new IllegalStateException("AreaMusic client packet handler is not installed");
        }
        return handler;
    }
}
