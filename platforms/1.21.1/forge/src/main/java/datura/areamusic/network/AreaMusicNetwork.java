package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class AreaMusicNetwork {
    private static final String MOD_ID = "areamusic";
    private static final int PROTOCOL_VERSION = 3;
    private static final AtomicReference<ClientPacketHandler> CLIENT_HANDLER =
            new AtomicReference<>();

    private static Channel<CustomPacketPayload> channel;

    private AreaMusicNetwork() {
    }

    public static synchronized void register() {
        if (channel != null) {
            return;
        }

        channel = ChannelBuilder
                .named(ResourceLocation.fromNamespaceAndPath(MOD_ID, "main"))
                .networkProtocolVersion(PROTOCOL_VERSION)
                .payloadChannel()
                .play()
                .clientbound()
                .addMain(
                        PlaybackPayload.TYPE,
                        PlaybackPayload.STREAM_CODEC,
                        (payload, context) -> handleClientPlayback(payload.message())
                )
                .addMain(
                        ReloadPayload.TYPE,
                        ReloadPayload.STREAM_CODEC,
                        (payload, context) -> handleClientReload(payload.message())
                )
                .build();
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

    public static void sendPlayback(ServerPlayer player, long revision, PlaybackState state) {
        requireChannel().send(
                new PlaybackPayload(new ClientboundPlaybackState(
                        revision,
                        Objects.requireNonNull(state, "state")
                )),
                Objects.requireNonNull(player, "player").connection.getConnection()
        );
    }

    public static void sendReload(ServerPlayer player, long revision) {
        requireChannel().send(
                new ReloadPayload(new ClientboundReloadMusic(revision)),
                Objects.requireNonNull(player, "player").connection.getConnection()
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

    private static ClientPacketHandler requireClientHandler() {
        ClientPacketHandler handler = CLIENT_HANDLER.get();
        if (handler == null) {
            throw new IllegalStateException("AreaMusic client packet handler is not installed");
        }
        return handler;
    }

    private static Channel<CustomPacketPayload> requireChannel() {
        if (channel == null) {
            throw new IllegalStateException("AreaMusic network channel has not been registered");
        }
        return channel;
    }

    public record PlaybackPayload(
            ClientboundPlaybackState message
    ) implements CustomPacketPayload {
        public static final Type<PlaybackPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "playback")
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
                ResourceLocation.fromNamespaceAndPath(MOD_ID, "reload")
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
}
