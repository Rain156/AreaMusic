package datura.areamusic.fabric.client;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FabricClientLifecycleAdapterTest {
    @Test
    void clientEntrypointOwnsOneCommonServiceAndDelegatesEveryClientLifecycle() throws IOException {
        ClassData adapter = read(
                "datura/areamusic/fabric/client/FabricClientAreaMusic.class"
        );
        assertTrue(adapter.interfaces.contains("net/fabricmc/api/ClientModInitializer"));

        MethodData entrypoint = adapter.method("onInitializeClient");
        assertCall(entrypoint,
                "datura/areamusic/fabric/client/FabricClientAreaMusic#initialize");
        for (String event : List.of(
                "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientTickEvents#END_CLIENT_TICK",
                "net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents#JOIN",
                "net/fabricmc/fabric/api/client/networking/v1/ClientPlayConnectionEvents#DISCONNECT",
                "net/fabricmc/fabric/api/client/event/lifecycle/v1/ClientLifecycleEvents#CLIENT_STOPPING",
                "net/fabricmc/fabric/api/client/screen/v1/ScreenEvents#AFTER_INIT"
        )) {
            assertField(entrypoint, event);
        }
        for (String callback : List.of(
                "onClientEndTick",
                "onJoin",
                "onDisconnect",
                "onClientStopping",
                "onScreenInit"
        )) {
            assertHandle(entrypoint,
                    "datura/areamusic/fabric/client/FabricClientAreaMusic#" + callback);
        }

        MethodData initialize = adapter.method("initialize");
        assertField(initialize,
                "datura/areamusic/fabric/client/FabricClientAreaMusic#instance");
        assertTrue(initialize.jumps.contains(Opcodes.IFNULL),
                "initialize must return when the singleton already exists");
        assertCall(initialize, "net/fabricmc/loader/api/FabricLoader#getGameDir");
        assertCall(initialize, "net/fabricmc/loader/api/FabricLoader#getConfigDir");
        assertCall(initialize, "java/nio/file/Path#toAbsolutePath");
        assertCall(initialize, "java/nio/file/Path#normalize");
        assertCall(initialize,
                "datura/areamusic/fabric/config/FabricClientConfig#load");
        assertCall(initialize, "datura/areamusic/client/ClientAreaMusic#<init>");
        assertHandle(initialize,
                "datura/areamusic/fabric/config/FabricClientConfig#volume");
        assertCall(initialize,
                "datura/areamusic/fabric/network/FabricClientNetworking#setClientHandler");
        assertCall(initialize,
                "datura/areamusic/fabric/network/FabricClientNetworking#registerClientReceivers");

        assertCall(adapter.method("onClientEndTick"),
                "datura/areamusic/client/ClientAreaMusic#tick");
        MethodData join = adapter.method("onJoin");
        assertCall(join, "net/minecraft/client/Minecraft#execute");
        assertHandle(join, "datura/areamusic/client/ClientAreaMusic#connect");
        MethodData disconnect = adapter.method("onDisconnect");
        assertCall(disconnect, "net/minecraft/client/Minecraft#execute");
        assertHandle(disconnect, "datura/areamusic/client/ClientAreaMusic#disconnect");

        MethodData stopping = adapter.method("onClientStopping");
        assertCall(stopping, "datura/areamusic/client/ClientAreaMusic#shutdown");
        assertCall(stopping,
                "datura/areamusic/fabric/network/FabricClientNetworking#clearClientHandler");

        MethodData screen = adapter.method("onScreenInit");
        assertCall(screen, "net/minecraft/client/gui/screens/Screen#children");
        assertCall(screen, "datura/areamusic/client/AreaMusicSoundOptions#onScreenInit");
        assertHandle(screen,
                "datura/areamusic/fabric/config/FabricClientConfig#volume");
        assertHandle(screen,
                "datura/areamusic/fabric/config/FabricClientConfig#setVolume");
    }

    private static void assertCall(MethodData method, String prefix) {
        assertTrue(method.calls.stream().anyMatch(call -> call.startsWith(prefix)),
                () -> method.name + " does not call " + prefix + "; calls were " + method.calls);
    }

    private static void assertField(MethodData method, String prefix) {
        assertTrue(method.fields.stream().anyMatch(field -> field.startsWith(prefix)),
                () -> method.name + " does not access " + prefix + "; fields were " + method.fields);
    }

    private static void assertHandle(MethodData method, String prefix) {
        assertTrue(method.handles.stream().anyMatch(handle -> handle.startsWith(prefix)),
                () -> method.name + " has no method reference for " + prefix
                        + "; handles were " + method.handles);
    }

    private static ClassData read(String resourceName) throws IOException {
        try (InputStream input = FabricClientLifecycleAdapterTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "compiled client adapter class is missing: " + resourceName);
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
                    method.fields.add(owner + "#" + fieldName + fieldDescriptor);
                }

                @Override
                public void visitJumpInsn(int opcode, Label label) {
                    method.jumps.add(opcode);
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
        private final List<String> interfaces = new ArrayList<>();
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
        private final List<String> fields = new ArrayList<>();
        private final List<String> handles = new ArrayList<>();
        private final List<Integer> jumps = new ArrayList<>();

        private MethodData(String name) {
            this.name = name;
        }
    }
}
