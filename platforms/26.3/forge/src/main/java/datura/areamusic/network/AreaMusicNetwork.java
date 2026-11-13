package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import datura.areamusic.server.AreaMusicServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.Channel;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

public final class AreaMusicNetwork {
    private static final String MOD_ID = "areamusic";
    private static final int PROTOCOL_VERSION = 4;
    private static final AtomicReference<ClientPacketHandler> CLIENT_HANDLER =
            new AtomicReference<>();

    private static final AreaMusicChannelRegistration<Channel<CustomPacketPayload>>
            CHANNEL_REGISTRATION = new AreaMusicChannelRegistration<>(
                    "AreaMusic network channel",
                    AreaMusicNetwork::buildChannel
            );

    private AreaMusicNetwork() {
    }

    public static void register() {
        CHANNEL_REGISTRATION.register();
    }

    private static Channel<CustomPacketPayload> buildChannel() {
        validateRegistrationInputs();
        return ChannelBuilder
                .named(Identifier.fromNamespaceAndPath(MOD_ID, "main"))
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
                .addMain(
                        SyncToClientPayload.TYPE,
                        SyncToClientPayload.STREAM_CODEC,
                        (payload, context) -> requireClientHandler().onSync(payload.blob().data())
                )
                .serverbound()
                .addMain(
                        SyncToServerPayload.TYPE,
                        SyncToServerPayload.STREAM_CODEC,
                        (payload, context) -> {
                            ServerPlayer sender = context.getSender();
                            if (sender != null) {
                                AreaMusicServer.onSyncMessage(sender, payload.blob().data());
                            }
                        }
                )
                .build();
    }

    private static void validateRegistrationInputs() {
        CustomPacketPayload.Type<PlaybackPayload> playbackType =
                Objects.requireNonNull(PlaybackPayload.TYPE, "playback payload type");
        CustomPacketPayload.Type<ReloadPayload> reloadType =
                Objects.requireNonNull(ReloadPayload.TYPE, "reload payload type");
        Objects.requireNonNull(PlaybackPayload.STREAM_CODEC, "playback payload codec");
        Objects.requireNonNull(ReloadPayload.STREAM_CODEC, "reload payload codec");

        Identifier expectedPlayback = Identifier.fromNamespaceAndPath(
                MOD_ID,
                "playback"
        );
        Identifier expectedReload = Identifier.fromNamespaceAndPath(
                MOD_ID,
                "reload"
        );
        if (!expectedPlayback.equals(playbackType.id())) {
            throw new IllegalStateException(
                    "playback payload ID must be " + expectedPlayback + ", found " +
                            playbackType.id()
            );
        }
        if (!expectedReload.equals(reloadType.id())) {
            throw new IllegalStateException(
                    "reload payload ID must be " + expectedReload + ", found " + reloadType.id()
            );
        }
        if (playbackType.id().equals(reloadType.id())) {
            throw new IllegalStateException("playback and reload payload IDs must be distinct");
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

    public static void sendSync(ServerPlayer player, byte[] data) {
        requireChannel().send(
                new SyncToClientPayload(new SyncBlob(data)),
                Objects.requireNonNull(player, "player").connection.getConnection()
        );
    }

    /** Client only. */
    public static void sendSyncToServer(byte[] data) {
        requireChannel().send(
                new SyncToServerPayload(new SyncBlob(data)),
                PacketDistributor.SERVER.noArg()
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
        return CHANNEL_REGISTRATION.requireRegistered();
    }

    public record PlaybackPayload(
            ClientboundPlaybackState message
    ) implements CustomPacketPayload {
        public static final Type<PlaybackPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath(MOD_ID, "playback")
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
                Identifier.fromNamespaceAndPath(MOD_ID, "reload")
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
                Identifier.fromNamespaceAndPath(MOD_ID, "sync_s2c")
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
                Identifier.fromNamespaceAndPath(MOD_ID, "sync_c2s")
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
