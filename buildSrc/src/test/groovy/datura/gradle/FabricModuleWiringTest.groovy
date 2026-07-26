package datura.gradle

import groovy.json.JsonSlurper
import org.junit.jupiter.api.Test

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class FabricModuleWiringTest {
    @Test
    void settingsAndRootLifecycleTasksIncludeTheFabricModule() {
        Path root = findRepositoryRoot()
        String settings = Files.readString(root.resolve('settings.gradle'))
        String rootBuild = Files.readString(root.resolve('build.gradle'))

        assertTrue(settings.contains("include ':platforms:mc1_20_1:fabric'"))
        assertTrue(settings.contains(
                "project(':platforms:mc1_20_1:fabric').projectDir = file('platforms/1.20.1/fabric')"
        ))
        assertTrue(rootBuild.contains("def fabricProjectPath = ':platforms:mc1_20_1:fabric'"))
        assertTrue(rootBuild.contains('"${fabricProjectPath}:test"'))
        assertTrue(rootBuild.contains('"${fabricProjectPath}:check"'))
        assertTrue(rootBuild.contains('"${fabricProjectPath}:assemble"'))
    }

    @Test
    void fabricPinsTheRequiredToolchainMappingsAndExternalLoaderDependencies() {
        Path root = findRepositoryRoot()
        Path fabricBuildFile = root.resolve('platforms/1.20.1/fabric/build.gradle')

        assertTrue(Files.isRegularFile(fabricBuildFile), 'Fabric build.gradle must exist')

        String settings = Files.readString(root.resolve('settings.gradle'))
        String properties = Files.readString(root.resolve('gradle.properties'))
        String fabricBuild = Files.readString(fabricBuildFile)

        assertTrue(settings.contains("url = 'https://maven.fabricmc.net/'"))
        assertTrue(properties.contains('fabric_loader_version=0.19.3'))
        assertTrue(properties.contains('fabric_api_version=0.92.11+1.20.1'))
        assertTrue(fabricBuild.contains("id 'fabric-loom' version '1.17.17'"))
        assertTrue(fabricBuild.contains('toolchain.languageVersion = JavaLanguageVersion.of(17)'))
        assertTrue(fabricBuild.contains('minecraft "com.mojang:minecraft:${minecraft_version}"'))
        assertTrue(fabricBuild.contains('mappings loom.officialMojangMappings()'))
        assertTrue(fabricBuild.contains('modImplementation "net.fabricmc:fabric-loader:${fabric_loader_version}"'))
        assertTrue(fabricBuild.contains('modImplementation "net.fabricmc.fabric-api:fabric-api:${fabric_api_version}"'))
        assertTrue(fabricBuild.contains("implementation(project(':core'))"))
        assertTrue(fabricBuild.contains("implementation(project(':platforms:mc1_20_1:common'))"))
        assertTrue(fabricBuild.contains('transitive = false'))
        List<String> dependencyOrPluginPrefixes = [
                'id ', 'implementation ', 'api ', 'compileonly ', 'runtimeonly ',
                'modimplementation ', 'modcompileonly ', 'modruntimeonly '
        ]
        assertFalse(fabricBuild.readLines().any { line ->
            String normalized = line.trim().toLowerCase(Locale.ROOT)
            normalized.contains('architectury') && dependencyOrPluginPrefixes.any {
                normalized.startsWith(it)
            }
        })
        assertFalse(fabricBuild.readLines().any { it.trim().startsWith('include ') })
    }

    @Test
    void fabricRemapJarFlattensOnlyProductionOutputsAndSharedAudioCodecs() {
        Path root = findRepositoryRoot()
        String fabricBuild = Files.readString(
                root.resolve('platforms/1.20.1/fabric/build.gradle')
        )

        assertTrue(fabricBuild.contains(
                "def sharedAudioCodecResources = rootProject.layout.projectDirectory.dir('platforms/shared/audio-codecs')"
        ))
        assertTrue(fabricBuild.contains('sourceSets.main.resources.srcDir(sharedAudioCodecResources)'))
        assertTrue(fabricBuild.contains("def coreSourceSets = project(':core').sourceSets"))
        assertTrue(fabricBuild.contains(
                "def commonSourceSets = project(':platforms:mc1_20_1:common').sourceSets"
        ))
        assertTrue(fabricBuild.contains('def coreMainOutput = coreSourceSets.main.output'))
        assertTrue(fabricBuild.contains('def commonMainOutput = commonSourceSets.main.output'))
        assertTrue(fabricBuild.contains("tasks.register('syncEmbeddedCodecs', Sync)"))
        assertTrue(fabricBuild.contains("embeddedCodecs 'com.googlecode.soundlibs:mp3spi:1.9.5.4'"))
        assertTrue(fabricBuild.contains("embeddedCodecs 'com.googlecode.soundlibs:jlayer:1.0.1.4'"))
        assertTrue(fabricBuild.contains("embeddedCodecs 'com.googlecode.soundlibs:vorbisspi:1.0.3.3'"))
        assertTrue(fabricBuild.contains("embeddedCodecs 'com.googlecode.soundlibs:tritonus-share:0.3.7.4'"))
        assertTrue(fabricBuild.contains("embeddedCodecs 'org.jflac:jflac-codec:1.5.2'"))
        assertTrue(fabricBuild.contains("exclude 'META-INF/services/**'"))
        assertTrue(fabricBuild.contains("exclude 'module-info.class'"))
        assertTrue(fabricBuild.contains('sourceSets.main.output.dir([builtBy: syncEmbeddedCodecs], embeddedCodecsDir)'))
        assertTrue(fabricBuild.contains("tasks.named('jar', Jar).configure"))
        assertTrue(fabricBuild.contains('from coreMainOutput'))
        assertTrue(fabricBuild.contains('from commonMainOutput'))
        assertTrue(fabricBuild.contains("def remapJarTask = tasks.named('remapJar'"))
        assertFalse(fabricBuild.readLines().any { it.trim().startsWith('include ') })
    }

    @Test
    void fabricJarDeclaresDeterministicIdentityManifestAttributes() {
        Path root = findRepositoryRoot()
        String fabricBuild = Files.readString(
                root.resolve('platforms/1.20.1/fabric/build.gradle')
        )

        assertTrue(fabricBuild.contains('manifest {'))
        assertTrue(fabricBuild.contains("'Specification-Title'       : mod_id"))
        assertTrue(fabricBuild.contains("'Specification-Vendor'      : mod_authors"))
        assertTrue(fabricBuild.contains("'Specification-Version'     : '1'"))
        assertTrue(fabricBuild.contains("'Implementation-Title'      : rootProject.name"))
        assertTrue(fabricBuild.contains("'Implementation-Version'    : archiveVersion"))
        assertTrue(fabricBuild.contains("'Implementation-Vendor'     : mod_authors"))
        assertFalse(fabricBuild.contains('Implementation-Timestamp'))
    }

    @Test
    void fabricMetadataTemplateSeparatesEntrypointsAndDeclaresExactRuntimeDependencies() {
        Path root = findRepositoryRoot()
        Path metadataFile = root.resolve(
                'platforms/1.20.1/fabric/src/main/resources/fabric.mod.json'
        )

        assertTrue(Files.isRegularFile(metadataFile), 'fabric.mod.json must exist')

        String text = Files.readString(metadataFile)
        Map<String, Object> metadata = (Map<String, Object>) new JsonSlurper().parseText(text)
        assertEquals(1, metadata.schemaVersion)
        assertEquals('${mod_id}', metadata.id)
        assertEquals('${mod_version}', metadata.version)
        assertEquals('${mod_name}', metadata.name)
        assertEquals('${mod_description}', metadata.description)
        assertEquals('${mod_license}', metadata.license)
        assertEquals(['${mod_authors}'], metadata.authors)
        assertEquals('*', metadata.environment)
        assertEquals(
                ['datura.areamusic.fabric.AreaMusicFabric'],
                metadata.entrypoints.main
        )
        assertEquals(
                ['datura.areamusic.fabric.client.FabricClientAreaMusic'],
                metadata.entrypoints.client
        )
        assertEquals(
                [
                        fabricloader: '>=${fabric_loader_version}',
                        minecraft   : '${minecraft_version}',
                        java        : '>=17',
                        'fabric-api': '>=${fabric_api_version}'
                ],
                metadata.depends
        )
        assertFalse(text.toLowerCase(Locale.ROOT).contains('architectury'))

        String fabricBuild = Files.readString(
                root.resolve('platforms/1.20.1/fabric/build.gradle')
        )
        assertTrue(fabricBuild.contains("filesMatching('fabric.mod.json')"))
        assertTrue(fabricBuild.contains('inputs.properties replaceProperties'))
        for (String propertyName : [
                'minecraft_version',
                'fabric_loader_version',
                'fabric_api_version',
                'mod_id',
                'mod_name',
                'mod_license',
                'mod_version',
                'mod_authors',
                'mod_description'
        ]) {
            assertTrue(fabricBuild.contains("${propertyName}: ${propertyName}"))
        }
    }

    @Test
    void fabricCheckAuditsTheTypedFinalRemapJarAndAllProductionCategories() {
        Path root = findRepositoryRoot()
        Path verifierSource = root.resolve(
                'buildSrc/src/main/groovy/datura/gradle/VerifyFabricProductionJar.groovy'
        )
        assertTrue(Files.isRegularFile(verifierSource),
                'typed Fabric production JAR verifier must exist')

        String fabricBuild = Files.readString(
                root.resolve('platforms/1.20.1/fabric/build.gradle')
        )
        assertTrue(fabricBuild.contains('import datura.gradle.VerifyFabricProductionJar'))
        assertTrue(fabricBuild.contains('import net.fabricmc.loom.task.RemapJarTask'))
        assertTrue(fabricBuild.contains(
                "def remapJarTask = tasks.named('remapJar', RemapJarTask)"
        ))
        assertTrue(fabricBuild.contains(
                "tasks.register('verifyProductionJar', VerifyFabricProductionJar)"
        ))
        assertTrue(fabricBuild.contains(
                'productionJar.set(remapJarTask.flatMap { it.archiveFile })'
        ))
        assertTrue(fabricBuild.contains('coreMainClassRoots.from(coreMainOutput.classesDirs)'))
        assertTrue(fabricBuild.contains('commonMainClassRoots.from(commonMainOutput.classesDirs)'))
        assertTrue(fabricBuild.contains('fabricMainClassRoots.from(fabricMainOutput.classesDirs)'))
        assertTrue(fabricBuild.contains(
                'testOutputRoots.from(coreTestOutput, commonTestOutput, fabricTestOutput)'
        ))
        assertTrue(fabricBuild.contains('expectedProjectClassMajor.set(61)'))
        assertTrue(fabricBuild.contains('expectedFabricApiVersion.set(fabric_api_version)'))
        assertTrue(fabricBuild.contains('codecDirectory.set(embeddedCodecsDir)'))
        assertTrue(fabricBuild.contains(
                "sharedAudioCodecResources.file('META-INF/services/javax.sound.sampled.spi.AudioFileReader')"
        ))
        assertTrue(fabricBuild.contains(
                "sharedAudioCodecResources.file('META-INF/services/javax.sound.sampled.spi.FormatConversionProvider')"
        ))
        assertTrue(fabricBuild.contains(
                'expectedFileName.set("${mod_id}-fabric-${minecraft_version}-${mod_version}.jar")'
        ))
        assertTrue(fabricBuild.contains("expectedMainEntrypoint.set('datura.areamusic.fabric.AreaMusicFabric')"))
        assertTrue(fabricBuild.contains(
                "expectedClientEntrypoint.set('datura.areamusic.fabric.client.FabricClientAreaMusic')"
        ))
        assertTrue(fabricBuild.contains(
                "remappingEvidenceEntry.set('datura/areamusic/client/ClientAreaMusic\$MinecraftClientRuntime.class')"
        ))
        assertTrue(fabricBuild.contains("expectedIntermediaryName.set('method_1551')"))
        assertTrue(fabricBuild.contains("forbiddenMojangName.set('getInstance')"))
        assertTrue(fabricBuild.contains("tasks.named('check')"))
        assertTrue(fabricBuild.contains(
                'dependsOn verifySourceBoundaries, verifyClassBoundaries, verifyProductionJar'
        ))
    }

    @Test
    void fabricCheckEnforcesSourceAndCompiledClassBoundariesWithoutForbiddingFabricOrMinecraft() {
        Path root = findRepositoryRoot()
        String fabricBuild = Files.readString(
                root.resolve('platforms/1.20.1/fabric/build.gradle')
        )

        assertTrue(fabricBuild.contains('import datura.gradle.VerifySourceBoundaries'))
        assertTrue(fabricBuild.contains('import datura.gradle.VerifyClassBoundaries'))
        assertTrue(fabricBuild.contains(
                "tasks.register('verifySourceBoundaries', VerifySourceBoundaries)"
        ))
        assertTrue(fabricBuild.contains('sourceFiles.from(sourceSets.main.allJava)'))
        assertTrue(fabricBuild.contains(
                "sourceRoot.set(layout.projectDirectory.dir('src/main/java'))"
        ))
        assertTrue(fabricBuild.contains(
                'sourceLanguageVersion.set(java.toolchain.languageVersion.map { it.asInt() })'
        ))
        assertTrue(fabricBuild.contains(
                "tasks.register('verifyClassBoundaries', VerifyClassBoundaries)"
        ))
        assertTrue(fabricBuild.contains('classRoots.from(sourceSets.main.output.classesDirs)'))
        assertTrue(fabricBuild.contains("boundaryName.set('Fabric')"))
        assertTrue(fabricBuild.contains("dependsOn tasks.named('classes')"))

        String sourcePrefixes = capturedList(fabricBuild, 'forbiddenFabricPlatformImports')
        String classPrefixes = capturedList(fabricBuild, 'forbiddenFabricPlatformInternalNames')
        for (String prefix : ['net.minecraftforge', 'net.neoforged', 'dev.architectury', 'architectury']) {
            assertTrue(sourcePrefixes.contains("'${prefix}'"))
        }
        for (String prefix : ['net/minecraftforge', 'net/neoforged', 'dev/architectury', 'architectury']) {
            assertTrue(classPrefixes.contains("'${prefix}'"))
        }
        for (String allowed : ['net.fabricmc', 'net.minecraft']) {
            assertFalse(sourcePrefixes.contains("'${allowed}'"))
            assertFalse(classPrefixes.contains("'${allowed.replace('.', '/')}'"))
        }
        assertTrue(fabricBuild.contains(
                'dependsOn verifySourceBoundaries, verifyClassBoundaries, verifyProductionJar'
        ))
    }

    private static String capturedList(String buildScript, String variableName) {
        def matcher = buildScript =~ /(?s)def ${variableName} = \[(.*?)]/
        assertTrue(matcher.find(), "missing ${variableName} list")
        return matcher.group(1)
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
