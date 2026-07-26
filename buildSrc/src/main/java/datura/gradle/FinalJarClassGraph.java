package datura.gradle;

import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.ModuleVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.RecordComponentVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Reads every JVM type-bearing class-file surface used by the final-JAR audit. */
final class FinalJarClassGraph {
    private static final String PROJECT_PREFIX = "datura/areamusic/";

    private FinalJarClassGraph() {
    }

    static Set<String> references(byte[] classBytes) {
        ReferenceCollector collector = new ReferenceCollector();
        new ClassReader(classBytes).accept(collector, 0);
        return Set.copyOf(collector.references);
    }

    static List<String> verifyDedicatedServerReachability(
            Map<String, byte[]> projectClasses,
            List<String> roots,
            List<String> forbiddenPrefixes
    ) {
        List<String> errors = new ArrayList<>();
        for (String root : roots) {
            walkRoot(projectClasses, root, forbiddenPrefixes, errors);
        }
        return List.copyOf(errors);
    }

    private static void walkRoot(
            Map<String, byte[]> projectClasses,
            String root,
            List<String> forbiddenPrefixes,
            List<String> errors
    ) {
        Set<String> visited = new LinkedHashSet<>();
        Deque<PendingClass> pending = new ArrayDeque<>();
        pending.addLast(new PendingClass(root, List.of(root)));
        while (!pending.isEmpty()) {
            PendingClass current = pending.removeFirst();
            if (!visited.add(current.internalName())) {
                continue;
            }
            byte[] bytes = projectClasses.get(current.internalName());
            if (bytes == null) {
                errors.add("dedicated-server graph is missing reachable project class '"
                        + current.internalName() + "' via " + formatPath(current.path()));
                continue;
            }
            Set<String> references;
            try {
                references = new TreeSet<>(references(bytes));
            } catch (RuntimeException invalidClass) {
                errors.add("dedicated-server graph class '" + current.internalName()
                        + "' is invalid via " + formatPath(current.path()) + ": "
                        + invalidClass.getMessage());
                continue;
            }
            for (String reference : references) {
                List<String> path = append(current.path(), reference);
                if (startsWithAny(reference, forbiddenPrefixes)) {
                    errors.add("dedicated-server graph reaches client-only type '"
                            + reference + "' via " + formatPath(path));
                } else if (reference.startsWith(PROJECT_PREFIX)
                        && !visited.contains(reference)) {
                    pending.addLast(new PendingClass(reference, path));
                }
            }
        }
    }

    private static boolean startsWithAny(String value, List<String> prefixes) {
        for (String prefix : prefixes) {
            if (value.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> append(List<String> path, String value) {
        List<String> result = new ArrayList<>(path.size() + 1);
        result.addAll(path);
        result.add(value);
        return List.copyOf(result);
    }

    private static String formatPath(List<String> path) {
        return String.join(" -> ", path);
    }

    private record PendingClass(String internalName, List<String> path) {
    }

    private static final class ReferenceCollector extends ClassVisitor {
        private final Set<String> references = new LinkedHashSet<>();

        private ReferenceCollector() {
            super(Opcodes.ASM9);
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
            addInternalName(superName);
            if (interfaces != null) {
                for (String interfaceName : interfaces) {
                    addInternalName(interfaceName);
                }
            }
            addSignature(signature, false);
        }

        @Override
        public ModuleVisitor visitModule(String name, int access, String version) {
            return new ModuleVisitor(Opcodes.ASM9) {
                @Override
                public void visitMainClass(String mainClass) {
                    addInternalName(mainClass);
                }

                @Override
                public void visitUse(String service) {
                    addInternalName(service);
                }

                @Override
                public void visitProvide(String service, String... providers) {
                    addInternalName(service);
                    if (providers != null) {
                        for (String provider : providers) {
                            addInternalName(provider);
                        }
                    }
                }
            };
        }

        @Override
        public void visitNestHost(String nestHost) {
            addInternalName(nestHost);
        }

        @Override
        public void visitOuterClass(String owner, String name, String descriptor) {
            addInternalName(owner);
            addDescriptor(descriptor);
        }

        @Override
        public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
            return annotation(descriptor);
        }

        @Override
        public AnnotationVisitor visitTypeAnnotation(
                int typeRef,
                TypePath typePath,
                String descriptor,
                boolean visible
        ) {
            return annotation(descriptor);
        }

        @Override
        public void visitNestMember(String nestMember) {
            addInternalName(nestMember);
        }

        @Override
        public void visitPermittedSubclass(String permittedSubclass) {
            addInternalName(permittedSubclass);
        }

        @Override
        public void visitInnerClass(String name, String outerName, String innerName, int access) {
            addInternalName(name);
            addInternalName(outerName);
        }

        @Override
        public RecordComponentVisitor visitRecordComponent(
                String name,
                String descriptor,
                String signature
        ) {
            addDescriptor(descriptor);
            addSignature(signature, true);
            return new RecordComponentVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
                }
            };
        }

        @Override
        public FieldVisitor visitField(
                int access,
                String name,
                String descriptor,
                String signature,
                Object value
        ) {
            addDescriptor(descriptor);
            addSignature(signature, true);
            addConstant(value);
            return new FieldVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
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
            addDescriptor(descriptor);
            addSignature(signature, false);
            if (exceptions != null) {
                for (String exception : exceptions) {
                    addInternalName(exception);
                }
            }
            return new MethodVisitor(Opcodes.ASM9) {
                @Override
                public AnnotationVisitor visitAnnotationDefault() {
                    return annotationValues();
                }

                @Override
                public AnnotationVisitor visitAnnotation(String descriptor, boolean visible) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitTypeAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitParameterAnnotation(
                        int parameter,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
                }

                @Override
                public void visitFrame(
                        int type,
                        int numLocal,
                        Object[] local,
                        int numStack,
                        Object[] stack
                ) {
                    addFrameEntries(numLocal, local);
                    addFrameEntries(numStack, stack);
                }

                @Override
                public void visitTypeInsn(int opcode, String type) {
                    addInternalName(type);
                }

                @Override
                public void visitFieldInsn(
                        int opcode,
                        String owner,
                        String name,
                        String descriptor
                ) {
                    addInternalName(owner);
                    addDescriptor(descriptor);
                }

                @Override
                public void visitMethodInsn(
                        int opcode,
                        String owner,
                        String name,
                        String descriptor,
                        boolean isInterface
                ) {
                    addInternalName(owner);
                    addDescriptor(descriptor);
                }

                @Override
                public void visitInvokeDynamicInsn(
                        String name,
                        String descriptor,
                        Handle bootstrapMethodHandle,
                        Object... bootstrapMethodArguments
                ) {
                    addDescriptor(descriptor);
                    addHandle(bootstrapMethodHandle);
                    if (bootstrapMethodArguments != null) {
                        for (Object argument : bootstrapMethodArguments) {
                            addConstant(argument);
                        }
                    }
                }

                @Override
                public void visitLdcInsn(Object value) {
                    addConstant(value);
                }

                @Override
                public void visitMultiANewArrayInsn(String descriptor, int numDimensions) {
                    addDescriptor(descriptor);
                }

                @Override
                public void visitTryCatchBlock(
                        Label start,
                        Label end,
                        Label handler,
                        String type
                ) {
                    addInternalName(type);
                }

                @Override
                public AnnotationVisitor visitInsnAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitTryCatchAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
                }

                @Override
                public void visitLocalVariable(
                        String name,
                        String descriptor,
                        String signature,
                        Label start,
                        Label end,
                        int index
                ) {
                    addDescriptor(descriptor);
                    addSignature(signature, true);
                }

                @Override
                public AnnotationVisitor visitLocalVariableAnnotation(
                        int typeRef,
                        TypePath typePath,
                        Label[] start,
                        Label[] end,
                        int[] index,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
                }
            };
        }

        private AnnotationVisitor annotation(String descriptor) {
            addDescriptor(descriptor);
            return annotationValues();
        }

        private AnnotationVisitor annotationValues() {
            return new AnnotationVisitor(Opcodes.ASM9) {
                @Override
                public void visit(String name, Object value) {
                    addConstant(value);
                }

                @Override
                public void visitEnum(String name, String descriptor, String value) {
                    addDescriptor(descriptor);
                }

                @Override
                public AnnotationVisitor visitAnnotation(String name, String descriptor) {
                    return annotation(descriptor);
                }

                @Override
                public AnnotationVisitor visitArray(String name) {
                    return annotationValues();
                }
            };
        }

        private void addFrameEntries(int count, Object[] entries) {
            if (entries == null) {
                return;
            }
            for (int index = 0; index < count; index++) {
                Object entry = entries[index];
                if (entry instanceof String internalName) {
                    addInternalName(internalName);
                }
            }
        }

        private void addConstant(Object value) {
            if (value instanceof Type type) {
                addType(type);
            } else if (value instanceof Handle handle) {
                addHandle(handle);
            } else if (value instanceof ConstantDynamic dynamic) {
                addDescriptor(dynamic.getDescriptor());
                addHandle(dynamic.getBootstrapMethod());
                for (int index = 0; index < dynamic.getBootstrapMethodArgumentCount(); index++) {
                    addConstant(dynamic.getBootstrapMethodArgument(index));
                }
            } else if (value instanceof Object[] values) {
                for (Object nested : values) {
                    addConstant(nested);
                }
            }
        }

        private void addHandle(Handle handle) {
            if (handle == null) {
                return;
            }
            addInternalName(handle.getOwner());
            addDescriptor(handle.getDesc());
        }

        private void addDescriptor(String descriptor) {
            if (descriptor != null) {
                addType(Type.getType(descriptor));
            }
        }

        private void addType(Type type) {
            switch (type.getSort()) {
                case Type.ARRAY -> addType(type.getElementType());
                case Type.OBJECT -> addInternalName(type.getInternalName());
                case Type.METHOD -> {
                    for (Type argument : type.getArgumentTypes()) {
                        addType(argument);
                    }
                    addType(type.getReturnType());
                }
                default -> {
                }
            }
        }

        private void addInternalName(String internalName) {
            if (internalName != null) {
                if (internalName.startsWith("[")) {
                    addDescriptor(internalName);
                } else {
                    references.add(internalName);
                }
            }
        }

        private void addSignature(String signature, boolean typeSignature) {
            if (signature == null) {
                return;
            }
            SignatureVisitor visitor = new CollectingSignatureVisitor();
            SignatureReader reader = new SignatureReader(signature);
            if (typeSignature) {
                reader.acceptType(visitor);
            } else {
                reader.accept(visitor);
            }
        }

        private final class CollectingSignatureVisitor extends SignatureVisitor {
            private String currentClass;

            private CollectingSignatureVisitor() {
                super(Opcodes.ASM9);
            }

            @Override
            public void visitClassType(String name) {
                currentClass = name;
                addInternalName(name);
            }

            @Override
            public void visitInnerClassType(String name) {
                if (currentClass != null) {
                    currentClass = currentClass + "$" + name;
                    addInternalName(currentClass);
                }
            }

            @Override
            public void visitEnd() {
                currentClass = null;
            }
        }
    }
}
