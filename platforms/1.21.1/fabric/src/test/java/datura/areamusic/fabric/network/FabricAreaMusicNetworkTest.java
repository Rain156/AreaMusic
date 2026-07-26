package datura.areamusic.fabric.network;

import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import datura.areamusic.playback.PlaybackState;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class FabricAreaMusicNetworkTest {
    private static final String NETWORK_OWNER =
            "datura/areamusic/fabric/network/FabricAreaMusicNetwork";
    private static final String CLIENT_NETWORK_OWNER =
            "datura/areamusic/fabric/network/FabricClientNetworking";
    private static final String GUARD_OWNER =
            "datura/areamusic/fabric/AreaMusicFabric$RegistrationGuard";

    @Test
    void usesIndependentTypedPlaybackAndReloadPayloads() throws Throwable {
        Class<?> playbackPayload = requiredClass(
                "datura.areamusic.fabric.network.FabricAreaMusicNetwork$PlaybackPayload"
        );
        Class<?> reloadPayload = requiredClass(
                "datura.areamusic.fabric.network.FabricAreaMusicNetwork$ReloadPayload"
        );

        Object playbackType = requiredField(playbackPayload, "TYPE").get(null);
        Object reloadType = requiredField(reloadPayload, "TYPE").get(null);
        Object playbackId = invoke(requiredMethod(playbackType.getClass(), "id"), playbackType);
        Object reloadId = invoke(requiredMethod(reloadType.getClass(), "id"), reloadType);

        assertEquals("areamusic:playback", playbackId.toString());
        assertEquals("areamusic:reload", reloadId.toString());
        assertNotEquals(playbackType, reloadType);
        assertNotNull(requiredField(playbackPayload, "STREAM_CODEC").get(null));
        assertNotNull(requiredField(reloadPayload, "STREAM_CODEC").get(null));
    }

    @Test
    void typedPayloadCodecsDelegateToTheMinecraft1211CommonMessageCodecs() throws IOException {
        ClassData playback = read(NETWORK_OWNER + "$PlaybackPayload.class");
        ClassData reload = read(NETWORK_OWNER + "$ReloadPayload.class");

        for (ClassData payload : List.of(playback, reload)) {
            assertTrue(payload.interfaces.contains(
                    "net/minecraft/network/protocol/common/custom/CustomPacketPayload"
            ));
            assertEquals(
                    "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload$Type;",
                    payload.fields.get("TYPE")
            );
            assertEquals(
                    "Lnet/minecraft/network/codec/StreamCodec;",
                    payload.fields.get("STREAM_CODEC")
            );
            assertField(payload.method("type"), NETWORK_OWNER + "$" + payload.simpleName()
                    + "#TYPE");
            assertAnyCall(payload, "net/minecraft/network/codec/StreamCodec#of");
        }

        assertAnyCall(playback,
                "datura/areamusic/network/ClientboundPlaybackState#encode");
        assertAnyCall(playback,
                "datura/areamusic/network/ClientboundPlaybackState#decode");
        assertAnyCall(reload,
                "datura/areamusic/network/ClientboundReloadMusic#encode");
        assertAnyCall(reload,
                "datura/areamusic/network/ClientboundReloadMusic#decode");
    }

    @Test
    void registrationGuardRequiresRegistrationAndAllowsRepeatedRegistration() throws Throwable {
        Class<?> guardType = requiredClass(
                "datura.areamusic.fabric.AreaMusicFabric$RegistrationGuard"
        );
        Constructor<?> constructor = requiredDeclaredConstructor(guardType, String.class);
        Method requireRegistered = requiredDeclaredMethod(guardType, "requireRegistered");
        Method register = requiredDeclaredMethod(guardType, "register");
        Object guard = constructor.newInstance("fresh test registration");

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> invoke(requireRegistered, guard)
        );
        assertTrue(failure.getMessage().contains("fresh test registration"));
        assertDoesNotThrow(() -> invoke(register, guard));
        assertDoesNotThrow(() -> invoke(register, guard));
        assertDoesNotThrow(() -> invoke(requireRegistered, guard));
    }

    @Test
    void payloadRegistrationIsIdempotentAndMarksSuccessOnlyAfterBothCodecs() throws IOException {
        ClassData network = read(NETWORK_OWNER + ".class");
        MethodData register = network.method("register");

        assertRegistrationShortCircuit(register, GUARD_OWNER);
        List<Integer> registryCalls = instructionIndexesContaining(
                register,
                "CALL:net/fabricmc/fabric/api/networking/v1/PayloadTypeRegistry#register"
        );
        assertEquals(2, registryCalls.size(),
                () -> "register must install exactly two typed payload codecs: "
                        + register.instructions);
        List<Integer> completions = instructionIndexesContaining(
                register,
                "CALL:" + GUARD_OWNER + "#register()V"
        );
        assertEquals(1, completions.size());
        assertTrue(completions.get(0) > registryCalls.get(1),
                () -> "registration must be marked only after both codecs succeed: "
                        + register.instructions);

        MethodData requireRegistered = network.method("requireRegistered");
        assertCall(requireRegistered, GUARD_OWNER + "#requireRegistered");
    }

    @Test
    void mainEntrypointRegistersPayloadsBeforeServerCallbacks() throws IOException {
        MethodData initialize = read(
                "datura/areamusic/fabric/AreaMusicFabric.class"
        ).method("onInitialize");
        int networking = firstInstructionContaining(
                initialize,
                "CALL:" + NETWORK_OWNER + "#register"
        );
        int server = firstInstructionContaining(
                initialize,
                "CALL:datura/areamusic/fabric/server/FabricAreaMusicServer#register"
        );
        assertTrue(networking < server,
                () -> "payload types must be registered before any sender-capable lifecycle: "
                        + initialize.instructions);
    }

    @Test
    void networkAndServerOwnIndependentRegistrationGuardInstances() throws IOException {
        assertOwnRegistrationGuard(
                read(NETWORK_OWNER + ".class"),
                NETWORK_OWNER,
                GUARD_OWNER
        );
        assertOwnRegistrationGuard(
                read("datura/areamusic/fabric/server/FabricAreaMusicServer.class"),
                "datura/areamusic/fabric/server/FabricAreaMusicServer",
                GUARD_OWNER
        );
    }

    @Test
    void serverRegistrationIsIdempotentAndMarksSuccessOnlyAfterEveryCallback() throws IOException {
        MethodData register = read(
                "datura/areamusic/fabric/server/FabricAreaMusicServer.class"
        ).method("register");
        assertRegistrationShortCircuit(register, GUARD_OWNER);
        List<Integer> callbacks = instructionIndexesContaining(
                register,
                "CALL:net/fabricmc/fabric/api/event/Event#register"
        );
        assertFalse(callbacks.isEmpty(),
                () -> "server registration must install Fabric callbacks: "
                        + register.instructions);
        List<Integer> completions = instructionIndexesContaining(
                register,
                "CALL:" + GUARD_OWNER + "#register()V"
        );
        assertEquals(1, completions.size());
        assertTrue(completions.get(0) > callbacks.get(callbacks.size() - 1),
                () -> "server registration must remain unmarked if any callback fails: "
                        + register.instructions);
    }

    @Test
    void serverSendersConstructTypedPayloadsAndTargetOnePlayer() throws IOException {
        ClassData network = read(NETWORK_OWNER + ".class");
        MethodData playback = network.method("sendPlayback");
        assertCall(playback, NETWORK_OWNER + "#requireRegistered");
        assertCall(playback, NETWORK_OWNER + "$PlaybackPayload#<init>");
        assertCall(playback,
                "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking#send");

        MethodData reload = network.method("sendReload");
        assertCall(reload, NETWORK_OWNER + "#requireRegistered");
        assertCall(reload, NETWORK_OWNER + "$ReloadPayload#<init>");
        assertCall(reload,
                "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking#send");

        for (MethodData sender : List.of(playback, reload)) {
            assertFalse(sender.calls.stream().anyMatch(call ->
                            call.startsWith(
                                    "net/fabricmc/fabric/api/networking/v1/PacketByteBufs#"
                            )),
                    () -> "typed transport must not allocate a legacy raw packet buffer: "
                            + sender.calls);
            assertTrue(sender.calls.stream().anyMatch(call ->
                            call.startsWith(
                                    "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking#send"
                            ) && call.contains("Lnet/minecraft/server/level/ServerPlayer;")
                                    && call.contains(
                                    "Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;"
                            )),
                    () -> "send must use the targeted typed-payload overload: " + sender.calls);
        }
    }

    @Test
    void clientReceiversAreTypedIdempotentAndCompleteBeforeTheSuccessFlag() throws IOException {
        ClassData clientNetwork = read(CLIENT_NETWORK_OWNER + ".class");
        MethodData registration = clientNetwork.method("registerClientReceivers");
        assertCall(registration, NETWORK_OWNER + "#requireRegistered");
        assertReceiverRegistrationShortCircuit(registration);

        List<Integer> registrations = instructionIndexesContaining(
                registration,
                "CALL:net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking"
                        + "#registerGlobalReceiver"
        );
        assertEquals(2, registrations.size(),
                () -> "exactly two typed receivers must be registered: "
                        + registration.instructions);
        assertHandle(registration, CLIENT_NETWORK_OWNER + "#receivePlayback");
        assertHandle(registration, CLIENT_NETWORK_OWNER + "#receiveReload");

        String successWrite = "FIELD:" + Opcodes.PUTSTATIC + ":" + CLIENT_NETWORK_OWNER
                + "#receiversRegisteredZ";
        List<Integer> writes = instructionIndexesContaining(registration, successWrite);
        assertEquals(1, writes.size(),
                () -> "receiver success flag must be written exactly once: "
                        + registration.instructions);
        assertTrue(writes.get(0) > registrations.get(1),
                () -> "receiver success flag must remain false if either registration fails: "
                        + registration.instructions);
        assertEquals("INSN:" + Opcodes.ICONST_1,
                registration.instructions.get(writes.get(0) - 1));
    }

    @Test
    void typedReceiverContractRunsOnClientThreadWithoutDoubleDispatch() throws IOException {
        ClassData clientNetwork = read(CLIENT_NETWORK_OWNER + ".class");
        MethodData playback = clientNetwork.method("receivePlayback");
        assertCall(playback, NETWORK_OWNER + "$PlaybackPayload#message");
        assertCall(playback, CLIENT_NETWORK_OWNER + "#handleClientPlayback");
        assertNoClientExecute(playback);

        MethodData reload = clientNetwork.method("receiveReload");
        assertCall(reload, NETWORK_OWNER + "$ReloadPayload#message");
        assertCall(reload, CLIENT_NETWORK_OWNER + "#handleClientReload");
        assertNoClientExecute(reload);
    }

    @Test
    void staleClearCannotRemoveAReplacementClientHandler() throws Throwable {
        Class<?> clientNetwork = requiredClass(
                "datura.areamusic.fabric.network.FabricClientNetworking"
        );
        Method setHandler = requiredMethod(
                clientNetwork,
                "setClientHandler",
                ClientPacketHandler.class
        );
        Method clearHandler = requiredMethod(
                clientNetwork,
                "clearClientHandler",
                ClientPacketHandler.class
        );
        Method handleReload = requiredDeclaredMethod(
                clientNetwork,
                "handleClientReload",
                ClientboundReloadMusic.class
        );
        List<String> calls = new ArrayList<>();
        ClientPacketHandler oldHandler = recordingHandler(new ArrayList<>());
        ClientPacketHandler replacement = recordingHandler(calls);

        invoke(setHandler, null, oldHandler);
        invoke(setHandler, null, replacement);
        try {
            assertFalse((Boolean) invoke(clearHandler, null, oldHandler));
            invoke(handleReload, null, new ClientboundReloadMusic(9L));
        } finally {
            invoke(clearHandler, null, replacement);
        }

        assertEquals(List.of("onReload:9"), calls);
    }

    @Test
    void missingClientHandlerFailsExplicitly() throws Throwable {
        Class<?> clientNetwork = requiredClass(
                "datura.areamusic.fabric.network.FabricClientNetworking"
        );
        Method setHandler = requiredMethod(
                clientNetwork,
                "setClientHandler",
                ClientPacketHandler.class
        );
        Method clearHandler = requiredMethod(
                clientNetwork,
                "clearClientHandler",
                ClientPacketHandler.class
        );
        Method handlePlayback = requiredDeclaredMethod(
                clientNetwork,
                "handleClientPlayback",
                ClientboundPlaybackState.class
        );
        ClientPacketHandler handler = recordingHandler(new ArrayList<>());

        assertThrows(NullPointerException.class,
                () -> invoke(setHandler, null, new Object[]{null}));
        assertThrows(NullPointerException.class,
                () -> invoke(clearHandler, null, new Object[]{null}));
        invoke(setHandler, null, handler);
        assertTrue((Boolean) invoke(clearHandler, null, handler));

        IllegalStateException failure = assertThrows(
                IllegalStateException.class,
                () -> invoke(
                        handlePlayback,
                        null,
                        new ClientboundPlaybackState(4L, PlaybackState.stopped())
                )
        );
        assertTrue(failure.getMessage().contains("client packet handler"));
    }

    private static void assertRegistrationShortCircuit(MethodData method, String guardOwner) {
        String statusCheck = "CALL:" + guardOwner + "#isRegistered()Z";
        List<Integer> checks = instructionIndexesContaining(method, statusCheck);
        assertEquals(1, checks.size(),
                () -> "registration must check its guard exactly once: "
                        + method.instructions);
        int check = checks.get(0);
        assertEquals("JUMP:" + Opcodes.IFEQ, method.instructions.get(check + 1));
        assertEquals("INSN:" + Opcodes.RETURN, method.instructions.get(check + 2));
    }

    private static void assertReceiverRegistrationShortCircuit(MethodData method) {
        String field = "FIELD:" + Opcodes.GETSTATIC + ":" + CLIENT_NETWORK_OWNER
                + "#receiversRegisteredZ";
        List<Integer> reads = instructionIndexesContaining(method, field);
        assertEquals(1, reads.size(),
                () -> "registerClientReceivers must read its guard exactly once: "
                        + method.instructions);
        int read = reads.get(0);
        assertEquals("JUMP:" + Opcodes.IFEQ, method.instructions.get(read + 1));
        assertEquals("INSN:" + Opcodes.RETURN, method.instructions.get(read + 2));
    }

    private static void assertOwnRegistrationGuard(
            ClassData data,
            String componentOwner,
            String guardOwner
    ) {
        MethodData initializer = data.method("<clinit>");
        String guardDescriptor = "L" + guardOwner + ";";
        List<Integer> allocations = instructionIndexesContaining(
                initializer,
                "TYPE:" + Opcodes.NEW + ":" + guardOwner
        );
        List<Integer> constructors = instructionIndexesContaining(
                initializer,
                "CALL:" + guardOwner + "#<init>(Ljava/lang/String;)V"
        );
        List<Integer> ownWrites = instructionIndexesContaining(
                initializer,
                "FIELD:" + Opcodes.PUTSTATIC + ":" + componentOwner
                        + "#REGISTRATION" + guardDescriptor
        );
        assertEquals(1, allocations.size());
        assertEquals(1, constructors.size());
        assertEquals(1, ownWrites.size());
        assertTrue(allocations.get(0) < constructors.get(0)
                && constructors.get(0) < ownWrites.get(0));
    }

    private static void assertNoClientExecute(MethodData method) {
        assertFalse(method.calls.stream().anyMatch(call ->
                        call.startsWith("net/minecraft/client/Minecraft#execute")),
                () -> "Fabric typed receiver is already invoked on the client thread; "
                        + "it must not enqueue a second dispatch: " + method.calls);
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

    private static Field requiredField(Class<?> type, String name) {
        try {
            return type.getField(name);
        } catch (NoSuchFieldException missing) {
            return fail("required field is missing: " + type.getName() + "#" + name, missing);
        }
    }

    private static Method requiredMethod(Class<?> type, String name, Class<?>... parameterTypes) {
        try {
            return type.getMethod(name, parameterTypes);
        } catch (NoSuchMethodException missing) {
            return fail("required method is missing: " + type.getName() + "#" + name, missing);
        }
    }

    private static Method requiredDeclaredMethod(
            Class<?> type,
            String name,
            Class<?>... parameterTypes
    ) {
        try {
            Method method = type.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException missing) {
            return fail("required method is missing: " + type.getName() + "#" + name, missing);
        }
    }

    private static Constructor<?> requiredDeclaredConstructor(
            Class<?> type,
            Class<?>... parameterTypes
    ) {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException missing) {
            return fail("required constructor is missing: " + type.getName(), missing);
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
                () -> method.name + " does not call " + prefix + "; calls were "
                        + method.calls);
    }

    private static void assertField(MethodData method, String prefix) {
        assertTrue(method.fields.stream().anyMatch(field -> field.startsWith(prefix)),
                () -> method.name + " does not access " + prefix + "; fields were "
                        + method.fields);
    }

    private static void assertHandle(MethodData method, String prefix) {
        assertTrue(method.handles.stream().anyMatch(handle -> handle.startsWith(prefix)),
                () -> method.name + " has no method reference for " + prefix
                        + "; handles were " + method.handles);
    }

    private static void assertAnyCall(ClassData data, String prefix) {
        assertTrue(data.methods.values().stream()
                        .flatMap(List::stream)
                        .flatMap(method -> method.calls.stream())
                        .anyMatch(call -> call.startsWith(prefix)),
                () -> data.name + " has no call to " + prefix);
    }

    private static int firstInstructionContaining(MethodData method, String fragment) {
        List<Integer> indexes = instructionIndexesContaining(method, fragment);
        assertFalse(indexes.isEmpty(),
                () -> method.name + " has no instruction containing " + fragment
                        + "; instructions were " + method.instructions);
        return indexes.get(0);
    }

    private static List<Integer> instructionIndexesContaining(
            MethodData method,
            String fragment
    ) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            if (method.instructions.get(index).contains(fragment)) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private static ClassData read(String resourceName) throws IOException {
        try (InputStream input = FabricAreaMusicNetworkTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "compiled network class is missing: " + resourceName);
            ClassData data = new ClassData();
            new ClassReader(input).accept(new InspectionVisitor(data), 0);
            return data;
        }
    }

    private static final class InspectionVisitor extends ClassVisitor {
        private final ClassData data;

        private InspectionVisitor(ClassData data) {
            super(Opcodes.ASM9);
            this.data = data;
        }

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
            if (interfaces != null) {
                data.interfaces.addAll(List.of(interfaces));
            }
        }

        @Override
        public org.objectweb.asm.FieldVisitor visitField(
                int access,
                String name,
                String descriptor,
                String signature,
                Object value
        ) {
            data.fields.put(name, descriptor);
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            MethodData method = new MethodData(name);
            data.methods.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public void visitInsn(int opcode) {
                    method.instructions.add("INSN:" + opcode);
                }

                @Override
                public void visitFieldInsn(
                        int opcode,
                        String owner,
                        String fieldName,
                        String fieldDescriptor
                ) {
                    String field = owner + "#" + fieldName + fieldDescriptor;
                    method.fields.add(field);
                    method.instructions.add("FIELD:" + opcode + ":" + field);
                }

                @Override
                public void visitJumpInsn(int opcode, org.objectweb.asm.Label label) {
                    method.instructions.add("JUMP:" + opcode);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    method.instructions.add("TYPE:" + opcode + ":" + type);
                }

                @Override
                public void visitMethodInsn(
                        int opcode,
                        String owner,
                        String invokedName,
                        String invokedDescriptor,
                        boolean isInterface
                ) {
                    String call = owner + "#" + invokedName + invokedDescriptor;
                    method.calls.add(call);
                    method.instructions.add("CALL:" + call);
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String name,
                        String descriptor,
                        Handle bootstrapMethodHandle,
                        Object... bootstrapMethodArguments
                ) {
                    for (Object argument : bootstrapMethodArguments) {
                        if (argument instanceof Handle handle) {
                            String target = handle.getOwner() + "#" + handle.getName()
                                    + handle.getDesc();
                            method.handles.add(target);
                            method.instructions.add("HANDLE:" + target);
                        }
                    }
                }
            };
        }
    }

    private static final class ClassData {
        private String name;
        private final List<String> interfaces = new ArrayList<>();
        private final Map<String, String> fields = new HashMap<>();
        private final Map<String, List<MethodData>> methods = new HashMap<>();

        private String simpleName() {
            int marker = name.lastIndexOf('$');
            return marker >= 0 ? name.substring(marker + 1) : name;
        }

        private MethodData method(String name) {
            List<MethodData> matches = methods.get(name);
            assertNotNull(matches, "method is missing from " + this.name + ": " + name);
            assertEquals(1, matches.size(),
                    "method is overloaded in " + this.name + ": " + name);
            return matches.get(0);
        }
    }

    private static final class MethodData {
        private final String name;
        private final List<String> calls = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        private final List<String> handles = new ArrayList<>();
        private final List<String> instructions = new ArrayList<>();

        private MethodData(String name) {
            this.name = name;
        }
    }
}
