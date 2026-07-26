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
        assertFalse(buildScript.contains('layout.buildDirectory.file("libs/${mod_id}-forge-${mc1_20_1_minecraft_version}-${mod_version}.jar")'))
    }

    @Test
    void forgeJarAndAuditUseTheRealCommonOutputsAndSharedPackagingResources() {
        String buildScript = Files.readString(findForgeBuildScript())

        assertTrue(buildScript.contains("implementation(project(':platforms:mc1_20_1:common'))"))
        assertTrue(buildScript.contains('def commonMainOutput = commonSourceSets.main.output'))
        assertTrue(buildScript.contains('def commonTestOutput = commonSourceSets.test.output'))
        assertTrue(buildScript.contains('from commonMainOutput'))
        assertTrue(buildScript.contains('commonMainClassRoots.from(commonMainOutput.classesDirs)'))
        assertTrue(buildScript.contains('testOutputRoots.from(coreTestOutput, commonTestOutput, forgeTestOutput)'))
        assertTrue(buildScript.contains("def sharedAudioCodecResources = rootProject.layout.projectDirectory.dir('platforms/shared/audio-codecs')"))
        assertTrue(buildScript.contains('sourceSets.main.resources.srcDir(sharedAudioCodecResources)'))
        assertTrue(buildScript.contains("sharedAudioCodecResources.file('META-INF/services/javax.sound.sampled.spi.AudioFileReader')"))
        assertTrue(buildScript.contains("sharedAudioCodecResources.file('META-INF/services/javax.sound.sampled.spi.FormatConversionProvider')"))
        assertFalse(buildScript.contains("project(':core').file('src/main/resources/META-INF/services/"))
        assertTrue(buildScript.contains("namingEvidenceEntry.set('datura/areamusic/server/PlayerAreaTracker.class')"))
    }

    @Test
    void codecPackagingResourcesHaveSharedRawOwnershipAndACoreJarExclusionGate() {
        Path root = findRepositoryRoot()
        Path sharedRoot = root.resolve('platforms/shared/audio-codecs')
        List<String> entries = [
                'META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt',
                'META-INF/licenses/LGPL-2.1.txt',
                'META-INF/services/javax.sound.sampled.spi.AudioFileReader',
                'META-INF/services/javax.sound.sampled.spi.FormatConversionProvider'
        ]
        entries.each { entry ->
            assertTrue(Files.isRegularFile(sharedRoot.resolve(entry)), "shared packaging resource missing: ${entry}")
            assertFalse(Files.exists(root.resolve('core/src/main/resources').resolve(entry)), "core owns packaging resource: ${entry}")
        }

        String coreBuild = Files.readString(root.resolve('core/build.gradle'))
        assertTrue(coreBuild.contains('import datura.gradle.VerifyArchiveEntriesAbsent'))
        assertTrue(coreBuild.contains("tasks.register('verifyPackagingResourcesAbsent', VerifyArchiveEntriesAbsent)"))
        assertTrue(coreBuild.contains("archiveFile.set(tasks.named('jar', Jar).flatMap { it.archiveFile })"))
        assertTrue(coreBuild.contains('forbiddenEntries.set(audioCodecPackagingEntries)'))
        assertTrue(coreBuild.contains('dependsOn verifySourceBoundaries, verifyClassBoundaries, verifyPackagingResourcesAbsent'))
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
        return findRepositoryRoot().resolve('platforms/1.20.1/forge/build.gradle')
    }

    private static Path findRepositoryRoot() {
        Path current = Path.of(System.getProperty('user.dir')).toAbsolutePath()
        while (current != null) {
            if (Files.isRegularFile(current.resolve('settings.gradle'))
                    && Files.isDirectory(current.resolve('buildSrc'))) {
                return current
            }
            current = current.parent
        }
        throw new AssertionError('Could not locate repository root')
    }
}
