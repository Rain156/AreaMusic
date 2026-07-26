package datura.gradle

import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class Minecraft1211NeoForgeModuleWiringTest {
    private static final String PROJECT_PATH = ':platforms:mc1_21_1:neoforge_1_21_1'
    private static final String LEAF_DIRECTORY = 'platforms/1.21.1/neoforge'

    @Test
    void settingsExposeExactlyOneNativeNeoForgeLeaf() {
        String settings = Files.readString(findRepositoryRoot().resolve('settings.gradle'))

        assertTrue(settings.contains("include '${PROJECT_PATH}'"))
        assertTrue(settings.contains(
                "project('${PROJECT_PATH}').projectDir = file('${LEAF_DIRECTORY}')"
        ))
        assertFalse(settings.contains("include ':platforms:mc1_21_1:neoforge'"))
        assertFalse(settings.contains("include ':platforms:mc1_21_1:neoforge_1_21'"))
    }

    @Test
    void rootLifecycleMatrixIncludesTheNativeNeoForgeLeaf() {
        String rootBuild = Files.readString(findRepositoryRoot().resolve('build.gradle'))

        assertTrue(rootBuild.contains("def mc1211NeoForgeProjectPath = '${PROJECT_PATH}'"))
        for (String lifecycleTask : ['test', 'check', 'assemble', 'build']) {
            assertTrue(rootBuild.contains("\"\${mc1211NeoForgeProjectPath}:${lifecycleTask}\""))
        }
    }

    @Test
    void nativeNeoForgeVersionsAreCentralizedAndPinned() {
        String properties = Files.readString(findRepositoryRoot().resolve('gradle.properties'))
        String settings = Files.readString(findRepositoryRoot().resolve('settings.gradle'))

        assertTrue(properties.contains('mc1_21_1_neoforge_version=21.1.244'))
        assertTrue(properties.contains('mc1_21_1_moddevgradle_version=2.0.142'))
        assertTrue(settings.contains("url = 'https://maven.neoforged.net/releases/'"))
    }

    @Test
    void nativeNeoForgeLeafUsesModDevGradleJava21AndOnlyCurrentProductionModules() {
        String buildScript = Files.readString(
                findRepositoryRoot().resolve(LEAF_DIRECTORY).resolve('build.gradle')
        )

        assertTrue(buildScript.contains(
                "id 'net.neoforged.moddev' version \"\${mc1_21_1_moddevgradle_version}\""
        ))
        assertTrue(buildScript.contains('neoForge {'))
        assertTrue(buildScript.contains('version = mc1_21_1_neoforge_version'))
        assertTrue(buildScript.contains('toolchain.languageVersion = JavaLanguageVersion.of(21)'))
        assertTrue(buildScript.contains('options.release = 21'))
        assertTrue(buildScript.contains("implementation(project(':core'))"))
        assertTrue(buildScript.contains(
                "implementation(project(':platforms:mc1_21_1:common'))"
        ))
        assertTrue(buildScript.contains('transitive = false'))
        assertTrue(buildScript.contains(
                'archivesName = "${mod_id}-neoforge-${mc1_21_1_minecraft_version}"'
        ))
        assertFalse(buildScript.toLowerCase(Locale.ROOT).contains('architectury'))
        assertFalse(buildScript.contains("project(':platforms:mc1_20_1"))
    }

    @Test
    void modDevTestedModExplicitlyOwnsCoreCommonAndNeoForgeSourceSets() {
        String buildScript = Files.readString(
                findRepositoryRoot().resolve(LEAF_DIRECTORY).resolve('build.gradle')
        )

        assertTrue(buildScript.contains('sourceSet coreSourceSets.main'))
        assertTrue(buildScript.contains('sourceSet commonSourceSets.main'))
        assertTrue(buildScript.contains('sourceSet sourceSets.main'))
    }

    @Test
    void nativeNeoForgeLeafHasFlattenedJarAuditsAndNativeMetadata() {
        Path root = findRepositoryRoot()
        String buildScript = Files.readString(root.resolve(LEAF_DIRECTORY).resolve('build.gradle'))
        Path metadataFile = root.resolve(LEAF_DIRECTORY)
                .resolve('src/main/resources/META-INF/neoforge.mods.toml')

        assertTrue(Files.isRegularFile(metadataFile))
        String metadata = Files.readString(metadataFile)
        assertTrue(metadata.contains('modLoader = "javafml"'))
        assertTrue(metadata.contains('modId = "${mod_id}"'))
        assertTrue(metadata.contains('modId = "neoforge"'))
        assertTrue(metadata.contains('modId = "minecraft"'))
        assertFalse(metadata.contains('modId = "forge"'))
        assertFalse(metadata.toLowerCase(Locale.ROOT).contains('fabric'))
        assertFalse(metadata.toLowerCase(Locale.ROOT).contains('architectury'))

        assertTrue(buildScript.contains("tasks.register('syncEmbeddedCodecs', Sync)"))
        assertTrue(buildScript.contains("tasks.register('verifyProductionJar', VerifyProductionJar)"))
        assertTrue(buildScript.contains('expectedProjectClassMajor.set(65)'))
        assertTrue(buildScript.contains('from coreMainOutput'))
        assertTrue(buildScript.contains('from commonMainOutput'))
        assertTrue(buildScript.contains('neoforgeMainClassRoots.from(neoforgeMainOutput.classesDirs)'))
        assertTrue(buildScript.contains('expectedFileName.set("${mod_id}-neoforge-${mc1_21_1_minecraft_version}-${mod_version}.jar")'))
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
