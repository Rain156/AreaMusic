package datura.areamusic;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicEntrypointTest {
    private static final String OWNER = "datura/areamusic/AreaMusic";

    @Test
    void forgeEntrypointRegistersClientConfigAndTypedNetworkWithoutClientReferences()
            throws IOException {
        ClassData data = read(OWNER + ".class");
        MethodData constructor = data.constructor(
                "(Lnet/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext;)V"
        );

        assertEquals("areamusic", data.modId);
        assertCall(constructor,
                "net/minecraftforge/fml/javafmlmod/FMLJavaModLoadingContext#registerConfig");
        assertCall(constructor, "datura/areamusic/network/AreaMusicNetwork#register");
        assertField(constructor,
                "datura/areamusic/config/AreaMusicClientConfig#INSTANCE");
        assertTrue(constructor.constants.contains("areamusic-client.toml"));
        assertTrue(constructor.fields.stream().anyMatch(field ->
                field.contains("net/minecraftforge/fml/config/ModConfig$Type#CLIENT")));
        assertFalse(constructor.references().stream().anyMatch(reference ->
                        reference.startsWith("net/minecraft/client/")
                                || reference.startsWith("datura/areamusic/client/")),
                () -> "common Forge entrypoint references client code: "
                        + constructor.references());
    }

    private static void assertCall(MethodData method, String prefix) {
        assertTrue(method.calls.stream().anyMatch(call -> call.startsWith(prefix)),
                () -> "constructor does not call " + prefix + "; calls were " + method.calls);
    }

    private static void assertField(MethodData method, String prefix) {
        assertTrue(method.fields.stream().anyMatch(field -> field.startsWith(prefix)),
                () -> "constructor does not access " + prefix + "; fields were "
                        + method.fields);
    }

    private static ClassData read(String resourceName) throws IOException {
        try (InputStream input = AreaMusicEntrypointTest.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            assertNotNull(input, "compiled Forge entrypoint is missing: " + resourceName);
            ClassData data = new ClassData();
            new ClassReader(input).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    if (!"Lnet/minecraftforge/fml/common/Mod;".equals(descriptor)) {
                        return null;
                    }
                    return new AnnotationVisitor(Opcodes.ASM9) {
                        @Override
                        public void visit(String name, Object value) {
                            if ("value".equals(name)) {
                                data.modId = (String) value;
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
                    MethodData method = new MethodData(name, descriptor);
                    data.methods.add(method);
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
                        public void visitTypeInsn(int opcode, String type) {
                            method.types.add(type);
                        }

                        @Override
                        public void visitLdcInsn(Object value) {
                            method.constants.add(value);
                        }
                    };
                }
            }, 0);
            return data;
        }
    }

    private static final class ClassData {
        private String modId;
        private final List<MethodData> methods = new ArrayList<>();

        private MethodData constructor(String descriptor) {
            return methods.stream()
                    .filter(method -> "<init>".equals(method.name)
                            && descriptor.equals(method.descriptor))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "Forge entrypoint constructor is missing: " + descriptor
                    ));
        }
    }

    private static final class MethodData {
        private final String name;
        private final String descriptor;
        private final List<String> calls = new ArrayList<>();
        private final List<String> fields = new ArrayList<>();
        private final List<String> types = new ArrayList<>();
        private final List<Object> constants = new ArrayList<>();

        private MethodData(String name, String descriptor) {
            this.name = name;
            this.descriptor = descriptor;
        }

        private List<String> references() {
            List<String> references = new ArrayList<>(calls);
            references.addAll(fields);
            references.addAll(types);
            return references;
        }
    }
}
