package datura.areamusic;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ForgeLifecycleAdaptersTest {
    private static final String SUBSCRIBER =
            "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber;";
    private static final String SUBSCRIBE_EVENT =
            "Lnet/minecraftforge/eventbus/api/SubscribeEvent;";
    private static final String DIST = "Lnet/minecraftforge/api/distmarker/Dist;";
    private static final String BUS =
            "Lnet/minecraftforge/fml/common/Mod$EventBusSubscriber$Bus;";

    @Test
    void clientAdapterDeclaresForgeMetadataAndDelegatesEveryLifecycleBody() throws IOException {
        ClassData adapter = read("datura/areamusic/client/ForgeClientAreaMusic.class");
        ClassData modEvents = read("datura/areamusic/client/ForgeClientAreaMusic$ModEvents.class");
        ClassData forgeEvents = read("datura/areamusic/client/ForgeClientAreaMusic$ForgeEvents.class");

        assertSubscriber(modEvents, "MOD", true);
        assertSubscriber(forgeEvents, "FORGE", true);
        assertSubscribedMethods(modEvents, Set.of("onClientSetup"));
        assertSubscribedMethods(forgeEvents, Set.of(
                "onClientTick",
                "onLoggingIn",
                "onLoggingOut",
                "onGameShuttingDown"
        ));

        assertCall(modEvents.method("onClientSetup"),
                "net/minecraftforge/fml/event/lifecycle/FMLClientSetupEvent#enqueueWork");
        assertCall(forgeEvents.method("onClientTick"),
                "datura/areamusic/client/ClientAreaMusic#tick");
        assertField(forgeEvents.method("onClientTick"),
                "net/minecraftforge/event/TickEvent$Phase#END");
        assertCall(forgeEvents.method("onLoggingIn"),
                "datura/areamusic/client/ClientAreaMusic#connect");
        assertCall(forgeEvents.method("onLoggingOut"),
                "datura/areamusic/client/ClientAreaMusic#disconnect");
        assertCall(forgeEvents.method("onGameShuttingDown"),
                "datura/areamusic/client/ClientAreaMusic#shutdown");
        assertCall(forgeEvents.method("onGameShuttingDown"),
                "datura/areamusic/network/AreaMusicNetwork#clearClientHandler");

        MethodData initialize = adapter.method("initialize");
        assertCall(initialize, "datura/areamusic/client/ClientAreaMusic#<init>");
        assertCall(initialize, "datura/areamusic/network/AreaMusicNetwork#setClientHandler");
        assertCall(initialize, "java/nio/file/Path#toAbsolutePath");
        assertCall(initialize, "java/nio/file/Path#normalize");
        assertField(initialize, "net/minecraftforge/fml/loading/FMLPaths#GAMEDIR");
        assertHandle(initialize, "datura/areamusic/config/AreaMusicClientConfig#volume");
    }

    @Test
    void serverAdapterDeclaresForgeMetadataFiltersEventsAndDelegatesToCommon() throws IOException {
        ClassData adapter = read("datura/areamusic/server/ForgeAreaMusicServer.class");

        assertSubscriber(adapter, "FORGE", false);
        assertSubscribedMethods(adapter, Set.of(
                "onRegisterCommands",
                "onServerStarted",
                "onServerStopping",
                "onPlayerTick",
                "onPlayerLogin",
                "onPlayerLogout",
                "onPlayerRespawn",
                "onPlayerChangedDimension"
        ));

        assertCall(adapter.method("onRegisterCommands"),
                "datura/areamusic/server/AreaMusicServer#registerCommands");
        MethodData start = adapter.method("onServerStarted");
        assertCall(start, "datura/areamusic/server/AreaMusicServer#onServerStarted");
        assertCall(start, "java/nio/file/Path#toAbsolutePath");
        assertCall(start, "java/nio/file/Path#normalize");
        assertField(start, "net/minecraftforge/fml/loading/FMLPaths#GAMEDIR");
        assertField(start, "net/minecraftforge/fml/loading/FMLPaths#CONFIGDIR");
        assertCall(adapter.method("onServerStopping"),
                "datura/areamusic/server/AreaMusicServer#onServerStopping");

        MethodData tick = adapter.method("onPlayerTick");
        assertCall(tick, "datura/areamusic/server/AreaMusicServer#onPlayerEndTick");
        assertField(tick, "net/minecraftforge/event/TickEvent$Phase#END");
        assertType(tick, "net/minecraft/server/level/ServerPlayer");
        assertPlayerDelegation(adapter, "onPlayerLogin", "onPlayerLogin");
        assertPlayerDelegation(adapter, "onPlayerLogout", "onPlayerLogout");
        assertPlayerDelegation(adapter, "onPlayerRespawn", "onPlayerRespawn");
        assertPlayerDelegation(adapter, "onPlayerChangedDimension", "onPlayerChangedDimension");

        ClassData bridge = read(
                "datura/areamusic/server/ForgeAreaMusicServer$ForgeNetworkSender.class"
        );
        assertTrue(bridge.interfaces.contains("datura/areamusic/server/NetworkSender"));
        assertCall(bridge.method("sendReload"),
                "datura/areamusic/network/AreaMusicNetwork#sendReload");
        assertCall(bridge.method("sendPlayback"),
                "datura/areamusic/network/AreaMusicNetwork#sendPlayback");
    }

    @Test
    void forgePacketHandlersEnqueueWorkAndMarkPacketsHandled() throws IOException {
        ClassData network = read("datura/areamusic/network/AreaMusicNetwork.class");

        for (String methodName : List.of("handlePlaybackPacket", "handleReloadPacket")) {
            MethodData method = network.method(methodName);
            assertCall(method, "net/minecraftforge/network/NetworkEvent$Context#enqueueWork");
            assertCall(method, "net/minecraftforge/network/NetworkEvent$Context#setPacketHandled");
            assertTrue(method.opcodes.contains(Opcodes.ICONST_1),
                    methodName + " must pass true to setPacketHandled");
        }
    }

    @Test
    void commonServerRoutesOwnedLifecycleStateThroughTheCoordinator() throws IOException {
        ClassData server = read("datura/areamusic/server/AreaMusicServer.class");
        String coordinator = "datura/areamusic/server/ServerLifecycleCoordinator#";
        String reloadOperation =
                "datura/areamusic/server/ServerLifecycleCoordinator$ReloadOperation#";
        String createOperation =
                "datura/areamusic/server/ServerLifecycleCoordinator$CreateOperation#";
        String completion =
                "datura/areamusic/server/ServerLifecycleCoordinator$Completion#";

        assertCall(server.method("onServerStarted"), coordinator + "start");
        assertCall(server.method("onServerStopping"), coordinator + "stop");
        MethodData requestReload = server.method("requestReload");
        assertCall(requestReload, coordinator + "beginReload");
        assertJump(requestReload, Opcodes.IFNONNULL);
        assertAnyCall(server, "lambda$requestReload$",
                reloadOperation + "server");
        MethodData createArea = server.method("createArea");
        assertCall(createArea, coordinator + "createBusy");
        assertCall(createArea, coordinator + "beginCreate");
        assertJump(createArea, Opcodes.IFNONNULL);
        assertAnyCall(server, "lambda$createArea$",
                createOperation + "server");
        MethodData finishReload = server.method("finishReload");
        assertCall(finishReload, reloadOperation + "lifecycle");
        assertCall(finishReload, coordinator + "finishReload");
        assertJump(finishReload, Opcodes.IFNONNULL);
        assertCall(finishReload,
                "datura/areamusic/server/AreaMusicServer$ServerState#owns");
        assertCall(finishReload, completion + "lifecycle");
        assertCall(finishReload, completion + "server");
        assertCall(finishReload, completion + "sender");
        MethodData finishCreate = server.method("finishCreate");
        assertCall(finishCreate, createOperation + "lifecycle");
        assertCall(finishCreate, coordinator + "finishCreate");
        assertJump(finishCreate, Opcodes.IFNONNULL);
        assertCall(finishCreate,
                "datura/areamusic/server/AreaMusicServer$ServerState#owns");
        assertCall(finishCreate, completion + "lifecycle");
        assertCall(finishCreate, completion + "server");
        assertCall(finishCreate, completion + "sender");
        assertCall(server.method("onPlayerLogout"), coordinator + "removeTracker");
        assertCall(server.method("forceSync"), coordinator + "removeTracker");
        assertCall(server.method("forceSync"), coordinator + "senderFor");
        assertCall(server.method("syncPlayer"), coordinator + "senderFor");
        assertCall(server.method("syncPlayerWithSender"), coordinator + "tracker");
        assertCall(finishReload, coordinator + "clearTrackers");
        assertCall(finishCreate, coordinator + "clearTrackers");
    }

    private static void assertPlayerDelegation(
            ClassData adapter,
            String eventMethod,
            String commonMethod
    ) {
        MethodData method = adapter.method(eventMethod);
        assertType(method, "net/minecraft/server/level/ServerPlayer");
        assertCall(method, "datura/areamusic/server/AreaMusicServer#" + commonMethod);
    }

    private static void assertSubscriber(ClassData data, String bus, boolean clientOnly) {
        assertTrue(data.subscriberPresent, "EventBusSubscriber is missing from " + data.name);
        assertEquals("areamusic", data.modId);
        assertEquals(bus, data.bus);
        assertEquals(clientOnly ? List.of("CLIENT") : List.of(), data.distValues);
    }

    private static void assertSubscribedMethods(ClassData data, Set<String> expected) {
        Set<String> actual = new HashSet<>();
        data.methods.forEach((name, methods) -> methods.stream()
                .filter(method -> method.subscribeEvent)
                .forEach(method -> {
                    assertTrue((method.access & Opcodes.ACC_PUBLIC) != 0,
                            name + " must be public");
                    assertTrue((method.access & Opcodes.ACC_STATIC) != 0,
                            name + " must be static");
                    actual.add(name);
                }));
        assertEquals(expected, actual);
    }

    private static void assertCall(MethodData method, String prefix) {
        assertTrue(method.calls.stream().anyMatch(call -> call.startsWith(prefix)),
                () -> method.name + " does not call " + prefix + "; calls were " + method.calls);
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

    private static void assertJump(MethodData method, int opcode) {
        assertTrue(method.jumps.contains(opcode),
                () -> method.name + " does not contain jump opcode " + opcode
                        + "; jumps were " + method.jumps);
    }

    private static void assertField(MethodData method, String prefix) {
        assertTrue(method.fields.stream().anyMatch(field -> field.startsWith(prefix)),
                () -> method.name + " does not access " + prefix + "; fields were " + method.fields);
    }

    private static void assertType(MethodData method, String type) {
        assertTrue(method.types.contains(type),
                () -> method.name + " does not test/cast " + type + "; types were " + method.types);
    }

    private static void assertHandle(MethodData method, String prefix) {
        assertTrue(method.handles.stream().anyMatch(handle -> handle.startsWith(prefix)),
                () -> method.name + " has no lambda handle for " + prefix
                        + "; handles were " + method.handles);
    }

    private static ClassData read(String resourceName) throws IOException {
        ClassLoader loader = ForgeLifecycleAdaptersTest.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(resourceName)) {
            assertNotNull(input, "compiled adapter class is missing: " + resourceName);
            ClassData data = new ClassData();
            new ClassReader(input).accept(new AdapterVisitor(data), 0);
            return data;
        }
    }

    private static final class AdapterVisitor extends ClassVisitor {
        private final ClassData data;

        private AdapterVisitor(ClassData data) {
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
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            if (!SUBSCRIBER.equals(descriptor)) {
                return null;
            }
            data.subscriberPresent = true;
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    if ("modid".equals(name)) {
                        data.modId = (String) value;
                    }
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    if (!"value".equals(name)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitEnum(String ignored, String descriptor, String value) {
                            if (DIST.equals(descriptor)) {
                                data.distValues.add(value);
                            }
                        }
                    };
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    if ("bus".equals(name) && BUS.equals(descriptor)) {
                        data.bus = value;
                    }
                }
            };
        }

        @Override
        public MethodVisitor visitMethod(
                int access,
                String name,
                String descriptor,
                String signature,
                String[] exceptions
        ) {
            MethodData method = new MethodData(access, name, descriptor);
            data.methods.computeIfAbsent(name, ignored -> new ArrayList<>()).add(method);
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String annotation, boolean visible) {
                    if (SUBSCRIBE_EVENT.equals(annotation)) {
                        method.subscribeEvent = true;
                    }
                    return null;
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
                public void visitTypeInsn(int opcode, String type) {
                    method.types.add(type);
                }

                @Override
                public void visitInsn(int opcode) {
                    method.opcodes.add(opcode);
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
        private boolean subscriberPresent;
        private String modId;
        private String bus;
        private final List<String> distValues = new ArrayList<>();
        private final Map<String, List<MethodData>> methods = new HashMap<>();

        private MethodData method(String name) {
            List<MethodData> matches = methods.get(name);
            assertNotNull(matches, "method is missing from " + this.name + ": " + name);
            assertEquals(1, matches.size(), "method is overloaded in " + this.name + ": " + name);
            return matches.get(0);
        }
    }

    private static final class MethodData {
        private final int access;
        private final String name;
        @SuppressWarnings("unused")
        private final String descriptor;
        private boolean subscribeEvent;
        private final List<String> calls = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        private final List<String> types = new ArrayList<>();
        private final List<String> handles = new ArrayList<>();
        private final List<Integer> opcodes = new ArrayList<>();
        private final List<Integer> jumps = new ArrayList<>();

        private MethodData(int access, String name, String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
