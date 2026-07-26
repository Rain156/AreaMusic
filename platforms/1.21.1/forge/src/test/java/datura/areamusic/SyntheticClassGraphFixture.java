package datura.areamusic;

import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

final class SyntheticClassGraphFixture {
    private SyntheticClassGraphFixture() {
    }

    static byte[] linkingClass(String className, String target) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "reachHelper",
                "()V",
                null,
                null
        );
        method.visitCode();
        method.visitMethodInsn(
                Opcodes.INVOKESTATIC,
                target,
                "touch",
                "()V",
                false
        );
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] classWithFieldType(String className, String fieldType) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        writer.visitField(
                Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC,
                "linkedType",
                "L" + fieldType + ";",
                null,
                null
        ).visitEnd();
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "touch",
                "()V",
                null,
                null
        );
        method.visitCode();
        method.visitInsn(Opcodes.RETURN);
        method.visitMaxs(0, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }

    static byte[] classWithStringConstant(String className, String text) {
        ClassWriter writer = new ClassWriter(0);
        writer.visit(Opcodes.V21, Opcodes.ACC_PUBLIC, className, null, "java/lang/Object", null);
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                "text",
                "()Ljava/lang/String;",
                null,
                null
        );
        method.visitCode();
        method.visitLdcInsn(text);
        method.visitInsn(Opcodes.ARETURN);
        method.visitMaxs(1, 0);
        method.visitEnd();
        writer.visitEnd();
        return writer.toByteArray();
    }
}
