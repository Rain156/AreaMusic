package datura.areamusic.fabric.network;

import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.FriendlyByteBuf;

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
                FabricAreaMusicNetwork.PLAYBACK_CHANNEL,
                FabricClientNetworking::receivePlayback
        );
        if (!playbackRegistered) {
            throw new IllegalStateException(
                    "AreaMusic playback receiver was already registered by another owner"
            );
        }

        boolean reloadRegistered = ClientPlayNetworking.registerGlobalReceiver(
                FabricAreaMusicNetwork.RELOAD_CHANNEL,
                FabricClientNetworking::receiveReload
        );
        if (!reloadRegistered) {
            ClientPlayNetworking.unregisterGlobalReceiver(FabricAreaMusicNetwork.PLAYBACK_CHANNEL);
            throw new IllegalStateException(
                    "AreaMusic reload receiver was already registered by another owner"
            );
        }
        receiversRegistered = true;
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
            Minecraft client,
            ClientPacketListener networkHandler,
            FriendlyByteBuf buffer,
            PacketSender responseSender
    ) {
        ClientboundPlaybackState message = ClientboundPlaybackState.decode(buffer);
        client.execute(() -> handleClientPlayback(message));
    }

    private static void receiveReload(
            Minecraft client,
            ClientPacketListener networkHandler,
            FriendlyByteBuf buffer,
            PacketSender responseSender
    ) {
        ClientboundReloadMusic message = ClientboundReloadMusic.decode(buffer);
        client.execute(() -> handleClientReload(message));
    }

    private static ClientPacketHandler requireClientHandler() {
        ClientPacketHandler handler = CLIENT_HANDLER.get();
        if (handler == null) {
            throw new IllegalStateException("AreaMusic client packet handler is not installed");
        }
        return handler;
    }
}
