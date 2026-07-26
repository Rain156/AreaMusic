package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Registers only clientbound typed payloads and schedules their handling on the client thread. */
public final class AreaMusicNetwork {
    private static final String PROTOCOL_VERSION = "3";
    private static final AtomicReference<ClientPacketHandler> CLIENT_HANDLER =
            new AtomicReference<>();
    private static final PayloadRegistration REGISTRATION = new PayloadRegistration();

    private AreaMusicNetwork() {
    }

    public static void register(RegisterPayloadHandlersEvent event) {
        REGISTRATION.register(event);
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
                PayloadRegistrar registrar = event.registrar(PROTOCOL_VERSION);
                registrar.playToClient(
                        PlaybackPayload.TYPE,
                        PlaybackPayload.STREAM_CODEC,
                        (message, context) -> context.enqueueWork(
                                () -> handleClientPlayback(message.message())
                        )
                );
                registrar.playToClient(
                        ReloadPayload.TYPE,
                        ReloadPayload.STREAM_CODEC,
                        (message, context) -> context.enqueueWork(
                                () -> handleClientReload(message.message())
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

    private enum State {
        NEW,
        REGISTERING,
        REGISTERED,
        FAILED
    }

    public record PlaybackPayload(ClientboundPlaybackState message)
            implements CustomPacketPayload {
        public static final Type<PlaybackPayload> TYPE = new Type<>(
                ResourceLocation.fromNamespaceAndPath("areamusic", "playback")
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
                ResourceLocation.fromNamespaceAndPath("areamusic", "reload")
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
