package datura.gradle

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class CommonModuleWiringTest {
    @Test
    void commonOwnsTheSharedLifecycleServicesAndTheirTests() {
        Path root = findRepositoryRoot()
        List<String> sharedPaths = [
                'src/main/java/datura/areamusic/client/ClientAreaMusic.java',
                'src/main/java/datura/areamusic/server/AreaMusicServer.java',
                'src/test/java/datura/areamusic/client/ClientAreaMusicTest.java',
                'src/test/java/datura/areamusic/server/AreaMusicServerTest.java'
        ]
        Path common = root.resolve('platforms/1.20.1/common')
        Path forge = root.resolve('platforms/1.20.1/forge')

        sharedPaths.each { relative ->
            assertTrue(Files.isRegularFile(common.resolve(relative)),
                    "common must own ${relative}")
            assertFalse(Files.exists(forge.resolve(relative)),
                    "Forge must not retain ${relative}")
        }
    }

    @Test
    void settingsAndRootLifecycleTasksIncludeTheCommonModule() {
        Path root = findRepositoryRoot()
        String settings = Files.readString(root.resolve('settings.gradle'))
        String rootBuild = Files.readString(root.resolve('build.gradle'))

        assertTrue(settings.contains("include ':platforms:mc1_20_1:common'"))
        assertTrue(settings.contains("project(':platforms:mc1_20_1:common').projectDir = file('platforms/1.20.1/common')"))
        assertTrue(rootBuild.contains("def commonProjectPath = ':platforms:mc1_20_1:common'"))
        assertTrue(rootBuild.contains("dependsOn ':core:test', \"\${commonProjectPath}:test\", \"\${forgeProjectPath}:test\""))
        assertTrue(rootBuild.contains("dependsOn ':core:check', \"\${commonProjectPath}:check\", \"\${forgeProjectPath}:check\""))
        assertTrue(rootBuild.contains("dependsOn ':core:assemble', \"\${commonProjectPath}:assemble\", \"\${forgeProjectPath}:assemble\""))
    }

    @Test
    void commonUsesVanillaMcpAndAFileOnlyCoreDependency() {
        String commonBuild = Files.readString(
                findRepositoryRoot().resolve('platforms/1.20.1/common/build.gradle')
        )

        assertTrue(commonBuild.contains("id 'net.neoforged.moddev.legacyforge' version '2.0.142'"))
        assertTrue(commonBuild.contains('mcpVersion = minecraft_version'))
        assertTrue(commonBuild.contains('addModdingDependenciesTo sourceSets.test'))
        assertTrue(commonBuild.contains("implementation(project(':core'))"))
        assertTrue(commonBuild.contains('transitive = false'))
        assertFalse(commonBuild.contains('version = "${minecraft_version}-${forge_version}"'))
        assertFalse(commonBuild.contains("implementation 'net.minecraftforge"))
        assertFalse(commonBuild.contains('net.neoforged:neoforge'))
        assertFalse(commonBuild.contains("id 'fabric"))
        assertFalse(commonBuild.contains("implementation 'net.fabricmc"))
        assertFalse(commonBuild.contains("id 'architectury"))
        assertFalse(commonBuild.contains("implementation 'dev.architectury"))
    }

    @Test
    void coreAndCommonChecksExecuteTypedSourceBoundaryGates() {
        Path root = findRepositoryRoot()
        String coreBuild = Files.readString(root.resolve('core/build.gradle'))
        String commonBuild = Files.readString(root.resolve('platforms/1.20.1/common/build.gradle'))

        assertTrue(coreBuild.contains('import datura.gradle.VerifySourceBoundaries'))
        assertTrue(coreBuild.contains("tasks.register('verifySourceBoundaries', VerifySourceBoundaries)"))
        assertTrue(coreBuild.contains("boundaryName.set('core')"))
        assertTrue(coreBuild.contains("'net.minecraft'"))
        assertTrue(coreBuild.contains('sourceLanguageVersion.set(java.toolchain.languageVersion.map { it.asInt() })'))
        assertTrue(coreBuild.contains("tasks.named('check')"))
        assertTrue(coreBuild.contains('dependsOn verifySourceBoundaries'))

        assertTrue(commonBuild.contains('import datura.gradle.VerifySourceBoundaries'))
        assertTrue(commonBuild.contains("tasks.register('verifySourceBoundaries', VerifySourceBoundaries)"))
        assertTrue(commonBuild.contains("boundaryName.set('common')"))
        assertFalse(commonBuild.contains("'net.minecraft',"))
        assertTrue(commonBuild.contains('sourceLanguageVersion.set(java.toolchain.languageVersion.map { it.asInt() })'))
        assertTrue(commonBuild.contains("tasks.named('check')"))
        assertTrue(commonBuild.contains('dependsOn verifySourceBoundaries'))
    }

    @Test
    void coreAndCommonChecksExecuteTypedCompiledClassBoundaryGates() {
        Path root = findRepositoryRoot()
        String coreBuild = Files.readString(root.resolve('core/build.gradle'))
        String commonBuild = Files.readString(root.resolve('platforms/1.20.1/common/build.gradle'))

        [coreBuild, commonBuild].each { buildScript ->
            assertTrue(buildScript.contains('import datura.gradle.VerifyClassBoundaries'))
            assertTrue(buildScript.contains("tasks.register('verifyClassBoundaries', VerifyClassBoundaries)"))
            assertTrue(buildScript.contains('classRoots.from(sourceSets.main.output.classesDirs)'))
            assertTrue(buildScript.contains("dependsOn tasks.named('classes')"))
            assertTrue(buildScript.contains('dependsOn verifySourceBoundaries, verifyClassBoundaries'))
        }
        assertTrue(coreBuild.contains("boundaryName.set('core')"))
        assertTrue(coreBuild.contains("'net/minecraft'"))
        assertTrue(commonBuild.contains("boundaryName.set('common')"))
        assertFalse(commonBuild.contains("'net/minecraft',"))
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
