package datura.gradle

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class Minecraft1211FabricModuleWiringTest {
    @Test
    void settingsGiveTheMinecraft1211FabricModuleAUniqueLoomServiceIdentity() {
        Path root = findRepositoryRoot()
        String settings = Files.readString(root.resolve('settings.gradle'))

        assertTrue(settings.contains("include ':platforms:mc1_21_1:fabric_1_21_1'"))
        assertTrue(settings.contains(
                "project(':platforms:mc1_21_1:fabric_1_21_1').projectDir = file('platforms/1.21.1/fabric')"
        ))
        assertFalse(settings.contains("include ':platforms:mc1_21_1:fabric'"))
    }

    @Test
    void everyRootLifecycleTaskIncludesMinecraft1211FabricWithoutWeakeningExistingModules() {
        Path root = findRepositoryRoot()
        String rootBuild = Files.readString(root.resolve('build.gradle'))

        assertTrue(rootBuild.contains(
                "def mc1211FabricProjectPath = ':platforms:mc1_21_1:fabric_1_21_1'"
        ))
        for (String lifecycleTask : ['test', 'check', 'assemble', 'build']) {
            String registration = lifecycleTask == 'test' ? 'register' : 'named'
            assertTrue(rootBuild.contains("tasks.${registration}('${lifecycleTask}')"), lifecycleTask)
            assertTrue(rootBuild.contains(
                    "\"\${mc1211FabricProjectPath}:${lifecycleTask}\""
            ), lifecycleTask)
        }

        for (String existingPath : [
                ':core',
                ':platforms:mc1_20_1:common',
                ':platforms:mc1_20_1:fabric',
                ':platforms:mc1_20_1:forge',
                ':platforms:mc1_21_1:common'
        ]) {
            assertTrue(rootBuild.contains(existingPath), existingPath)
        }
    }

    @Test
    void fabricBuildPinsMinecraft1211LoomOfficialMappingsFabricApiAndJava21() {
        Path root = findRepositoryRoot()
        Path buildFile = root.resolve('platforms/1.21.1/fabric/build.gradle')

        assertTrue(Files.isRegularFile(buildFile), '1.21.1 Fabric build.gradle must exist')
        String settings = Files.readString(root.resolve('settings.gradle'))
        String properties = Files.readString(root.resolve('gradle.properties'))
        String buildScript = Files.readString(buildFile)

        assertTrue(settings.contains("url = 'https://maven.fabricmc.net/'"))
        assertTrue(properties.contains('mc1_21_1_minecraft_version=1.21.1'))
        assertTrue(properties.contains('mc1_21_1_loom_version=1.17.17'))
        assertTrue(properties.contains('mc1_21_1_fabric_loader_version=0.19.3'))
        assertTrue(properties.contains('mc1_21_1_fabric_api_version=0.116.14+1.21.1'))
        assertTrue(buildScript.contains(
                'id \'fabric-loom\' version "${mc1_21_1_loom_version}"'
        ))
        assertTrue(buildScript.contains('toolchain.languageVersion = JavaLanguageVersion.of(21)'))
        assertTrue(buildScript.contains('options.release = 21'))
        assertTrue(buildScript.contains(
                'minecraft "com.mojang:minecraft:${mc1_21_1_minecraft_version}"'
        ))
        assertTrue(buildScript.contains('mappings loom.officialMojangMappings()'))
        assertTrue(buildScript.contains(
                'modImplementation "net.fabricmc:fabric-loader:${mc1_21_1_fabric_loader_version}"'
        ))
        assertTrue(buildScript.contains(
                'modImplementation "net.fabricmc.fabric-api:fabric-api:${mc1_21_1_fabric_api_version}"'
        ))
        assertTrue(buildScript.contains("implementation(project(':core'))"))
        assertTrue(buildScript.contains(
                "implementation(project(':platforms:mc1_21_1:common'))"
        ))
        assertTrue(buildScript.contains('transitive = false'))
        assertTrue(buildScript.contains(
                'archivesName = "${mod_id}-fabric-${mc1_21_1_minecraft_version}"'
        ))
        assertFalse(buildScript.contains("project(':platforms:mc1_20_1"))
        assertNoArchitecturyOrNestedJarConfiguration(buildScript)
    }

    @Test
    void fabricMetadataTemplateHasExactEntrypointsAndRuntimeDependencies() {
        Path root = findRepositoryRoot()
        Path metadataFile = root.resolve(
                'platforms/1.21.1/fabric/src/main/resources/fabric.mod.json'
        )

        assertTrue(Files.isRegularFile(metadataFile), '1.21.1 fabric.mod.json must exist')
        String text = Files.readString(metadataFile)
        Map<String, Object> metadata = (Map<String, Object>) new JsonSlurper().parseText(text)

        assertEquals(1, metadata.schemaVersion)
        assertEquals('${mod_id}', metadata.id)
        assertEquals('${mod_version}', metadata.version)
        assertEquals('${mod_name}', metadata.name)
        assertEquals('${mod_description}', metadata.description)
        assertEquals(['${mod_authors}'], metadata.authors)
        assertEquals('${mod_license}', metadata.license)
        assertEquals('*', metadata.environment)
        assertEquals(
                ['datura.areamusic.fabric.AreaMusicFabric'],
                metadata.entrypoints.main
        )
        assertEquals(
                ['datura.areamusic.fabric.client.FabricClientAreaMusic'],
                metadata.entrypoints.client
        )
        assertEquals([
                fabricloader: '>=${mc1_21_1_fabric_loader_version}',
                minecraft   : '${mc1_21_1_minecraft_version}',
                java        : '>=21',
                'fabric-api': '>=${mc1_21_1_fabric_api_version}'
        ], metadata.depends)
        assertFalse(text.toLowerCase(Locale.ROOT).contains('architectury'))
    }

    @Test
    void fabricBuildFlattensOnlyProductionOutputsAndLockedSharedAudioCodecs() {
        Path root = findRepositoryRoot()
        Path buildFile = root.resolve('platforms/1.21.1/fabric/build.gradle')

        assertTrue(Files.isRegularFile(buildFile), '1.21.1 Fabric build.gradle must exist')
        String buildScript = Files.readString(buildFile)

        assertTrue(buildScript.contains(
                "def sharedAudioCodecResources = rootProject.layout.projectDirectory.dir('platforms/shared/audio-codecs')"
        ))
        assertTrue(buildScript.contains("evaluationDependsOn(':core')"))
        assertTrue(buildScript.contains(
                "evaluationDependsOn(':platforms:mc1_21_1:common')"
        ))
        assertTrue(buildScript.contains('sourceSets.main.resources.srcDir(sharedAudioCodecResources)'))
        assertTrue(buildScript.contains("def coreSourceSets = project(':core').sourceSets"))
        assertTrue(buildScript.contains(
                "tasks.register('compileCoreMainJava21', JavaCompile)"
        ))
        assertTrue(buildScript.contains('source(coreSourceSets.main.allJava)'))
        assertTrue(buildScript.contains(
                'languageVersion = JavaLanguageVersion.of(21)'
        ))
        assertTrue(buildScript.contains(
                'def coreMainOutput = files(coreMainJava21ClassesDir).builtBy(compileCoreMainJava21)'
        ))
        assertTrue(buildScript.contains(
                "def commonSourceSets = project(':platforms:mc1_21_1:common').sourceSets"
        ))
        assertTrue(buildScript.contains('from coreMainOutput'))
        assertTrue(buildScript.contains('from commonMainOutput'))
        assertTrue(buildScript.contains("tasks.register('syncEmbeddedCodecs', Sync)"))
        for (String dependency : [
                "embeddedCodecs 'com.googlecode.soundlibs:mp3spi:1.9.5.4'",
                "embeddedCodecs 'com.googlecode.soundlibs:jlayer:1.0.1.4'",
                "embeddedCodecs 'com.googlecode.soundlibs:vorbisspi:1.0.3.3'",
                "embeddedCodecs 'com.googlecode.soundlibs:tritonus-share:0.3.7.4'",
                "embeddedCodecs 'org.jflac:jflac-codec:1.5.2'"
        ]) {
            assertTrue(buildScript.contains(dependency), dependency)
        }
        assertTrue(buildScript.contains("exclude 'META-INF/services/**'"))
        assertTrue(buildScript.contains("exclude 'module-info.class'"))
        assertTrue(buildScript.contains("def remapJarTask = tasks.named('remapJar'"))
        assertNoArchitecturyOrNestedJarConfiguration(buildScript)
    }

    @Test
    void fabricCheckUsesTypedAuditsForJava21ProductionOutputsAndFinalRemapJar() {
        Path root = findRepositoryRoot()
        Path buildFile = root.resolve('platforms/1.21.1/fabric/build.gradle')

        assertTrue(Files.isRegularFile(buildFile), '1.21.1 Fabric build.gradle must exist')
        String buildScript = Files.readString(buildFile)

        assertTrue(buildScript.contains('import datura.gradle.VerifyFabricProductionJar'))
        assertTrue(buildScript.contains('import datura.gradle.VerifySourceBoundaries'))
        assertTrue(buildScript.contains('import datura.gradle.VerifyClassBoundaries'))
        assertTrue(buildScript.contains('import net.fabricmc.loom.task.RemapJarTask'))
        assertTrue(buildScript.contains(
                "tasks.register('verifyProductionJar', VerifyFabricProductionJar)"
        ))
        assertTrue(buildScript.contains(
                'productionJar.set(remapJarTask.flatMap { it.archiveFile })'
        ))
        assertTrue(buildScript.contains('coreMainClassRoots.from(coreMainOutput)'))
        assertTrue(buildScript.contains('commonMainClassRoots.from(commonMainOutput.classesDirs)'))
        assertTrue(buildScript.contains('fabricMainClassRoots.from(fabricMainOutput.classesDirs)'))
        assertTrue(buildScript.contains(
                'testOutputRoots.from(coreTestOutput, commonTestOutput, fabricTestOutput)'
        ))
        assertTrue(buildScript.contains('coreTestOutputRoots.from(coreTestOutput)'))
        assertTrue(buildScript.contains('commonTestOutputRoots.from(commonTestOutput)'))
        assertTrue(buildScript.contains('fabricTestOutputRoots.from(fabricTestOutput)'))
        assertTrue(buildScript.contains('expectedProjectClassMajor.set(65)'))
        assertTrue(buildScript.contains('expectedJavaVersion.set(21)'))
        assertTrue(buildScript.contains('expectedPackFormat.set(34)'))
        assertTrue(buildScript.contains(
                'expectedFileName.set("${mod_id}-fabric-${mc1_21_1_minecraft_version}-${mod_version}.jar")'
        ))
        assertTrue(buildScript.contains(
                "remappingEvidenceEntry.set('datura/areamusic/client/ClientAreaMusic\$MinecraftClientRuntime.class')"
        ))
        assertTrue(buildScript.contains("expectedIntermediaryName.set('method_1551')"))
        assertTrue(buildScript.contains("forbiddenMojangName.set('getInstance')"))
        assertTrue(buildScript.contains("tasks.named('check')"))
        assertTrue(buildScript.contains(
                'dependsOn verifySourceBoundaries, verifyClassBoundaries, verifyProductionJar'
        ))
    }

    @Test
    void dedicatedServerGraphTestReceivesOnlyExactCacheableProductionClassRoots() {
        Path root = findRepositoryRoot()
        String buildScript = Files.readString(
                root.resolve('platforms/1.21.1/fabric/build.gradle')
        )
        String testTask = buildScript.substring(
                buildScript.indexOf("tasks.named('test', Test).configure {")
        )

        assertTrue(buildScript.contains(
                'import datura.gradle.ProductionRootsJvmArgumentProvider'
        ))
        assertTrue(buildScript.contains('import org.gradle.api.tasks.PathSensitivity'))
        assertTrue(buildScript.contains(
                "def dedicatedServerProductionRoots = files(\n" +
                        '        coreMainOutput,\n' +
                        '        commonMainOutput.classesDirs,\n' +
                        '        fabricMainOutput.classesDirs\n' +
                        ')'
        ))
        assertTrue(testTask.contains('inputs.files(dedicatedServerProductionRoots)'))
        assertTrue(testTask.contains(
                ".withPropertyName('dedicatedServerProductionClassRoots')"
        ))
        assertTrue(testTask.contains(
                '.withPathSensitivity(PathSensitivity.RELATIVE)'
        ))
        assertTrue(testTask.contains('dependsOn compileCoreMainJava21'))
        assertTrue(testTask.contains(
                "dependsOn ':platforms:mc1_21_1:common:classes'"
        ))
        assertTrue(testTask.contains("dependsOn tasks.named('classes')"))
        assertTrue(testTask.contains(
                'def productionRootsArgumentProvider = objects.newInstance('
        ))
        assertTrue(testTask.contains('ProductionRootsJvmArgumentProvider'))
        assertTrue(testTask.contains(
                "productionRootsArgumentProvider.propertyName.set(\n" +
                        "            'areamusic.fabric.dedicatedServerProductionRoots'\n" +
                        '    )'
        ))
        assertTrue(testTask.contains(
                'productionRootsArgumentProvider.productionRoots.from(\n' +
                        '            dedicatedServerProductionRoots\n' +
                        '    )'
        ))
        assertTrue(testTask.contains(
                'jvmArgumentProviders.add(productionRootsArgumentProvider)'
        ))
        for (String forbidden : [
                'coreTestOutput',
                'commonTestOutput',
                'fabricTestOutput',
                'sourceSets.test',
                'testRuntimeClasspath'
        ]) {
            assertFalse(testTask.contains(forbidden), forbidden)
        }
    }

    private static void assertNoArchitecturyOrNestedJarConfiguration(String buildScript) {
        List<String> dependencyOrPluginPrefixes = [
                'id ', 'implementation ', 'api ', 'compileonly ', 'runtimeonly ',
                'modimplementation ', 'modcompileonly ', 'modruntimeonly '
        ]
        assertFalse(buildScript.readLines().any { line ->
            String normalized = line.trim().toLowerCase(Locale.ROOT)
            normalized.contains('architectury') && dependencyOrPluginPrefixes.any {
                normalized.startsWith(it)
            }
        })
        assertFalse(buildScript.readLines().any { it.trim().startsWith('include ') })
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
