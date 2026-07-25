package datura.gradle

import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.bundling.Jar
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class ForgeAuditWiringTest {
    private static final String EXPECTED_FILE_NAME = 'areamusic-forge-1.20.1-1.2.3.jar'

    @TempDir
    Path temporaryDirectory

    @Test
    void forgeAuditIsWiredToTheTypedReobfJarArchiveProvider() {
        String buildScript = Files.readString(findForgeBuildScript())

        assertTrue(buildScript.contains("def reobfJarTask = tasks.named('reobfJar', Jar)"))
        assertTrue(buildScript.contains('productionJar.set(reobfJarTask.flatMap { it.archiveFile })'))
        assertFalse(buildScript.contains('layout.buildDirectory.file("libs/${mod_id}-forge-${minecraft_version}-${mod_version}.jar")'))
    }

    @Test
    void archiveProviderFollowsRelocatedReobfOutputAndIgnoresAStaleOldArtifact() {
        Path projectDirectory = temporaryDirectory.resolve('project')
        Files.createDirectories(projectDirectory)
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()

        def reobfJarTask = project.tasks.register('reobfJar', Jar) {
            archiveFileName.set(EXPECTED_FILE_NAME)
            destinationDirectory.set(project.layout.buildDirectory.dir('libs'))
        }
        VerifyProductionJar audit = project.tasks.create('verifyProductionJar', VerifyProductionJar)
        Provider<RegularFile> reobfOutput = reobfJarTask.flatMap { it.archiveFile }
        audit.productionJar.set(reobfOutput)

        Path staleArtifact = project.layout.buildDirectory.file("libs/${EXPECTED_FILE_NAME}").get().asFile.toPath()
        Files.createDirectories(staleArtifact.parent)
        Files.write(staleArtifact, 'stale artifact'.bytes)

        reobfJarTask.get().destinationDirectory.set(project.layout.buildDirectory.dir('relocated-output'))
        Path relocatedArtifact = project.layout.buildDirectory.file(
                "relocated-output/${EXPECTED_FILE_NAME}"
        ).get().asFile.toPath()

        assertEquals(relocatedArtifact.toFile().canonicalFile, audit.productionJar.get().asFile.canonicalFile)
        assertFalse(audit.productionJar.get().asFile.canonicalFile == staleArtifact.toFile().canonicalFile)
        assertTrue(Files.isRegularFile(staleArtifact))
    }

    private static Path findForgeBuildScript() {
        Path current = Path.of(System.getProperty('user.dir')).toAbsolutePath()
        while (current != null) {
            Path candidate = current.resolve('platforms/1.20.1/forge/build.gradle')
            if (Files.isRegularFile(candidate)) {
                return candidate
            }
            current = current.parent
        }
        throw new AssertionError('Could not locate platforms/1.20.1/forge/build.gradle')
    }
}
