package datura.areamusic.network;

import datura.areamusic.area.AreaTrackDefinition;
import datura.areamusic.playback.PlaybackState;
import io.netty.buffer.Unpooled;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.handling.IPayloadHandler;
import net.neoforged.neoforge.network.registration.HandlerThread;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.InputStream;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicNetworkTest {
    @Test
    void typedPayloadsUseStableDistinctIds() {
        assertEquals("areamusic:playback", AreaMusicNetwork.PlaybackPayload.TYPE.id().toString());
        assertEquals("areamusic:reload", AreaMusicNetwork.ReloadPayload.TYPE.id().toString());
        assertFalse(AreaMusicNetwork.PlaybackPayload.TYPE.equals(AreaMusicNetwork.ReloadPayload.TYPE));
    }

    @Test
    void typedStreamCodecsRoundTripParallelPlaylistStoppedAndReloadPayloads() {
        List<AreaMusicNetwork.PlaybackPayload> playbackCases = List.of(
                new AreaMusicNetwork.PlaybackPayload(new ClientboundPlaybackState(
                        11L,
                        PlaybackState.playing(
                                "cavern",
                                List.of(new AreaTrackDefinition(
                                        "ambient/cave.ogg", 3, 0.75f, true, 250, 500
                                )),
                                true
                        )
                )),
                new AreaMusicNetwork.PlaybackPayload(new ClientboundPlaybackState(
                        12L,
                        PlaybackState.playingPlaylistLoop(
                                "village",
                                List.of("day.ogg", "night.ogg"),
                                0.5f,
                                1000,
                                1500,
                                false
                        )
                )),
                new AreaMusicNetwork.PlaybackPayload(new ClientboundPlaybackState(
                        13L,
                        PlaybackState.stopped()
                ))
        );

        for (AreaMusicNetwork.PlaybackPayload payload : playbackCases) {
            assertEquals(payload, roundTrip(AreaMusicNetwork.PlaybackPayload.STREAM_CODEC, payload));
        }
        AreaMusicNetwork.ReloadPayload reload = new AreaMusicNetwork.ReloadPayload(
                new ClientboundReloadMusic(14L)
        );
        assertEquals(reload, roundTrip(AreaMusicNetwork.ReloadPayload.STREAM_CODEC, reload));
    }

    @Test
    void registrationUsesProtocolThreeTwoMainThreadClientboundPayloadsAndDispatchesHandlers() {
        AtomicReference<String> protocol = new AtomicReference<>();
        AtomicReference<HandlerThread> thread = new AtomicReference<>();
        RecordingClientboundRegistrar registrar = new RecordingClientboundRegistrar();

        AreaMusicNetwork.registerPayloads((requestedProtocol, requestedThread) -> {
            protocol.set(requestedProtocol);
            thread.set(requestedThread);
            return registrar;
        });

        assertEquals("3", protocol.get());
        assertSame(HandlerThread.MAIN, thread.get());
        assertEquals(2, registrar.registrations.size());
        CapturedRegistration<?> playback = registrar.registrations.get(0);
        CapturedRegistration<?> reload = registrar.registrations.get(1);
        assertSame(AreaMusicNetwork.PlaybackPayload.TYPE, playback.type);
        assertSame(AreaMusicNetwork.PlaybackPayload.STREAM_CODEC, playback.codec);
        assertSame(AreaMusicNetwork.ReloadPayload.TYPE, reload.type);
        assertSame(AreaMusicNetwork.ReloadPayload.STREAM_CODEC, reload.codec);

        List<String> calls = new ArrayList<>();
        ClientPacketHandler handler = new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
                calls.add("playback:" + revision);
            }

            @Override
            public void onReload(long revision) {
                calls.add("reload:" + revision);
            }
        };
        AreaMusicNetwork.setClientHandler(handler);
        try {
            registrar.dispatch(0, new AreaMusicNetwork.PlaybackPayload(
                    new ClientboundPlaybackState(21L, PlaybackState.stopped())
            ));
            registrar.dispatch(1, new AreaMusicNetwork.ReloadPayload(
                    new ClientboundReloadMusic(22L)
            ));
        } finally {
            AreaMusicNetwork.clearClientHandler(handler);
        }

        assertEquals(List.of("playback:21", "reload:22"), calls);
    }

    @Test
    void productionRegistrarAdapterCallsNeoForgePlayToClient() throws Exception {
        String resource = "datura/areamusic/network/AreaMusicNetwork$"
                + "NeoForgeClientboundRegistrar.class";
        AtomicBoolean found = new AtomicBoolean();
        try (InputStream input = AreaMusicNetworkTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertTrue(input != null, "compiled production registrar adapter is missing");
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                ) {
                    if (!"playToClient".equals(name)) {
                        return null;
                    }
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                                int opcode,
                                String owner,
                                String invokedName,
                                String invokedDescriptor,
                                boolean isInterface
                        ) {
                            if ("net/neoforged/neoforge/network/registration/PayloadRegistrar"
                                    .equals(owner) && "playToClient".equals(invokedName)) {
                                found.set(true);
                            }
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        }
        assertTrue(found.get(), "production adapter does not call PayloadRegistrar.playToClient");
    }

    @Test
    void replacementHandlerReceivesClientboundMessagesAndStaleClearCannotRemoveIt() {
        List<String> calls = new ArrayList<>();
        ClientPacketHandler oldHandler = new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
            }

            @Override
            public void onReload(long revision) {
            }
        };
        ClientPacketHandler replacement = new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
                calls.add("playback:" + revision);
            }

            @Override
            public void onReload(long revision) {
                calls.add("reload:" + revision);
            }
        };

        AreaMusicNetwork.setClientHandler(oldHandler);
        AreaMusicNetwork.setClientHandler(replacement);
        try {
            assertFalse(AreaMusicNetwork.clearClientHandler(oldHandler));
            AreaMusicNetwork.handleClientReload(new ClientboundReloadMusic(7L));
            AreaMusicNetwork.handleClientPlayback(
                    new ClientboundPlaybackState(8L, PlaybackState.stopped())
            );
        } finally {
            AreaMusicNetwork.clearClientHandler(replacement);
        }

        assertEquals(List.of("reload:7", "playback:8"), calls);
    }

    @Test
    void dispatchFailsClearlyUntilTheClientLifecycleInstallsAHandler() {
        assertThrows(
                IllegalStateException.class,
                () -> AreaMusicNetwork.handleClientReload(new ClientboundReloadMusic(1L))
        );
    }

    private static <T> T roundTrip(
            StreamCodec<RegistryFriendlyByteBuf, T> codec,
            T value
    ) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(
                Unpooled.buffer(),
                RegistryAccess.EMPTY
        );
        try {
            codec.encode(buffer, value);
            return codec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static final class RecordingClientboundRegistrar
            implements AreaMusicNetwork.ClientboundRegistrar {
        private final List<CapturedRegistration<?>> registrations = new ArrayList<>();

        @Override
        public <T extends CustomPacketPayload> void playToClient(
                CustomPacketPayload.Type<T> type,
                StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
                IPayloadHandler<T> handler
        ) {
            registrations.add(new CapturedRegistration<>(type, codec, handler));
        }

        private <T extends CustomPacketPayload> void dispatch(int index, T payload) {
            @SuppressWarnings("unchecked")
            CapturedRegistration<T> registration =
                    (CapturedRegistration<T>) registrations.get(index);
            IPayloadContext mainThreadContext = (IPayloadContext) Proxy.newProxyInstance(
                    IPayloadContext.class.getClassLoader(),
                    new Class<?>[]{IPayloadContext.class},
                    (proxy, method, arguments) -> {
                        throw new AssertionError(
                                "MAIN-thread payload handler unexpectedly called context."
                                        + method.getName()
                        );
                    }
            );
            registration.handler.handle(payload, mainThreadContext);
        }
    }

    private record CapturedRegistration<T extends CustomPacketPayload>(
            CustomPacketPayload.Type<T> type,
            StreamCodec<? super RegistryFriendlyByteBuf, T> codec,
            IPayloadHandler<T> handler
    ) {
    }
}
