package datura.areamusic;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NeoForgeDedicatedServerClassGraphTest {
    private static final String ROOTS_PROPERTY =
            "areamusic.neoforge.dedicatedServerProductionRoots";
    private static final List<String> FORBIDDEN_PREFIXES = List.of(
            "net/minecraft/client/",
            "com/mojang/blaze3d/",
            "net/neoforged/neoforge/client/",
            "datura/areamusic/client/"
    );

    @Test
    void entrypointAndServerSubscriberReachNoClientOnlyProductionClasses() throws Exception {
        Set<String> visited = new LinkedHashSet<>();
        visited.addAll(walk("datura/areamusic/AreaMusic"));
        visited.addAll(walk("datura/areamusic/server/NeoForgeAreaMusicServer"));

        assertTrue(visited.contains("datura/areamusic/network/AreaMusicNetwork"));
        assertTrue(visited.contains("datura/areamusic/server/AreaMusicServer"));
        assertTrue(visited.contains("datura/areamusic/server/PlayerAreaTracker"));
        assertFalse(visited.stream().anyMatch(name -> name.startsWith("datura/areamusic/client/")));
    }

    private static Set<String> walk(String root) throws IOException {
        List<Path> roots = configuredRoots();
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            assertNotClientOnly(current);
            byte[] bytes = findClass(roots, current);
            assertNotNull(bytes, "missing reachable production class: " + current);
            for (String reference : projectReferences(bytes)) {
                assertNotClientOnly(reference);
                if (reference.startsWith("datura/areamusic/")) {
                    pending.addLast(reference);
                }
            }
        }
        return visited;
    }

    private static List<Path> configuredRoots() {
        String encoded = System.getProperty(ROOTS_PROPERTY);
        assertNotNull(encoded, "missing dedicated-server production roots property");
        return List.of(encoded.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .stream().filter(value -> !value.isBlank()).map(Path::of).toList();
    }

    private static byte[] findClass(List<Path> roots, String internalName) throws IOException {
        Path match = null;
        for (Path root : roots) {
            Path candidate = root.resolve(internalName + ".class").normalize();
            if (candidate.startsWith(root) && Files.isRegularFile(candidate)) {
                if (match != null) {
                    throw new AssertionError("production class collision: " + internalName);
                }
                match = candidate;
            }
        }
        return match == null ? null : Files.readAllBytes(match);
    }

    private static Set<String> projectReferences(byte[] bytes) {
        Set<String> references = new LinkedHashSet<>();
        new ClassReader(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override
            public void visit(int version, int access, String name, String signature,
                              String superName, String[] interfaces) {
                add(superName);
                if (interfaces != null) for (String type : interfaces) add(type);
            }

            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                             String signature, String[] exceptions) {
                addDescriptor(descriptor);
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override
                    public void visitTypeInsn(int opcode, String type) { add(type); }

                    @Override
                    public void visitFieldInsn(int opcode, String owner, String name,
                                               String descriptor) {
                        add(owner);
                        addDescriptor(descriptor);
                    }

                    @Override
                    public void visitMethodInsn(int opcode, String owner, String name,
                                                String descriptor, boolean isInterface) {
                        add(owner);
                        addDescriptor(descriptor);
                    }
                };
            }

            private void addDescriptor(String descriptor) {
                Type type = Type.getType(descriptor);
                if (type.getSort() == Type.METHOD) {
                    for (Type argument : type.getArgumentTypes()) addType(argument);
                    addType(type.getReturnType());
                } else {
                    addType(type);
                }
            }

            private void addType(Type type) {
                if (type.getSort() == Type.ARRAY) addType(type.getElementType());
                if (type.getSort() == Type.OBJECT) add(type.getInternalName());
            }

            private void add(String name) {
                if (name != null) references.add(name);
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return references;
    }

    private static void assertNotClientOnly(String name) {
        assertFalse(FORBIDDEN_PREFIXES.stream().anyMatch(name::startsWith),
                () -> "dedicated-server graph reaches client-only type: " + name);
    }
}
