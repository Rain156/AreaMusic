package datura.areamusic.fabric;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
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

class FabricServerLifecycleAdapterTest {
    @Test
    void mainAndServerAdaptersRegisterEveryLifecycleAndDelegateToCommon() throws IOException {
        ClassData entrypoint = read("datura/areamusic/fabric/AreaMusicFabric.class");
        assertTrue(entrypoint.interfaces.contains("net/fabricmc/api/ModInitializer"));
        assertCall(entrypoint.method("onInitialize"),
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork#register");
        assertCall(entrypoint.method("onInitialize"),
                "datura/areamusic/fabric/server/FabricAreaMusicServer#register");

        ClassData adapter = read("datura/areamusic/fabric/server/FabricAreaMusicServer.class");
        MethodData register = adapter.method("register");
        for (String event : List.of(
                "net/fabricmc/fabric/api/command/v2/CommandRegistrationCallback#EVENT",
                "net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents#SERVER_STARTED",
                "net/fabricmc/fabric/api/event/lifecycle/v1/ServerLifecycleEvents#SERVER_STOPPING",
                "net/fabricmc/fabric/api/event/lifecycle/v1/ServerTickEvents#END_SERVER_TICK",
                "net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents#JOIN",
                "net/fabricmc/fabric/api/networking/v1/ServerPlayConnectionEvents#DISCONNECT",
                "net/fabricmc/fabric/api/entity/event/v1/ServerPlayerEvents#AFTER_RESPAWN",
                "net/fabricmc/fabric/api/entity/event/v1/ServerEntityWorldChangeEvents#AFTER_PLAYER_CHANGE_WORLD"
        )) {
            assertField(register, event);
        }
        for (String callback : List.of(
                "onRegisterCommands",
                "onServerStarted",
                "onServerStopping",
                "onServerEndTick",
                "onPlayerLogin",
                "onPlayerLogout",
                "onPlayerRespawn",
                "onPlayerChangedDimension"
        )) {
            assertHandle(register,
                    "datura/areamusic/fabric/server/FabricAreaMusicServer#" + callback);
        }

        assertCall(adapter.method("onRegisterCommands"),
                "datura/areamusic/server/AreaMusicServer#registerCommands");
        MethodData started = adapter.method("onServerStarted");
        assertCall(started, "datura/areamusic/server/AreaMusicServer#onServerStarted");
        assertCall(started, "net/fabricmc/loader/api/FabricLoader#getGameDir");
        assertCall(started, "net/fabricmc/loader/api/FabricLoader#getConfigDir");
        assertCall(started, "java/nio/file/Path#toAbsolutePath");
        assertCall(started, "java/nio/file/Path#normalize");
        assertCall(adapter.method("onServerStopping"),
                "datura/areamusic/server/AreaMusicServer#onServerStopping");

        MethodData tick = adapter.method("onServerEndTick");
        assertCall(tick, "net/minecraft/server/MinecraftServer#getPlayerList");
        assertCall(tick, "net/minecraft/server/players/PlayerList#getPlayers");
        assertHandle(tick, "datura/areamusic/server/AreaMusicServer#onPlayerEndTick");
        assertCall(adapter.method("onPlayerLogin"),
                "datura/areamusic/server/AreaMusicServer#onPlayerLogin");
        assertCall(adapter.method("onPlayerLogout"),
                "datura/areamusic/server/AreaMusicServer#onPlayerLogout");
        assertCall(adapter.method("onPlayerRespawn"),
                "datura/areamusic/server/AreaMusicServer#onPlayerRespawn");
        assertCall(adapter.method("onPlayerChangedDimension"),
                "datura/areamusic/server/AreaMusicServer#onPlayerChangedDimension");

        ClassData bridge = read(
                "datura/areamusic/fabric/server/FabricAreaMusicServer$FabricNetworkSender.class"
        );
        assertTrue(bridge.interfaces.contains("datura/areamusic/server/NetworkSender"));
        assertCall(bridge.method("sendReload"),
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork#sendReload");
        assertCall(bridge.method("sendPlayback"),
                "datura/areamusic/fabric/network/FabricAreaMusicNetwork#sendPlayback");
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
        try (InputStream input = FabricServerLifecycleAdapterTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "compiled adapter class is missing: " + resourceName);
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

        private MethodData(String name) {
            this.name = name;
        }
    }
}
