package datura.areamusic;

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

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

final class DedicatedServerClassGraph {
    private static final String PROJECT_CLASS_PREFIX = "datura/areamusic/";
    private static final List<String> CLIENT_ONLY_PREFIXES = List.of(
            "net/minecraft/client/",
            "com/mojang/blaze3d/",
            "net/minecraftforge/client/",
            "datura/areamusic/client/"
    );

    private DedicatedServerClassGraph() {
    }

    static List<String> verify(String root, ClassBytesResolver resolver) throws IOException {
        return new Walker(resolver).verify(root);
    }

    @FunctionalInterface
    interface ClassBytesResolver {
        byte[] resolve(String internalName) throws IOException;
    }

    private static final class Walker {
        private final ClassBytesResolver resolver;
        private final Set<String> visited = new LinkedHashSet<>();
        private final Map<String, List<String>> discoveredPaths = new HashMap<>();
        private final Deque<PendingClass> pending = new ArrayDeque<>();

        private Walker(ClassBytesResolver resolver) {
            this.resolver = resolver;
        }

        private List<String> verify(String root) throws IOException {
            List<String> rootPath = List.of(root);
            assertNotClientOnly(root, rootPath);
            if (!root.startsWith(PROJECT_CLASS_PREFIX)) {
                throw new AssertionError("Dedicated-server graph root is not a project class: " + root);
            }
            discoveredPaths.put(root, rootPath);
            pending.addLast(new PendingClass(root, rootPath));

            while (!pending.isEmpty()) {
                PendingClass current = pending.removeFirst();
                if (!visited.add(current.internalName())) {
                    continue;
                }
                byte[] classBytes = resolver.resolve(current.internalName());
                if (classBytes == null) {
                    throw new AssertionError(
                            "Missing reachable project class on dedicated-server path: "
                                    + formatPath(current.path())
                    );
                }

                for (String reference : new TreeSet<>(
                        ClassReferenceCollector.collect(classBytes)
                )) {
                    List<String> referencePath = append(current.path(), reference);
                    assertNotClientOnly(reference, referencePath);
                    if (reference.startsWith(PROJECT_CLASS_PREFIX)
                            && !discoveredPaths.containsKey(reference)) {
                        discoveredPaths.put(reference, referencePath);
                        pending.addLast(new PendingClass(reference, referencePath));
                    }
                }
            }

            return List.copyOf(visited);
        }

        private static void assertNotClientOnly(String internalName, List<String> path) {
            for (String forbidden : CLIENT_ONLY_PREFIXES) {
                if (internalName.startsWith(forbidden)) {
                    throw new AssertionError(
                            "Dedicated-server class graph reaches client-only type '"
                                    + internalName
                                    + "' via "
                                    + formatPath(path)
                    );
                }
            }
        }

        private static List<String> append(List<String> path, String internalName) {
            List<String> extended = new ArrayList<>(path.size() + 1);
            extended.addAll(path);
            extended.add(internalName);
            return List.copyOf(extended);
        }

        private static String formatPath(List<String> path) {
            return String.join(" -> ", path);
        }

        private record PendingClass(String internalName, List<String> path) {
        }
    }

    private static final class ClassReferenceCollector extends ClassVisitor {
        private final Set<String> references = new LinkedHashSet<>();

        private ClassReferenceCollector() {
            super(Opcodes.ASM9);
        }

        private static Set<String> collect(byte[] classBytes) {
            ClassReferenceCollector collector = new ClassReferenceCollector();
            new ClassReader(classBytes).accept(collector, 0);
            return Set.copyOf(collector.references);
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
        public void visitInnerClass(
                String name,
                String outerName,
                String innerName,
                int access
        ) {
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
                public AnnotationVisitor visitInsnAnnotation(
                        int typeRef,
                        TypePath typePath,
                        String descriptor,
                        boolean visible
                ) {
                    return annotation(descriptor);
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
            if (internalName == null) {
                return;
            }
            if (internalName.startsWith("[")) {
                addDescriptor(internalName);
            } else {
                references.add(internalName);
            }
        }

        private void addSignature(String signature, boolean typeSignature) {
            if (signature == null) {
                return;
            }
            SignatureReader reader = new SignatureReader(signature);
            SignatureVisitor visitor = new ReferenceSignatureVisitor(this);
            if (typeSignature) {
                reader.acceptType(visitor);
            } else {
                reader.accept(visitor);
            }
        }
    }

    private static final class ReferenceSignatureVisitor extends SignatureVisitor {
        private final ClassReferenceCollector collector;
        private String currentClass;

        private ReferenceSignatureVisitor(ClassReferenceCollector collector) {
            super(Opcodes.ASM9);
            this.collector = collector;
        }

        @Override
        public SignatureVisitor visitClassBound() {
            return nested();
        }

        @Override
        public SignatureVisitor visitInterfaceBound() {
            return nested();
        }

        @Override
        public SignatureVisitor visitSuperclass() {
            return nested();
        }

        @Override
        public SignatureVisitor visitInterface() {
            return nested();
        }

        @Override
        public SignatureVisitor visitParameterType() {
            return nested();
        }

        @Override
        public SignatureVisitor visitReturnType() {
            return nested();
        }

        @Override
        public SignatureVisitor visitExceptionType() {
            return nested();
        }

        @Override
        public SignatureVisitor visitArrayType() {
            return nested();
        }

        @Override
        public void visitClassType(String name) {
            currentClass = name;
            collector.addInternalName(name);
        }

        @Override
        public void visitInnerClassType(String name) {
            currentClass = currentClass == null ? name : currentClass + "$" + name;
            collector.addInternalName(currentClass);
        }

        @Override
        public SignatureVisitor visitTypeArgument(char wildcard) {
            return nested();
        }

        @Override
        public void visitEnd() {
            currentClass = null;
        }

        private ReferenceSignatureVisitor nested() {
            return new ReferenceSignatureVisitor(collector);
        }
    }
}
