package datura.areamusic.network;

import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class AreaMusicNetworkTest {
    private static final String OWNER = "datura/areamusic/network/AreaMusicNetwork";

    @Test
    void exposesIndependentTypedPlaybackAndReloadPayloads() throws Throwable {
        Class<?> playback = requiredClass(
                "datura.areamusic.network.AreaMusicNetwork$PlaybackPayload"
        );
        Class<?> reload = requiredClass(
                "datura.areamusic.network.AreaMusicNetwork$ReloadPayload"
        );

        Object playbackType = playback.getField("TYPE").get(null);
        Object reloadType = reload.getField("TYPE").get(null);
        Method id = playbackType.getClass().getMethod("id");

        assertEquals("areamusic:playback", invoke(id, playbackType).toString());
        assertEquals("areamusic:reload", invoke(id, reloadType).toString());
        assertNotEquals(playbackType, reloadType);
        assertNotNull(playback.getField("STREAM_CODEC").get(null));
        assertNotNull(reload.getField("STREAM_CODEC").get(null));
        assertTrue(net.minecraft.network.protocol.common.custom.CustomPacketPayload.class
                .isAssignableFrom(playback));
        assertTrue(net.minecraft.network.protocol.common.custom.CustomPacketPayload.class
                .isAssignableFrom(reload));
    }

    @Test
    void typedCodecsDelegateToTheMinecraft1211CommonSchemaV3Codecs() throws IOException {
        ClassData playback = read(OWNER + "$PlaybackPayload.class");
        ClassData reload = read(OWNER + "$ReloadPayload.class");

        assertAnyCall(playback, "net/minecraft/network/codec/StreamCodec#of");
        assertAnyCall(playback, "datura/areamusic/network/ClientboundPlaybackState#encode");
        assertAnyCall(playback, "datura/areamusic/network/ClientboundPlaybackState#decode");
        assertAnyCall(reload, "net/minecraft/network/codec/StreamCodec#of");
        assertAnyCall(reload, "datura/areamusic/network/ClientboundReloadMusic#encode");
        assertAnyCall(reload, "datura/areamusic/network/ClientboundReloadMusic#decode");
    }

    @Test
    void registrationIsSynchronizedIdempotentTypedAndClientThreadDispatched() throws IOException {
        ClassData network = read(OWNER + ".class");
        MethodData register = network.method("register");

        assertTrue(Modifier.isPublic(register.access));
        assertTrue(Modifier.isStatic(register.access));
        assertTrue(Modifier.isSynchronized(register.access));
        assertCall(register, "net/minecraftforge/network/ChannelBuilder#named");
        assertCall(register, "net/minecraftforge/network/ChannelBuilder#networkProtocolVersion");
        assertCall(register, "net/minecraftforge/network/ChannelBuilder#payloadChannel");
        assertCall(register, "net/minecraftforge/network/payload/PayloadConnection#play");
        assertCall(register, "net/minecraftforge/network/payload/PayloadProtocol#clientbound");
        assertEquals(2, register.calls.stream()
                .filter(call -> call.startsWith(
                        "net/minecraftforge/network/payload/PayloadFlow#addMain"
                )).count());
        assertCall(register, "net/minecraftforge/network/payload/PayloadFlow#build");
        assertTrue(register.fields.stream().anyMatch(field ->
                field.equals(OWNER + "#channel:Lnet/minecraftforge/network/Channel;")));
        assertFalse(register.calls.stream().anyMatch(call ->
                call.contains("net/minecraftforge/network/simple/SimpleChannel")));
    }

    @Test
    void clientDispatchUsesReplacementHandlerAndStaleClearCannotRemoveIt() throws Throwable {
        Class<?> network = requiredClass("datura.areamusic.network.AreaMusicNetwork");
        Method set = network.getMethod("setClientHandler", ClientPacketHandler.class);
        Method clear = network.getMethod("clearClientHandler", ClientPacketHandler.class);
        Method reload = declaredMethod(
                network,
                "handleClientReload",
                ClientboundReloadMusic.class
        );
        Method playback = declaredMethod(
                network,
                "handleClientPlayback",
                ClientboundPlaybackState.class
        );
        List<String> calls = new ArrayList<>();
        ClientPacketHandler oldHandler = recordingHandler(new ArrayList<>());
        ClientPacketHandler replacement = recordingHandler(calls);

        invoke(set, null, oldHandler);
        invoke(set, null, replacement);
        try {
            assertFalse((Boolean) invoke(clear, null, oldHandler));
            invoke(reload, null, new ClientboundReloadMusic(9L));
            invoke(playback, null, new ClientboundPlaybackState(10L, PlaybackState.stopped()));
        } finally {
            invoke(clear, null, replacement);
        }

        assertEquals(List.of("onReload:9", "onPlayback:10"), calls);
    }

    @Test
    void serverSendersConstructTypedPayloadsAndTargetThePlayerConnection()
            throws IOException {
        ClassData network = read(OWNER + ".class");
        MethodData playback = network.method("sendPlayback");
        assertCall(playback, OWNER + "#requireChannel");
        assertCall(playback, OWNER + "$PlaybackPayload#<init>");
        assertCall(playback,
                "datura/areamusic/network/ClientboundPlaybackState#<init>");
        assertCall(playback,
                "net/minecraft/server/network/ServerGamePacketListenerImpl#getConnection");
        assertCall(playback, "net/minecraftforge/network/Channel#send");

        MethodData reload = network.method("sendReload");
        assertCall(reload, OWNER + "#requireChannel");
        assertCall(reload, OWNER + "$ReloadPayload#<init>");
        assertCall(reload, "datura/areamusic/network/ClientboundReloadMusic#<init>");
        assertCall(reload,
                "net/minecraft/server/network/ServerGamePacketListenerImpl#getConnection");
        assertCall(reload, "net/minecraftforge/network/Channel#send");
    }

    private static ClientPacketHandler recordingHandler(List<String> calls) {
        return new ClientPacketHandler() {
            @Override
            public void onPlayback(long revision, PlaybackState state) {
                calls.add("onPlayback:" + revision);
            }

            @Override
            public void onReload(long revision) {
                calls.add("onReload:" + revision);
            }
        };
    }

    private static Class<?> requiredClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException missing) {
            return fail("required class is missing: " + name, missing);
        }
    }

    private static Method declaredMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            Method method = type.getDeclaredMethod(name, parameters);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            return fail("required method is missing: " + type.getName() + "#" + name, missing);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... arguments) throws Throwable {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        }
    }

    private static void assertCall(MethodData method, String prefix) {
        assertTrue(method.calls.stream().anyMatch(call -> call.startsWith(prefix)),
                () -> method.name + " does not call " + prefix + "; calls were " + method.calls);
    }

    private static void assertAnyCall(ClassData data, String prefix) {
        assertTrue(data.methods.values().stream()
                        .flatMap(List::stream)
                        .flatMap(method -> method.calls.stream())
                        .anyMatch(call -> call.startsWith(prefix)),
                () -> data.name + " has no call to " + prefix);
    }

    private static ClassData read(String resourceName) throws IOException {
        try (InputStream input = AreaMusicNetworkTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "compiled network class is missing: " + resourceName);
            ClassData data = new ClassData();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public void visit(
                        int version,
                        int access,
                        String name,
                        String signature,
                        String superName,
                        String[] interfaces
                ) {
                    data.name = name;
                }

                @Override
                public MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                ) {
                    MethodData method = new MethodData(access, name);
                    data.methods.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(
                                int opcode,
                                String owner,
                                String invokedName,
                                String invokedDescriptor,
                                boolean isInterface
                        ) {
                            method.calls.add(owner + "#" + invokedName + invokedDescriptor);
                        }

                        @Override
                        public void visitFieldInsn(
                                int opcode,
                                String owner,
                                String fieldName,
                                String fieldDescriptor
                        ) {
                            method.fields.add(owner + "#" + fieldName + ":" + fieldDescriptor);
                        }
                    };
                }
            }, 0);
            return data;
        }
    }

    private static final class ClassData {
        private String name;
        private final Map<String, List<MethodData>> methods = new HashMap<>();

        private MethodData method(String name) {
            List<MethodData> matches = methods.get(name);
            assertNotNull(matches, "method is missing from " + this.name + ": " + name);
            assertEquals(1, matches.size(), "method is overloaded: " + name);
            return matches.get(0);
        }
    }

    private static final class MethodData {
        private final int access;
        private final String name;
        private final List<String> calls = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();

        private MethodData(int access, String name) {
            this.access = access;
            this.name = name;
        }
    }
}
