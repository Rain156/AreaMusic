package datura.areamusic.fabric.network;

import datura.areamusic.network.ClientPacketHandler;
import datura.areamusic.network.ClientboundPlaybackState;
import datura.areamusic.network.ClientboundReloadMusic;
import datura.areamusic.playback.PlaybackState;
import net.minecraft.resources.ResourceLocation;
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
    @Test
    void usesIndependentPlaybackAndReloadChannels() throws Exception {
        ResourceLocation playback = (ResourceLocation) requiredField(
                FabricAreaMusicNetwork.class,
                "PLAYBACK_CHANNEL"
        ).get(null);
        ResourceLocation reload = (ResourceLocation) requiredField(
                FabricAreaMusicNetwork.class,
                "RELOAD_CHANNEL"
        ).get(null);

        assertEquals("areamusic:playback", playback.toString());
        assertEquals("areamusic:reload", reload.toString());
        assertNotEquals(playback, reload);
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
    void networkAndServerRegistrarsUseGuardsAfterCallbacksSucceed() throws IOException {
        String guardOwner = "datura/areamusic/fabric/AreaMusicFabric$RegistrationGuard";
        ClassData network = read(
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork.class"
        );
        assertCallCount(network.method("register"), guardOwner + "#register", 1);
        assertCallCount(network.method("requireRegistered"),
                guardOwner + "#requireRegistered", 1);

        ClassData server = read(
                "datura/areamusic/fabric/server/FabricAreaMusicServer.class"
        );
        MethodData registration = server.method("register");
        assertServerRegistrationShortCircuitAndCompletion(registration, guardOwner);
    }

    @Test
    void networkAndServerOwnIndependentRegistrationGuardInstances() throws IOException {
        String guardOwner = "datura/areamusic/fabric/AreaMusicFabric$RegistrationGuard";
        assertOwnRegistrationGuard(
                read("datura/areamusic/fabric/network/FabricAreaMusicNetwork.class"),
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork",
                guardOwner
        );
        assertOwnRegistrationGuard(
                read("datura/areamusic/fabric/server/FabricAreaMusicServer.class"),
                "datura/areamusic/fabric/server/FabricAreaMusicServer",
                guardOwner
        );
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

        assertThrows(NullPointerException.class, () -> invoke(setHandler, null, new Object[]{null}));
        assertThrows(NullPointerException.class, () -> invoke(clearHandler, null, new Object[]{null}));
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

    @Test
    void serverSendersUseCommonCodecsAndTargetedFabricTransport() throws IOException {
        ClassData serverNetwork = read(
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork.class"
        );
        MethodData playback = serverNetwork.method("sendPlayback");
        assertCall(playback, "datura/areamusic/network/ClientboundPlaybackState#encode");
        assertCall(playback, "net/fabricmc/fabric/api/networking/v1/PacketByteBufs#create");
        assertCall(playback, "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking#send");
        assertCall(playback, "datura/areamusic/fabric/network/FabricAreaMusicNetwork#requireRegistered");

        MethodData reload = serverNetwork.method("sendReload");
        assertCall(reload, "datura/areamusic/network/ClientboundReloadMusic#encode");
        assertCall(reload, "net/fabricmc/fabric/api/networking/v1/PacketByteBufs#create");
        assertCall(reload, "net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking#send");
        assertCall(reload, "datura/areamusic/fabric/network/FabricAreaMusicNetwork#requireRegistered");
    }

    @Test
    void clientReceiversDecodeThenEnqueueOntoTheMinecraftClientThread() throws IOException {
        ClassData clientNetwork = read(
                "datura/areamusic/fabric/network/FabricClientNetworking.class"
        );
        MethodData registration = clientNetwork.method("registerClientReceivers");
        assertCall(registration,
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork#requireRegistered");
        assertCall(registration,
                "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking#registerGlobalReceiver");
        assertCallCount(
                registration,
                "net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking#registerGlobalReceiver",
                2
        );
        assertReceiverRegistrationShortCircuit(registration);
        assertReceiverRegistrationCompletion(registration);
        assertHandle(registration,
                "datura/areamusic/fabric/network/FabricClientNetworking#receivePlayback");
        assertHandle(registration,
                "datura/areamusic/fabric/network/FabricClientNetworking#receiveReload");

        MethodData playback = clientNetwork.method("receivePlayback");
        assertCall(playback, "datura/areamusic/network/ClientboundPlaybackState#decode");
        assertCall(playback, "net/minecraft/client/Minecraft#execute");
        assertAnyCall(clientNetwork, "lambda$receivePlayback$",
                "datura/areamusic/fabric/network/FabricClientNetworking#handleClientPlayback");

        MethodData reload = clientNetwork.method("receiveReload");
        assertCall(reload, "datura/areamusic/network/ClientboundReloadMusic#decode");
        assertCall(reload, "net/minecraft/client/Minecraft#execute");
        assertAnyCall(clientNetwork, "lambda$receiveReload$",
                "datura/areamusic/fabric/network/FabricClientNetworking#handleClientReload");
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
                () -> method.name + " does not call " + prefix + "; calls were " + method.calls);
    }

    private static void assertCallCount(MethodData method, String prefix, long expectedCount) {
        long count = method.calls.stream().filter(call -> call.startsWith(prefix)).count();
        assertEquals(expectedCount, count,
                () -> method.name + " must call " + prefix + " exactly " + expectedCount
                        + " times; calls were " + method.calls);
    }

    private static void assertReceiverRegistrationShortCircuit(MethodData method) {
        String field = "datura/areamusic/fabric/network/FabricClientNetworking"
                + "#receiversRegisteredZ";
        List<Integer> reads = instructionIndexesStartingWith(
                method,
                "FIELD:" + Opcodes.GETSTATIC + ":" + field
        );
        int firstRegistration = firstInstructionStartingWith(
                method,
                "CALL:net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking"
                        + "#registerGlobalReceiver"
        );
        assertEquals(1, reads.size(),
                () -> "registerClientReceivers must read receiversRegistered exactly once: "
                        + method.instructions);
        int read = reads.get(0);
        assertEquals("JUMP:" + Opcodes.IFEQ, method.instructions.get(read + 1),
                () -> "a false receiversRegistered value must jump to receiver registration: "
                        + method.instructions);
        assertEquals("INSN:" + Opcodes.RETURN, method.instructions.get(read + 2),
                () -> "a true receiversRegistered value must return immediately: "
                        + method.instructions);
        assertTrue(read + 2 < firstRegistration,
                () -> "the idempotent return must precede receiver registration: "
                        + method.instructions);
    }

    private static void assertReceiverRegistrationCompletion(MethodData method) {
        String registrationCall =
                "CALL:net/fabricmc/fabric/api/client/networking/v1/ClientPlayNetworking"
                        + "#registerGlobalReceiver";
        List<Integer> registrations = instructionIndexesStartingWith(method, registrationCall);
        assertEquals(2, registrations.size(),
                () -> "registerClientReceivers must contain exactly two receiver registrations: "
                        + method.instructions);

        String fieldWrite = "FIELD:" + Opcodes.PUTSTATIC + ":"
                + "datura/areamusic/fabric/network/FabricClientNetworking"
                + "#receiversRegisteredZ";
        List<Integer> writes = instructionIndexesStartingWith(method, fieldWrite);
        assertEquals(1, writes.size(),
                () -> "registerClientReceivers must write receiversRegistered exactly once: "
                        + method.instructions);
        int write = writes.get(0);
        assertTrue(write > registrations.get(1),
                () -> "receiversRegistered must be written only after both registrations: "
                        + method.instructions);
        assertEquals("INSN:" + Opcodes.ICONST_1, method.instructions.get(write - 1),
                () -> "registerClientReceivers must set receiversRegistered to true: "
                        + method.instructions);
    }

    private static void assertServerRegistrationShortCircuitAndCompletion(
            MethodData method,
            String guardOwner
    ) {
        String statusCheck = "CALL:" + guardOwner + "#isRegistered()Z";
        List<Integer> checks = instructionIndexesStartingWith(method, statusCheck);
        assertEquals(1, checks.size(),
                () -> "server registration must check its guard exactly once: "
                        + method.instructions);
        int check = checks.get(0);
        assertEquals("JUMP:" + Opcodes.IFEQ, method.instructions.get(check + 1),
                () -> "an unregistered server guard must jump to callback registration: "
                        + method.instructions);
        assertEquals("INSN:" + Opcodes.RETURN, method.instructions.get(check + 2),
                () -> "an already registered server guard must return immediately: "
                        + method.instructions);

        List<Integer> callbacks = instructionIndexesStartingWith(
                method,
                "CALL:net/fabricmc/fabric/api/event/Event#register"
        );
        assertFalse(callbacks.isEmpty(),
                () -> "server registration must install Fabric callbacks: "
                        + method.instructions);
        List<Integer> completions = instructionIndexesStartingWith(
                method,
                "CALL:" + guardOwner + "#register()V"
        );
        assertEquals(1, completions.size(),
                () -> "server registration must mark its guard exactly once: "
                        + method.instructions);
        assertTrue(completions.get(0) > callbacks.get(callbacks.size() - 1),
                () -> "the server guard must be marked only after every callback: "
                        + method.instructions);
    }

    private static void assertOwnRegistrationGuard(
            ClassData data,
            String componentOwner,
            String guardOwner
    ) {
        MethodData initializer = data.method("<clinit>");
        String guardDescriptor = "L" + guardOwner + ";";
        List<Integer> allocations = instructionIndexesStartingWith(
                initializer,
                "TYPE:" + Opcodes.NEW + ":" + guardOwner
        );
        List<Integer> constructors = instructionIndexesStartingWith(
                initializer,
                "CALL:" + guardOwner + "#<init>(Ljava/lang/String;)V"
        );
        List<Integer> ownWrites = instructionIndexesStartingWith(
                initializer,
                "FIELD:" + Opcodes.PUTSTATIC + ":" + componentOwner
                        + "#REGISTRATION" + guardDescriptor
        );
        assertEquals(1, allocations.size(),
                () -> componentOwner + " must allocate its own registration guard: "
                        + initializer.instructions);
        assertEquals(1, constructors.size(),
                () -> componentOwner + " must construct its own registration guard: "
                        + initializer.instructions);
        assertEquals(1, ownWrites.size(),
                () -> componentOwner + " must store its own registration guard: "
                        + initializer.instructions);
        assertTrue(allocations.get(0) < constructors.get(0)
                        && constructors.get(0) < ownWrites.get(0),
                () -> componentOwner + " must allocate, construct, then store its guard: "
                        + initializer.instructions);

        assertFalse(initializer.instructions.stream().anyMatch(instruction ->
                        instruction.startsWith("FIELD:" + Opcodes.GETSTATIC + ":")
                                && instruction.endsWith(guardDescriptor)),
                () -> componentOwner + " must not load a shared registration guard: "
                        + initializer.instructions);
        assertFalse(initializer.calls.stream().anyMatch(call ->
                        call.endsWith(")" + guardDescriptor)),
                () -> componentOwner + " must not obtain its guard from a factory: "
                        + initializer.calls);
    }

    private static int firstInstructionStartingWith(MethodData method, String prefix) {
        List<Integer> indexes = instructionIndexesStartingWith(method, prefix);
        assertFalse(indexes.isEmpty(),
                () -> method.name + " has no instruction starting with " + prefix
                        + "; instructions were " + method.instructions);
        return indexes.get(0);
    }

    private static List<Integer> instructionIndexesStartingWith(
            MethodData method,
            String prefix
    ) {
        List<Integer> indexes = new ArrayList<>();
        for (int index = 0; index < method.instructions.size(); index++) {
            if (method.instructions.get(index).startsWith(prefix)) {
                indexes.add(index);
            }
        }
        return indexes;
    }

    private static void assertHandle(MethodData method, String prefix) {
        assertTrue(method.handles.stream().anyMatch(handle -> handle.startsWith(prefix)),
                () -> method.name + " has no method reference for " + prefix
                        + "; handles were " + method.handles);
    }

    private static void assertAnyCall(ClassData data, String methodPrefix, String callPrefix) {
        boolean found = data.methods.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith(methodPrefix))
                .flatMap(entry -> entry.getValue().stream())
                .flatMap(method -> method.calls.stream())
                .anyMatch(call -> call.startsWith(callPrefix));
        assertTrue(found, () -> data.name + " has no " + methodPrefix
                + " method calling " + callPrefix);
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
                    method.instructions.add(
                            "FIELD:" + opcode + ":" + owner + "#"
                                    + fieldName + fieldDescriptor
                    );
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
                    method.calls.add(owner + "#" + invokedName + invokedDescriptor);
                    method.instructions.add(
                            "CALL:" + owner + "#" + invokedName + invokedDescriptor
                    );
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
                            method.handles.add(
                                    handle.getOwner() + "#" + handle.getName() + handle.getDesc()
                            );
                        }
                    }
                }
            };
        }
    }

    private static final class ClassData {
        private String name;
        private final Map<String, List<MethodData>> methods = new HashMap<>();

        private MethodData method(String name) {
            List<MethodData> matches = methods.get(name);
            assertNotNull(matches, "method is missing from " + this.name + ": " + name);
            assertEquals(1, matches.size(), "method is overloaded in " + this.name + ": " + name);
            return matches.get(0);
        }
    }

    private static final class MethodData {
        private final String name;
        private final List<String> calls = new ArrayList<>();
        private final List<String> handles = new ArrayList<>();
        private final List<String> instructions = new ArrayList<>();

        private MethodData(String name) {
            this.name = name;
        }
    }
}
