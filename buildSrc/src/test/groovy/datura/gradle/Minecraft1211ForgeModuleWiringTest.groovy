package datura.gradle

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class Minecraft1211ForgeModuleWiringTest {
    @Test
    void settingsGiveTheMinecraft1211ForgeModuleAUniqueServiceIdentity() {
        Path root = findRepositoryRoot()
        String settings = Files.readString(root.resolve('settings.gradle'))

        assertTrue(settings.contains("include ':platforms:mc1_21_1:forge_1_21_1'"))
        assertTrue(settings.contains(
                "project(':platforms:mc1_21_1:forge_1_21_1').projectDir = file('platforms/1.21.1/forge')"
        ))
        assertFalse(settings.contains("include ':platforms:mc1_21_1:forge'"))
    }

    @Test
    void everyRootLifecycleTaskIncludesMinecraft1211Forge() {
        String rootBuild = Files.readString(findRepositoryRoot().resolve('build.gradle'))

        assertTrue(rootBuild.contains(
                "def mc1211ForgeProjectPath = ':platforms:mc1_21_1:forge_1_21_1'"
        ))
        for (String lifecycleTask : ['test', 'check', 'assemble', 'build']) {
            String registration = lifecycleTask == 'test' ? 'register' : 'named'
            assertTrue(rootBuild.contains("tasks.${registration}('${lifecycleTask}')"), lifecycleTask)
            assertTrue(rootBuild.contains(
                    "\"\${mc1211ForgeProjectPath}:${lifecycleTask}\""
            ), lifecycleTask)
        }
    }

    @Test
    void forgeBuildPinsForgeGradleMinecraft1211AndJava21() {
        Path buildFile = findRepositoryRoot().resolve('platforms/1.21.1/forge/build.gradle')

        assertTrue(Files.isRegularFile(buildFile), '1.21.1 Forge build.gradle must exist')
        String buildScript = Files.readString(buildFile)
        assertTrue(buildScript.contains(
                "id 'net.minecraftforge.gradle' version '7.0.3'"
        ))
        assertTrue(buildScript.contains(
                'mappings channel: mc1_21_1_mapping_channel,'
        ))
        assertTrue(buildScript.contains(
                'version: mc1_21_1_mapping_version'
        ))
        assertTrue(buildScript.contains('toolchain.languageVersion = JavaLanguageVersion.of(21)'))
        assertTrue(buildScript.contains('options.release = 21'))
        assertTrue(buildScript.contains(
                'archivesName = "${mod_id}-forge-${mc1_21_1_minecraft_version}"'
        ))
        assertFalse(buildScript.contains("project(':platforms:mc1_20_1"))
    }

    @Test
    void forgeBuildUsesOnlyCoreAndMinecraft1211CommonProductionOutputs() {
        String buildScript = Files.readString(
                findRepositoryRoot().resolve('platforms/1.21.1/forge/build.gradle')
        )

        assertTrue(buildScript.contains("implementation(project(':core'))"))
        assertTrue(buildScript.contains(
                "implementation(project(':platforms:mc1_21_1:common'))"
        ))
        assertTrue(buildScript.contains('transitive = false'))
        assertTrue(buildScript.contains("testImplementation 'org.ow2.asm:asm:9.7.1'"))
        assertTrue(buildScript.contains("tasks.named('test', Test)"))
        assertTrue(buildScript.contains('useJUnitPlatform()'))
        assertTrue(buildScript.contains('minecraft.mavenizer(it)'))
        assertTrue(buildScript.contains('maven fg.forgeMaven'))
        assertTrue(buildScript.contains('maven fg.minecraftLibsMaven'))
        assertTrue(buildScript.contains(
                'implementation minecraft.dependency('
        ))
        assertTrue(buildScript.contains(
                '"net.minecraftforge:forge:${mc1_21_1_minecraft_version}-' +
                        '${mc1_21_1_forge_version}"'
        ))
        for (String forbidden : [
                'architectury',
                'net.neoforged.moddev',
                'legacyforge',
                'forgeuniversalsrg',
                'addartifactstomanifest',
                'universal-srg',
                "project(':platforms:mc1_21_1:fabric_1_21_1')",
                "project(':platforms:mc1_21_1:neoforge')"
        ]) {
            assertFalse(buildScript.toLowerCase(Locale.ROOT).contains(forbidden), forbidden)
        }
    }

    @Test
    void forgeBuildProducesAndAuditsAFlatJava21ProductionJar() {
        String buildScript = Files.readString(
                findRepositoryRoot().resolve('platforms/1.21.1/forge/build.gradle')
        )

        assertTrue(buildScript.contains('import datura.gradle.VerifyProductionJar'))
        assertTrue(buildScript.contains("embeddedCodecs 'com.googlecode.soundlibs:mp3spi:1.9.5.4'"))
        assertTrue(buildScript.contains("embeddedCodecs 'org.jflac:jflac-codec:1.5.2'"))
        assertTrue(buildScript.contains("def compileCoreMainJava21 = tasks.register('compileCoreMainJava21', JavaCompile)"))
        assertTrue(buildScript.contains('options.release = 21'))
        assertTrue(buildScript.contains('sourceSets.main.output.dir([builtBy: syncEmbeddedCodecs]'))
        assertTrue(buildScript.contains("tasks.named('jar', Jar).configure"))
        assertTrue(buildScript.contains('from coreMainOutput'))
        assertTrue(buildScript.contains('from commonMainOutput'))
        assertTrue(buildScript.contains("tasks.register('verifyProductionJar', VerifyProductionJar)"))
        assertTrue(buildScript.contains('productionJar.set(productionJarTask.flatMap { it.archiveFile })'))
        assertTrue(buildScript.contains('expectedPackFormat.set(34)'))
        assertTrue(buildScript.contains('expectedProjectClassMajor.set(65)'))
        assertTrue(buildScript.contains('verifyReobfuscation.set(false)'))
        assertTrue(buildScript.contains('dependsOn verifyBundledAudioCodecs, verifyProductionJar'))
        assertFalse(buildScript.contains('reobfJar'))
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
