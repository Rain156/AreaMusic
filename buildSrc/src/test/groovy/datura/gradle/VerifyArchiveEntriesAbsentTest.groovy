package datura.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assertions.fail

class VerifyArchiveEntriesAbsentTest {
    private static final String FORBIDDEN_ENTRY = 'META-INF/services/javax.sound.sampled.spi.AudioFileReader'

    @TempDir
    Path temporaryDirectory

    @Test
    void rejectsAForbiddenPackagingResourceInAnArtifact() {
        Path archive = writeArchive('forbidden.jar', ['example/Allowed.class', FORBIDDEN_ENTRY])
        def task = newTask(archive)

        GradleException failure = assertThrows(GradleException, task::verifyArchive)

        assertTrue(failure.message.contains(FORBIDDEN_ENTRY))
    }

    @Test
    void acceptsAnArtifactWithoutPackagingResources() {
        Path archive = writeArchive('allowed.jar', ['example/Allowed.class'])
        def task = newTask(archive)

        task.verifyArchive()
    }

    @Test
    void rejectsAnArchiveEntryOwnedByAForbiddenContentRoot() {
        Path forbiddenRoot = temporaryDirectory.resolve('test-classes')
        Path forbiddenClass = forbiddenRoot.resolve('example/AreaMusicServerTest.class')
        Files.createDirectories(forbiddenClass.parent)
        Files.writeString(forbiddenClass, 'test output')
        Path archive = writeArchive('test-output.jar', ['example/AreaMusicServerTest.class'])
        def task = newTask(archive)
        requireForbiddenContentRoots(task).from(forbiddenRoot.toFile())

        GradleException failure = assertThrows(GradleException, task::verifyArchive)

        assertTrue(failure.message.contains('example/AreaMusicServerTest.class'))
    }

    private DefaultTask newTask(Path archive) {
        Class<? extends DefaultTask> taskType
        try {
            taskType = Class.forName('datura.gradle.VerifyArchiveEntriesAbsent').asSubclass(DefaultTask)
        } catch (ClassNotFoundException missingTask) {
            throw new AssertionError('VerifyArchiveEntriesAbsent task type is missing', missingTask)
        }
        Path projectDirectory = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Files.createDirectories(projectDirectory)
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        def task = project.tasks.create('verifyArchiveEntriesAbsent', taskType)
        task.archiveFile.set(archive.toFile())
        task.forbiddenEntries.set([FORBIDDEN_ENTRY])
        return task
    }

    private static Object requireForbiddenContentRoots(DefaultTask task) {
        def property = task.metaClass.hasProperty(task, 'forbiddenContentRoots')
        if (property == null) {
            fail('VerifyArchiveEntriesAbsent must expose forbiddenContentRoots')
        }
        return task.forbiddenContentRoots
    }

    private Path writeArchive(String fileName, List<String> entries) {
        Path archive = temporaryDirectory.resolve(fileName)
        ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))
        try {
            entries.each { name ->
                output.putNextEntry(new ZipEntry(name))
                output.write(name.bytes)
                output.closeEntry()
            }
        } finally {
            output.close()
        }
        return archive
    }
}
