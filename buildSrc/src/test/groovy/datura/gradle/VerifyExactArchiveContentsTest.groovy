package datura.gradle

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class VerifyExactArchiveContentsTest {
    @TempDir
    Path temporaryDirectory

    @Test
    void acceptsTheExactProductionClassAndResourceUnion() {
        Fixture fixture = newFixture()

        fixture.verify()
    }

    @Test
    void rejectsEmptyProductionClassInputs() {
        Fixture fixture = newFixture()
        fixture.deleteEveryFile(fixture.productionClasses)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('production class inputs are empty'))
    }

    @Test
    void rejectsDuplicateProductionClassLogicalEntriesAcrossRoots() {
        Fixture fixture = newFixture()
        Path duplicateClasses = fixture.root.resolve('duplicate-production-classes')
        fixture.writeRelative(
                duplicateClasses,
                'example/ProductionOne.class',
                'duplicate class'.bytes
        )
        fixture.task.productionClassRoots.from(duplicateClasses.toFile())

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "duplicate production class entry 'example/ProductionOne.class'"
        ))
    }

    @Test
    void rejectsEmptyProductionResourceInputs() {
        Fixture fixture = newFixture()
        fixture.deleteEveryFile(fixture.productionResources)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('production resource inputs are empty'))
    }

    @Test
    void rejectsEmptyTestOutputInputs() {
        Fixture fixture = newFixture()
        fixture.deleteEveryFile(fixture.testOutputs)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('test output inputs are empty'))
    }

    @Test
    void rejectsOneMissingProductionClassFromTheArchive() {
        Fixture fixture = newFixture()
        fixture.removeArchiveEntry('example/ProductionTwo.class')

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('missing archive entries: [example/ProductionTwo.class]'))
    }

    @Test
    void rejectsOneMissingProductionResourceFromTheArchive() {
        Fixture fixture = newFixture()
        fixture.removeArchiveEntry('assets/areamusic/lang/zh_cn.json')

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'missing archive entries: [assets/areamusic/lang/zh_cn.json]'
        ))
    }

    @Test
    void rejectsAnArbitraryExtraClass() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('example/Unexpected.class', 'extra class'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('unexpected archive entries: [example/Unexpected.class]'))
    }

    @Test
    void rejectsAnArbitraryExtraResource() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('extra/anything.bin', 'extra resource'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('extra/anything.bin'))
    }

    @Test
    void rejectsALoaderClassWithoutRelyingOnADenylist() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('net/neoforged/fml/Loader.class', 'loader class'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('net/neoforged/fml/Loader.class'))
    }

    @Test
    void rejectsALoaderResourceWithoutRelyingOnADenylist() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('META-INF/neoforge.mods.toml', 'loader metadata'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('META-INF/neoforge.mods.toml'))
    }

    @Test
    void rejectsANestedJarThroughTheExactUnion() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('META-INF/jars/dependency.jar', 'nested jar'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('META-INF/jars/dependency.jar'))
    }

    @Test
    void rejectsARealTestOutputEntry() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('example/TestOnly.class', 'test class'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'test outputs leaked into the production archive: [example/TestOnly.class]'
        ))
    }

    @Test
    void acceptsProductionEntriesAlsoPresentInTestOutputs() {
        Fixture fixture = newFixture()
        fixture.writeRelative(
                fixture.testOutputs,
                'example/ProductionOne.class',
                'test output with production class name'.bytes
        )
        fixture.writeRelative(
                fixture.testOutputs,
                'assets/areamusic/lang/en_us.json',
                'test output with production resource name'.bytes
        )

        fixture.verify()
    }

    @Test
    void rejectsAnUnexpectedFileInTheProductionResourceInputRoot() {
        Fixture fixture = newFixture()
        fixture.writeRelative(
                fixture.productionResources,
                'META-INF/neoforge.mods.toml',
                'loader metadata'.bytes
        )
        fixture.addArchiveEntry('META-INF/neoforge.mods.toml', 'loader metadata'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'unexpected production resource inputs: [META-INF/neoforge.mods.toml]'
        ))
    }

    private Fixture newFixture() {
        return new Fixture(temporaryDirectory.resolve(UUID.randomUUID().toString()))
    }

    private static GradleException assertAuditFails(Fixture fixture) {
        return assertThrows(GradleException, fixture::verify)
    }

    private static final class Fixture {
        static final List<String> EXPECTED_PRODUCTION_RESOURCES = [
                'pack.mcmeta',
                'assets/areamusic/lang/en_us.json',
                'assets/areamusic/lang/zh_cn.json'
        ].asImmutable()
        static final List<String> GENERATED_ARCHIVE_ENTRIES = [
                'META-INF/MANIFEST.MF'
        ].asImmutable()

        final Path root
        final Path productionClasses
        final Path productionResources
        final Path testOutputs
        final File archive
        final DefaultTask task
        final List<ArchiveEntryData> archiveEntries = []

        Fixture(Path root) {
            this.root = root
            Path projectDirectory = root.resolve('project')
            productionClasses = root.resolve('production-classes')
            productionResources = root.resolve('production-resources')
            testOutputs = root.resolve('test-outputs')
            [projectDirectory, productionClasses, productionResources, testOutputs].each {
                Files.createDirectories(it)
            }

            writeRelative(productionClasses, 'example/ProductionOne.class', 'class one'.bytes)
            writeRelative(productionClasses, 'example/ProductionTwo.class', 'class two'.bytes)
            writeRelative(productionResources, 'pack.mcmeta', '{"pack":{}}'.bytes)
            writeRelative(
                    productionResources,
                    'assets/areamusic/lang/en_us.json',
                    '{}'.bytes
            )
            writeRelative(
                    productionResources,
                    'assets/areamusic/lang/zh_cn.json',
                    '{}'.bytes
            )
            writeRelative(testOutputs, 'example/TestOnly.class', 'test class'.bytes)

            addArchiveEntry('example/ProductionOne.class', 'class one'.bytes)
            addArchiveEntry('example/ProductionTwo.class', 'class two'.bytes)
            addArchiveEntry('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\r\n\r\n'.bytes)
            addArchiveEntry('pack.mcmeta', '{"pack":{}}'.bytes)
            addArchiveEntry('assets/areamusic/lang/en_us.json', '{}'.bytes)
            addArchiveEntry('assets/areamusic/lang/zh_cn.json', '{}'.bytes)

            archive = root.resolve('common.jar').toFile()
            Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
            Class<? extends DefaultTask> taskType
            try {
                taskType = Class.forName('datura.gradle.VerifyExactArchiveContents')
                        .asSubclass(DefaultTask)
            } catch (ClassNotFoundException missingTask) {
                throw new AssertionError('VerifyExactArchiveContents task type is missing', missingTask)
            }
            task = project.tasks.create('verifyExactArchiveContents', taskType)
            task.archiveFile.set(archive)
            task.productionClassRoots.from(productionClasses.toFile())
            task.productionResourceRoot.set(productionResources.toFile())
            task.testOutputRoots.from(testOutputs.toFile())
            task.expectedProductionResourceEntries.set(EXPECTED_PRODUCTION_RESOURCES)
            task.generatedArchiveEntries.set(GENERATED_ARCHIVE_ENTRIES)
        }

        void verify() {
            writeArchive()
            task.verifyArchiveContents()
        }

        void addArchiveEntry(String name, byte[] bytes) {
            archiveEntries.add(new ArchiveEntryData(name, bytes))
        }

        void removeArchiveEntry(String name) {
            archiveEntries.removeAll { it.name == name }
        }

        void writeRelative(Path directory, String relativeName, byte[] bytes) {
            Path target = directory.resolve(relativeName)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }

        void deleteEveryFile(Path directory) {
            Files.walk(directory).withCloseable { paths ->
                paths.filter { Files.isRegularFile(it) }.toList().each { Files.delete(it) }
            }
        }

        private void writeArchive() {
            Files.createDirectories(archive.toPath().parent)
            ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)
            try {
                Set<String> directoryEntries = new TreeSet<>()
                archiveEntries.each { entryData ->
                    String[] segments = entryData.name.split('/')
                    String current = ''
                    for (int index = 0; index < segments.length - 1; index++) {
                        current += segments[index] + '/'
                        directoryEntries.add(current)
                    }
                }
                directoryEntries.each { directoryName ->
                    ZipArchiveEntry directory = new ZipArchiveEntry(directoryName)
                    directory.time = 0L
                    output.putArchiveEntry(directory)
                    output.closeArchiveEntry()
                }
                archiveEntries.each { entryData ->
                    ZipArchiveEntry entry = new ZipArchiveEntry(entryData.name)
                    entry.time = 0L
                    output.putArchiveEntry(entry)
                    output.write(entryData.bytes)
                    output.closeArchiveEntry()
                }
                output.finish()
            } finally {
                output.close()
            }
        }
    }

    private static final class ArchiveEntryData {
        final String name
        final byte[] bytes

        ArchiveEntryData(String name, byte[] bytes) {
            this.name = name
            this.bytes = bytes
        }
    }
}
