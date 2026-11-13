package datura.areamusic.fabric.network;

import datura.areamusic.fabric.AreaMusicFabric;
import datura.areamusic.fabric.AreaMusicFabric.RegistrationGuard;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import datura.areamusic.network.SyncBlob;
import datura.areamusic.server.AreaMusicServer;
import datura.areamusic.playback.PlaybackState;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.Objects;

public final class FabricAreaMusicNetwork {
    private static final RegistrationGuard REGISTRATION = new RegistrationGuard(
            "AreaMusic Fabric networking has not been registered"
    );

    private FabricAreaMusicNetwork() {
    }

    public static synchronized void register() {
        if (REGISTRATION.isRegistered()) {
            return;
        }

        PayloadTypeRegistry.playS2C().register(
                PlaybackPayload.TYPE,
                PlaybackPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                ReloadPayload.TYPE,
                ReloadPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.playS2C().register(
                SyncToClientPayload.TYPE,
                SyncToClientPayload.STREAM_CODEC
        );
        PayloadTypeRegistry.playC2S().register(
                SyncToServerPayload.TYPE,
                SyncToServerPayload.STREAM_CODEC
        );
        ServerPlayNetworking.registerGlobalReceiver(
                SyncToServerPayload.TYPE,
                (payload, context) -> AreaMusicServer.onSyncMessage(
                        context.player(), payload.blob().data())
        );
        REGISTRATION.register();
    }

    public static void sendReload(ServerPlayer player, long revision) {
        requireRegistered();
        ServerPlayNetworking.send(
                Objects.requireNonNull(player, "player"),
                new ReloadPayload(new ClientboundReloadMusic(revision))
        );
    }

    public static void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
        requireRegistered();
        ServerPlayNetworking.send(
                Objects.requireNonNull(player, "player"),
                new PlaybackPayload(new ClientboundPlaybackState(
                        revision,
                        Objects.requireNonNull(state, "state")
                ))
        );
    }

    public static void sendSync(ServerPlayer player, byte[] data) {
        requireRegistered();
        ServerPlayNetworking.send(
                Objects.requireNonNull(player, "player"),
                new SyncToClientPayload(new SyncBlob(data))
        );
    }

    static void requireRegistered() {
        REGISTRATION.requireRegistered();
    }

    public record PlaybackPayload(
            ClientboundPlaybackState message
    ) implements CustomPacketPayload {
        public static final Type<PlaybackPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AreaMusicFabric.MOD_ID, "playback")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, PlaybackPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buffer, payload) -> ClientboundPlaybackState.encode(
                                payload.message,
                                buffer
                        ),
                        buffer -> new PlaybackPayload(ClientboundPlaybackState.decode(buffer))
                );

        public PlaybackPayload {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public Type<PlaybackPayload> type() {
            return TYPE;
        }
    }

    public record ReloadPayload(
            ClientboundReloadMusic message
    ) implements CustomPacketPayload {
        public static final Type<ReloadPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AreaMusicFabric.MOD_ID, "reload")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, ReloadPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buffer, payload) -> ClientboundReloadMusic.encode(
                                payload.message,
                                buffer
                        ),
                        buffer -> new ReloadPayload(ClientboundReloadMusic.decode(buffer))
                );

        public ReloadPayload {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public Type<ReloadPayload> type() {
            return TYPE;
        }
    }

    public record SyncToClientPayload(SyncBlob blob) implements CustomPacketPayload {
        public static final Type<SyncToClientPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AreaMusicFabric.MOD_ID, "sync_s2c")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncToClientPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buffer, payload) -> SyncBlob.encode(payload.blob, buffer),
                        buffer -> new SyncToClientPayload(SyncBlob.decodeClientbound(buffer))
                );

        public SyncToClientPayload {
            Objects.requireNonNull(blob, "blob");
        }

        @Override
        public Type<SyncToClientPayload> type() {
            return TYPE;
        }
    }

    public record SyncToServerPayload(SyncBlob blob) implements CustomPacketPayload {
        public static final Type<SyncToServerPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(AreaMusicFabric.MOD_ID, "sync_c2s")
        );
        public static final StreamCodec<RegistryFriendlyByteBuf, SyncToServerPayload> STREAM_CODEC =
                StreamCodec.of(
                        (buffer, payload) -> SyncBlob.encode(payload.blob, buffer),
                        buffer -> new SyncToServerPayload(SyncBlob.decodeServerbound(buffer))
                );

        public SyncToServerPayload {
            Objects.requireNonNull(blob, "blob");
        }

        @Override
        public Type<SyncToServerPayload> type() {
            return TYPE;
        }
    }
}
