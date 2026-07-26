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

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.fail

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

    @Test
    void rejectsAClassCompiledForAnUnexpectedJavaMajorVersion() {
        Path classes = temporaryDirectory.resolve('classes')
        writeClass(classes, 'example/Java17.class', allowedClass('example/Java17', Opcodes.V17))
        def task = newTask(classes, ['net/minecraftforge'])
        requireExpectedClassMajor(task).set(65)

        GradleException failure = assertThrows(GradleException, task::verifyClassReferences)

        assertTrue(failure.message.contains('Java17.class'))
        assertTrue(failure.message.contains('major version 61'))
        assertTrue(failure.message.contains('expected 65'))
    }

    @Test
    void acceptsAClassCompiledForTheExpectedJavaMajorVersion() {
        Path classes = temporaryDirectory.resolve('classes')
        writeClass(classes, 'example/Java21.class', allowedClass('example/Java21', Opcodes.V21))
        def task = newTask(classes, ['net/minecraftforge'])
        requireExpectedClassMajor(task).set(65)

        task.verifyClassReferences()
    }

    @Test
    void legacyModeDefaultsToFalseAndAcceptsOnlyClassRoots() {
        Path classes = temporaryDirectory.resolve('legacy-classes')
        writeClass(classes, 'example/Legacy.class', allowedClass('example/Legacy', Opcodes.V17))
        def task = newTask(classes, ['net/minecraftforge'])

        assertFalse(requireTaskProperty(task, 'separatedMode').get())
        task.verifyClassReferences()
    }

    @Test
    void legacyModeRejectsAnySeparatedInput() {
        Path classes = temporaryDirectory.resolve('mixed-legacy-classes')
        writeClass(classes, 'example/Legacy.class', allowedClass('example/Legacy', Opcodes.V17))
        def task = newTask(classes, ['net/minecraftforge'])
        Path productionSource = writeSource(
                temporaryDirectory.resolve('mixed-production-sources'),
                'example/Production.java'
        )
        requireTaskProperty(task, 'productionSourceFiles').from(productionSource.toFile())

        GradleException failure = assertThrows(GradleException, task::verifyClassReferences)

        assertTrue(failure.message.contains('legacy mode cannot use separated inputs'))
    }

    @Test
    void separatedModeRejectsLegacyClassRoots() {
        Path classes = temporaryDirectory.resolve('mixed-separated-classes')
        writeClass(classes, 'example/Legacy.class', allowedClass('example/Legacy', Opcodes.V17))
        def task = newTask(classes, ['net/minecraftforge'])
        requireTaskProperty(task, 'separatedMode').set(true)

        GradleException failure = assertThrows(GradleException, task::verifyClassReferences)

        assertTrue(failure.message.contains('separated mode cannot use classRoots'))
    }

    @Test
    void explicitSeparatedModeRejectsAllFourEmptyInputSets() {
        def task = newEmptyTask(['net/minecraftforge'])
        requireTaskProperty(task, 'separatedMode').set(true)

        GradleException failure = assertThrows(GradleException, task::verifyClassReferences)

        assertTrue(failure.message.contains('production source set is empty'))
        assertTrue(failure.message.contains('test source set is empty'))
        assertTrue(failure.message.contains('production compiled class set is empty'))
        assertTrue(failure.message.contains('test compiled class set is empty'))
    }

    @Test
    void acceptsSeparateNonEmptyProductionAndTestSourceAndClassSets() {
        SeparatedFixture fixture = newSeparatedFixture()

        fixture.verify()
    }

    @Test
    void rejectsAnEmptyProductionSourceSet() {
        SeparatedFixture fixture = newSeparatedFixture()
        fixture.task.productionSourceFiles.setFrom([])

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains('production source set is empty'))
    }

    @Test
    void rejectsAnEmptyTestSourceSet() {
        SeparatedFixture fixture = newSeparatedFixture()
        fixture.task.testSourceFiles.setFrom([])

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains('test source set is empty'))
    }

    @Test
    void rejectsAnEmptyProductionCompiledClassSet() {
        SeparatedFixture fixture = newSeparatedFixture()
        fixture.deleteEveryClass(fixture.productionClasses)

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains('production compiled class set is empty'))
    }

    @Test
    void rejectsAnEmptyTestCompiledClassSet() {
        SeparatedFixture fixture = newSeparatedFixture()
        fixture.deleteEveryClass(fixture.testClasses)

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains('test compiled class set is empty'))
    }

    @Test
    void rejectsOneMissingProductionPrimaryClass() {
        SeparatedFixture fixture = newSeparatedFixture()
        Files.delete(fixture.productionClasses.resolve('example/ProductionTwo.class'))

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains(
                'production source example/ProductionTwo.java has no compiled primary class example/ProductionTwo.class'
        ))
    }

    @Test
    void rejectsOneMissingTestPrimaryClass() {
        SeparatedFixture fixture = newSeparatedFixture()
        Files.delete(fixture.testClasses.resolve('example/TestTwo.class'))

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains(
                'test source example/TestTwo.java has no compiled primary class example/TestTwo.class'
        ))
    }

    @Test
    void rejectsAClassWhoseInternalNameDoesNotMatchItsRelativePath() {
        SeparatedFixture fixture = newSeparatedFixture()
        writeClass(
                fixture.productionClasses,
                'example/ProductionTwo.class',
                allowedClass('example/RenamedProduction', Opcodes.V21)
        )

        GradleException failure = assertThrows(GradleException, fixture::verify)

        assertTrue(failure.message.contains(
                'production example/ProductionTwo.class declares example/RenamedProduction.class'
        ))
    }

    private DefaultTask newTask(Path classes, List<String> forbiddenPrefixes) {
        def task = newEmptyTask(forbiddenPrefixes)
        task.classRoots.from(classes.toFile())
        return task
    }

    private DefaultTask newEmptyTask(List<String> forbiddenPrefixes) {
        Class<? extends DefaultTask> taskType
        try {
            taskType = Class.forName('datura.gradle.VerifyClassBoundaries').asSubclass(DefaultTask)
        } catch (ClassNotFoundException missingTask) {
            throw new AssertionError('VerifyClassBoundaries task type is missing', missingTask)
        }
        Path projectDirectory = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Files.createDirectories(projectDirectory)
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        def task = project.tasks.create('verifyClassBoundaries' + UUID.randomUUID(), taskType)
        task.boundaryName.set('fixture')
        task.forbiddenInternalNamePrefixes.set(forbiddenPrefixes)
        return task
    }

    private static Object requireExpectedClassMajor(DefaultTask task) {
        def property = task.metaClass.hasProperty(task, 'expectedClassMajor')
        if (property == null) {
            fail('VerifyClassBoundaries must expose expectedClassMajor')
        }
        return task.expectedClassMajor
    }

    private SeparatedFixture newSeparatedFixture() {
        Path fixtureRoot = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Path projectDirectory = fixtureRoot.resolve('project')
        Path productionSources = fixtureRoot.resolve('production-sources')
        Path testSources = fixtureRoot.resolve('test-sources')
        Path productionClasses = fixtureRoot.resolve('production-classes')
        Path testClasses = fixtureRoot.resolve('test-classes')
        [projectDirectory, productionSources, testSources, productionClasses, testClasses].each {
            Files.createDirectories(it)
        }

        List<Path> productionSourceFiles = [
                writeSource(productionSources, 'example/ProductionOne.java'),
                writeSource(productionSources, 'example/ProductionTwo.java')
        ]
        List<Path> testSourceFiles = [
                writeSource(testSources, 'example/TestOne.java'),
                writeSource(testSources, 'example/TestTwo.java')
        ]
        writeClass(
                productionClasses,
                'example/ProductionOne.class',
                allowedClass('example/ProductionOne', Opcodes.V21)
        )
        writeClass(
                productionClasses,
                'example/ProductionTwo.class',
                allowedClass('example/ProductionTwo', Opcodes.V21)
        )
        writeClass(testClasses, 'example/TestOne.class', allowedClass('example/TestOne', Opcodes.V21))
        writeClass(testClasses, 'example/TestTwo.class', allowedClass('example/TestTwo', Opcodes.V21))

        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        def task = project.tasks.create('verifySeparatedClassBoundaries', VerifyClassBoundaries)
        requireTaskProperty(task, 'productionSourceFiles').from(productionSourceFiles.collect { it.toFile() })
        requireTaskProperty(task, 'testSourceFiles').from(testSourceFiles.collect { it.toFile() })
        requireTaskProperty(task, 'productionClassRoots').from(productionClasses.toFile())
        requireTaskProperty(task, 'testClassRoots').from(testClasses.toFile())
        requireTaskProperty(task, 'separatedMode').set(true)
        task.boundaryName.set('fixture')
        task.forbiddenInternalNamePrefixes.set(['net/minecraftforge'])
        task.expectedClassMajor.set(65)
        return new SeparatedFixture(task, productionClasses, testClasses)
    }

    private static Object requireTaskProperty(DefaultTask task, String propertyName) {
        def property = task.metaClass.hasProperty(task, propertyName)
        if (property == null) {
            fail("VerifyClassBoundaries must expose ${propertyName}".toString())
        }
        return task."${propertyName}"
    }

    private static Path writeSource(Path root, String relativePath) {
        Path target = root.resolve(relativePath)
        Files.createDirectories(target.parent)
        String className = target.fileName.toString().replaceFirst(/\.java$/, '')
        Files.writeString(target, "package example;\npublic class ${className} {}\n")
        return target
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
        return allowedClass('example/Allowed', Opcodes.V17)
    }

    private static byte[] allowedClass(int classVersion) {
        return allowedClass('example/Allowed', classVersion)
    }

    private static byte[] allowedClass(String internalName, int classVersion) {
        ClassWriter writer = new ClassWriter(0)
        writer.visit(classVersion, Opcodes.ACC_PUBLIC, internalName, null, 'java/lang/Object', null)
        writer.visitField(Opcodes.ACC_PRIVATE, 'value', 'Ljava/lang/String;', null, null).visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static void writeClass(Path root, String relativePath, byte[] bytes) {
        Path target = root.resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.write(target, bytes)
    }

    private static final class SeparatedFixture {
        final def task
        final Path productionClasses
        final Path testClasses

        SeparatedFixture(def task, Path productionClasses, Path testClasses) {
            this.task = task
            this.productionClasses = productionClasses
            this.testClasses = testClasses
        }

        void verify() {
            task.verifyClassReferences()
        }

        void deleteEveryClass(Path root) {
            Files.walk(root).withCloseable { paths ->
                paths.filter { Files.isRegularFile(it) }.toList().each { Files.delete(it) }
            }
        }
    }
}
