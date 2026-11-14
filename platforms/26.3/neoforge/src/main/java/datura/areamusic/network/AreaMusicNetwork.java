package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import datura.areamusic.server.AreaMusicServer;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Registers the typed payloads and schedules their handling on the main thread of each side. */
public final class AreaMusicNetwork {
    private static final String PROTOCOL_VERSION = "6";
    private static final AtomicReference<ClientPacketHandler> CLIENT_HANDLER =
            new AtomicReference<>();
    private static final PayloadRegistration REGISTRATION = new PayloadRegistration();

    private AreaMusicNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        REGISTRATION.register(event);
    }

    static void registerPayloads(RegistrarFactory registrarFactory) {
        Objects.requireNonNull(registrarFactory, "registrarFactory");
        ClientboundRegistrar registrar = Objects.requireNonNull(
                registrarFactory.create(PROTOCOL_VERSION, HandlerThread.MAIN),
                "registrar"
        );
        registrar.playToClient(
                PlaybackPayload.TYPE,
                PlaybackPayload.STREAM_CODEC,
                (message, context) -> handleClientPlayback(message.message())
        );
        registrar.playToClient(
                ReloadPayload.TYPE,
                ReloadPayload.STREAM_CODEC,
                (message, context) -> handleClientReload(message.message())
        );
        registrar.playToClient(
                SyncToClientPayload.TYPE,
                SyncToClientPayload.STREAM_CODEC,
                (message, context) -> requireClientHandler().onSync(message.blob().data())
        );
        registrar.playToServer(
                SyncToServerPayload.TYPE,
                SyncToServerPayload.STREAM_CODEC,
                (message, context) -> {
                    if (context.player() instanceof ServerPlayer player) {
                        AreaMusicServer.onSyncMessage(player, message.blob().data());
                    }
                }
        );
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
        PacketDistributor.sendToPlayer(
                Objects.requireNonNull(player, "player"),
                new PlaybackPayload(new ClientboundPlaybackState(
                        revision,
                        Objects.requireNonNull(state, "state")
                ))
        );
    }

    public static void sendReload(ServerPlayer player, long revision) {
        PacketDistributor.sendToPlayer(
                Objects.requireNonNull(player, "player"),
                new ReloadPayload(new ClientboundReloadMusic(revision))
        );
    }

    public static void sendSync(ServerPlayer player, byte[] data) {
        PacketDistributor.sendToPlayer(
                Objects.requireNonNull(player, "player"),
                new SyncToClientPayload(new SyncBlob(data))
        );
    }

    static void handleClientPlayback(ClientboundPlaybackState message) {
        requireClientHandler().onPlayback(message.revision(), message.state());
    }

    static void handleClientReload(ClientboundReloadMusic message) {
        requireClientHandler().onReload(message.revision());
    }

    private static ClientPacketHandler requireClientHandler() {
        ClientPacketHandler handler = CLIENT_HANDLER.get();
        if (handler == null) {
            throw new IllegalStateException("AreaMusic client packet handler is not installed");
        }
        return handler;
    }

    private static final class PayloadRegistration {
        private State state = State.NEW;
        private Throwable failure;

        synchronized void register(RegisterPayloadHandlersEvent event) {
            Objects.requireNonNull(event, "event");
            switch (state) {
                case REGISTERED -> {
                    return;
                }
                case FAILED -> throw permanentFailure();
                case REGISTERING -> throw new IllegalStateException(
                        "AreaMusic payload registration is already in progress"
                );
                case NEW -> registerOnce(event);
            }
        }

        private void registerOnce(RegisterPayloadHandlersEvent event) {
            state = State.REGISTERING;
            try {
                registerPayloads((protocolVersion, handlerThread) ->
                        new NeoForgeClientboundRegistrar(
                                event.registrar(protocolVersion).executesOn(handlerThread)
                        )
                );
                state = State.REGISTERED;
            } catch (RuntimeException | LinkageError registrationFailure) {
                failure = registrationFailure;
                state = State.FAILED;
                throw permanentFailure();
            }
        }

        private IllegalStateException permanentFailure() {
            return new IllegalStateException(
                    "AreaMusic payload registration failed permanently; " +
                            "NeoForge registry state may already have been mutated",
                    failure
            );
        }
    }

    @FunctionalInterface
    interface RegistrarFactory {
        ClientboundRegistrar create(String protocolVersion, HandlerThread handlerThread);
    }

    interface ClientboundRegistrar {
        <T extends CustomPacketPayload> void playToClient(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                IPayloadHandler<T> handler
        );

        <T extends CustomPacketPayload> void playToServer(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                IPayloadHandler<T> handler
        );
    }

    private record NeoForgeClientboundRegistrar(PayloadRegistrar delegate)
            implements ClientboundRegistrar {
        private NeoForgeClientboundRegistrar {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public <T extends CustomPacketPayload> void playToClient(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                IPayloadHandler<T> handler
        ) {
            delegate.playToClient(type, codec, handler);
        }

        @Override
        public <T extends CustomPacketPayload> void playToServer(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                IPayloadHandler<T> handler
        ) {
            delegate.playToServer(type, codec, handler);
        }
    }

    private enum State {
        NEW,
        REGISTERING,
        REGISTERED,
        FAILED
    }

    public record PlaybackPayload(ClientboundPlaybackState message)
            implements CustomPacketPayload {
        public static final Type<PlaybackPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("areamusic", "playback")
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

    public record ReloadPayload(ClientboundReloadMusic message)
            implements CustomPacketPayload {
        public static final Type<ReloadPayload> TYPE = new Type<>(
                Identifier.fromNamespaceAndPath("areamusic", "reload")
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
                Identifier.fromNamespaceAndPath("areamusic", "sync_s2c")
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
                Identifier.fromNamespaceAndPath("areamusic", "sync_c2s")
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
