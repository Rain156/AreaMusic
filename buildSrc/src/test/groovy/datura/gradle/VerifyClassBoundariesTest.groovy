package datura.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Handle
import org.objectweb.asm.Label
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class VerifyClassBoundariesTest {
    @TempDir
    Path temporaryDirectory

    @Test
    void rejectsForbiddenInternalNamesAcrossClassMetadataAndInstructions() {
        Path classes = temporaryDirectory.resolve('classes')
        writeClass(classes, 'example/ForbiddenReferences.class', forbiddenReferenceClass())
        def task = newTask(classes, ['net/minecraftforge'])

        GradleException failure = assertThrows(GradleException, task::verifyClassReferences)

        [
                'net/minecraftforge/BaseType',
                'net/minecraftforge/InterfaceType',
                'net/minecraftforge/GenericType',
                'net/minecraftforge/AnnotationType',
                'net/minecraftforge/FieldType',
                'net/minecraftforge/ParameterType',
                'net/minecraftforge/ReturnType',
                'net/minecraftforge/InstructionOwner',
                'net/minecraftforge/HandleOwner',
                'net/minecraftforge/FrameOnlyType',
                'net/minecraftforge/LocalVariableOnlyType'
        ].each { forbiddenName ->
            assertTrue(failure.message.contains(forbiddenName), "missing violation for ${forbiddenName}")
        }
    }

    @Test
    void acceptsClassesThatReferenceOnlyAllowedNamespaces() {
        Path classes = temporaryDirectory.resolve('classes')
        writeClass(classes, 'example/Allowed.class', allowedClass())
        def task = newTask(classes, ['net/minecraftforge'])

        task.verifyClassReferences()
    }

    private DefaultTask newTask(Path classes, List<String> forbiddenPrefixes) {
        Class<? extends DefaultTask> taskType
        try {
            taskType = Class.forName('datura.gradle.VerifyClassBoundaries').asSubclass(DefaultTask)
        } catch (ClassNotFoundException missingTask) {
            throw new AssertionError('VerifyClassBoundaries task type is missing', missingTask)
        }
        Path projectDirectory = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Files.createDirectories(projectDirectory)
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        def task = project.tasks.create('verifyClassBoundaries', taskType)
        task.classRoots.from(classes.toFile())
        task.boundaryName.set('fixture')
        task.forbiddenInternalNamePrefixes.set(forbiddenPrefixes)
        return task
    }

    private static byte[] forbiddenReferenceClass() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC,
                'example/ForbiddenReferences',
                'Ljava/lang/Object;Lnet/minecraftforge/GenericType;',
                'net/minecraftforge/BaseType',
                ['net/minecraftforge/InterfaceType'] as String[]
        )
        writer.visitAnnotation('Lnet/minecraftforge/AnnotationType;', true).visitEnd()
        writer.visitField(
                Opcodes.ACC_PRIVATE,
                'value',
                'Lnet/minecraftforge/FieldType;',
                null,
                null
        ).visitEnd()
        MethodVisitor method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                'probe',
                '(Lnet/minecraftforge/ParameterType;)Lnet/minecraftforge/ReturnType;',
                null,
                null
        )
        method.visitCode()
        Label start = new Label()
        Label end = new Label()
        method.visitLabel(start)
        method.visitFrame(
                Opcodes.F_FULL,
                1,
                ['net/minecraftforge/FrameOnlyType'] as Object[],
                0,
                new Object[0]
        )
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitTypeInsn(Opcodes.CHECKCAST, 'net/minecraftforge/InstructionOwner')
        method.visitInsn(Opcodes.POP)
        method.visitLdcInsn(new Handle(
                Opcodes.H_INVOKESTATIC,
                'net/minecraftforge/HandleOwner',
                'call',
                '()V',
                false
        ))
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.ACONST_NULL)
        method.visitInsn(Opcodes.ARETURN)
        method.visitLabel(end)
        method.visitLocalVariable(
                'local',
                'Lnet/minecraftforge/LocalVariableOnlyType;',
                null,
                start,
                end,
                0
        )
        method.visitMaxs(1, 1)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] allowedClass() {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, 'example/Allowed', null, 'java/lang/Object', null)
        writer.visitField(Opcodes.ACC_PRIVATE, 'value', 'Ljava/lang/String;', null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static void writeClass(Path root, String relativePath, byte[] bytes) {
        Path target = root.resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }
}
