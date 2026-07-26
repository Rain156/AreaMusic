package datura.gradle

import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertTrue

class Minecraft1211CommonModuleWiringTest {
    @TempDir
    Path temporaryDirectory

    private static final List<String> PRODUCTION_SOURCES = [
            'datura/areamusic/client/AreaMusicSoundOptions.java',
            'datura/areamusic/client/ClientAreaMusic.java',
            'datura/areamusic/network/ClientPacketHandler.java',
            'datura/areamusic/network/ClientboundPlaybackState.java',
            'datura/areamusic/network/ClientboundReloadMusic.java',
            'datura/areamusic/server/AreaMusicCommands.java',
            'datura/areamusic/server/AreaMusicServer.java',
            'datura/areamusic/server/NetworkSender.java',
            'datura/areamusic/server/PlayerAreaTracker.java',
            'datura/areamusic/server/ServerLifecycleCoordinator.java'
    ]
    private static final List<String> TEST_SOURCES = [
            'datura/areamusic/client/AreaMusicSoundOptionsTest.java',
            'datura/areamusic/client/ClientAreaMusicTest.java',
            'datura/areamusic/playback/PlaybackStateTest.java',
            'datura/areamusic/server/AreaMusicCommandsTest.java',
            'datura/areamusic/server/AreaMusicServerTest.java',
            'datura/areamusic/server/PlayerAreaTrackerTest.java',
            'datura/areamusic/server/ServerLifecycleCoordinatorTest.java'
    ]

    @Test
    void settingsAndEveryRootLifecycleTaskIncludeMinecraft1211Common() {
        Path root = findRepositoryRoot()
        String settings = Files.readString(root.resolve('settings.gradle'))
        String rootBuild = Files.readString(root.resolve('build.gradle'))

        assertTrue(settings.contains("include ':platforms:mc1_21_1:common'"))
        assertTrue(settings.contains(
                "project(':platforms:mc1_21_1').projectDir = file('platforms/1.21.1')"
        ))
        assertTrue(settings.contains(
                "project(':platforms:mc1_21_1:common').projectDir = file('platforms/1.21.1/common')"
        ))
        assertTrue(rootBuild.contains("def mc1211CommonProjectPath = ':platforms:mc1_21_1:common'"))
        for (String lifecycleTask : ['test', 'check', 'assemble', 'build']) {
            String registration = lifecycleTask == 'test' ? 'register' : 'named'
            assertTrue(rootBuild.contains("tasks.${registration}('${lifecycleTask}')"), lifecycleTask)
            assertTrue(rootBuild.contains("\"\${mc1211CommonProjectPath}:${lifecycleTask}\""), lifecycleTask)
        }
    }

    @Test
    void commonUsesJava21NeoFormAndOnlyCorePlusMinecraftCompileModel() {
        Path root = findRepositoryRoot()
        Path buildFile = root.resolve('platforms/1.21.1/common/build.gradle')
        assertTrue(Files.isRegularFile(buildFile), '1.21.1 common build.gradle must exist')
        String buildScript = Files.readString(buildFile)

        assertTrue(buildScript.contains("id 'java-library'"))
        assertTrue(buildScript.contains("id 'net.neoforged.moddev' version '2.0.142'"))
        assertTrue(buildScript.contains('toolchain.languageVersion = JavaLanguageVersion.of(21)'))
        assertTrue(buildScript.contains('neoForge {'))
        assertTrue(buildScript.contains('neoFormVersion = mc1_21_1_neoform_version'))
        assertTrue(buildScript.contains('addModdingDependenciesTo sourceSets.test'))
        assertTrue(buildScript.contains("implementation(project(':core'))"))
        assertTrue(buildScript.contains('transitive = false'))
        assertTrue(buildScript.contains("testImplementation platform('org.junit:junit-bom:5.10.2')"))
        assertTrue(buildScript.contains("testImplementation 'org.junit.jupiter:junit-jupiter'"))
        assertTrue(buildScript.contains("testRuntimeOnly 'org.junit.platform:junit-platform-launcher'"))
        assertTrue(buildScript.contains("options.encoding = 'UTF-8'"))
        assertTrue(buildScript.contains('useJUnitPlatform()'))
        assertFalse(buildScript.contains("project(':platforms:mc1_20_1"))

        List<String> dependencyOrPluginPrefixes = [
                'implementation ', 'api ', 'compileonly ', 'runtimeonly ',
                'testimplementation ', 'testruntimeonly '
        ]
        assertFalse(buildScript.readLines().any { line ->
            String normalized = line.trim().toLowerCase(Locale.ROOT)
            dependencyOrPluginPrefixes.any { normalized.startsWith(it) }
                    && (normalized.contains('net.neoforged:neoforge')
                    || normalized.contains('net.minecraftforge')
                    || normalized.contains('net.fabricmc')
                    || normalized.contains('architectury'))
        })
        assertFalse(buildScript.contains("id 'net.neoforged.moddev.legacyforge'"))
        assertFalse(buildScript.contains("id 'fabric-loom'"))
        assertFalse(buildScript.contains("id 'architectury'"))
    }

    @Test
    void everyJavaCompileTaskUsesTheJava21ReleaseProvider() {
        Path root = findRepositoryRoot()
        Path initScript = temporaryDirectory.resolve('verify-java-release.gradle')
        Files.writeString(initScript, '''
gradle.projectsEvaluated {
    if (gradle.rootProject.name != 'areamusic') {
        return
    }
    def common = gradle.rootProject.findProject(':platforms:mc1_21_1:common')
    if (common == null) {
        throw new GradleException('1.21.1 common project is missing')
    }
    def compileTasks = common.tasks.withType(org.gradle.api.tasks.compile.JavaCompile).toList()
    if (compileTasks.isEmpty()) {
        throw new GradleException('1.21.1 common has no JavaCompile tasks')
    }
    def invalid = compileTasks.findAll { compileTask ->
        !compileTask.options.release.present || compileTask.options.release.get() != 21
    }.collect { compileTask ->
        "${compileTask.path}=${compileTask.options.release.orNull}"
    }
    if (!invalid.isEmpty()) {
        throw new GradleException("1.21.1 common JavaCompile release mismatch: ${invalid}")
    }
}
'''.stripIndent())

        GradleRunner.create()
                .withProjectDir(root.toFile())
                .withArguments(
                        ':platforms:mc1_21_1:common:help',
                        '--init-script', initScript.toString(),
                        '--configure-on-demand',
                        '--no-configuration-cache',
                        '--stacktrace'
                )
                .build()
    }

    @Test
    void commonOwnsExactlyThePortedProductionAndTestSources() {
        Path common = findRepositoryRoot().resolve('platforms/1.21.1/common')
        Path mainJava = common.resolve('src/main/java')
        Path testJava = common.resolve('src/test/java')

        assertEquals(PRODUCTION_SOURCES, relativeJavaFiles(mainJava))
        assertEquals(TEST_SOURCES, relativeJavaFiles(testJava))
        assertEquals(10, relativeJavaFiles(mainJava).size())
        assertEquals(7, relativeJavaFiles(testJava).size())
    }

    @Test
    void commonResourcesAreStrictPack34CopiesOfTheExistingLanguages() {
        Path root = findRepositoryRoot()
        Path resources = root.resolve('platforms/1.21.1/common/src/main/resources')
        Path packMetadata = resources.resolve('pack.mcmeta')
        assertTrue(Files.isRegularFile(packMetadata), '1.21.1 pack.mcmeta must exist')

        Object parsedPack = new JsonSlurper().parseText(Files.readString(packMetadata))
        assertEquals([pack: [pack_format: 34, description: 'areamusic resources']], parsedPack)
        assertEquals(Integer, parsedPack.pack.pack_format.class)

        for (String locale : ['en_us', 'zh_cn']) {
            Path existing = root.resolve("platforms/1.20.1/common/src/main/resources/assets/areamusic/lang/${locale}.json")
            Path ported = resources.resolve("assets/areamusic/lang/${locale}.json")
            assertTrue(Files.isRegularFile(ported), "${locale}.json must exist".toString())
            Object existingLanguage = new JsonSlurper().parseText(Files.readString(existing))
            Object portedLanguage = new JsonSlurper().parseText(Files.readString(ported))
            assertEquals(existingLanguage, portedLanguage, locale)
        }
    }

    @Test
    void commonCheckGatesLoaderBoundariesJava21ClassesAndExactJarContents() {
        Path buildFile = findRepositoryRoot().resolve('platforms/1.21.1/common/build.gradle')
        assertTrue(Files.isRegularFile(buildFile), '1.21.1 common build.gradle must exist')
        String buildScript = Files.readString(buildFile)

        assertTrue(buildScript.contains('import datura.gradle.VerifyExactArchiveContents'))
        assertFalse(buildScript.contains('import datura.gradle.VerifyArchiveEntriesAbsent'))
        assertTrue(buildScript.contains('import datura.gradle.VerifyClassBoundaries'))
        assertTrue(buildScript.contains('import datura.gradle.VerifySourceBoundaries'))
        assertTrue(buildScript.contains("tasks.register('verifySourceBoundaries', VerifySourceBoundaries)"))
        assertTrue(buildScript.contains("boundaryName.set('1.21.1 common')"))
        assertTrue(buildScript.contains('sourceFiles.from(sourceSets.main.allJava)'))
        assertTrue(buildScript.contains('sourceLanguageVersion.set(java.toolchain.languageVersion.map { it.asInt() })'))
        assertTrue(buildScript.contains("tasks.register('verifyClassBoundaries', VerifyClassBoundaries)"))
        assertTrue(buildScript.contains('productionSourceFiles.from(sourceSets.main.allJava)'))
        assertTrue(buildScript.contains('testSourceFiles.from(sourceSets.test.allJava)'))
        assertTrue(buildScript.contains('productionClassRoots.from(sourceSets.main.output.classesDirs)'))
        assertTrue(buildScript.contains('testClassRoots.from(sourceSets.test.output.classesDirs)'))
        assertTrue(buildScript.contains('separatedMode.set(true)'))
        assertFalse(buildScript.contains(
                'classRoots.from(sourceSets.main.output.classesDirs, sourceSets.test.output.classesDirs)'
        ))
        assertTrue(buildScript.contains('expectedClassMajor.set(65)'))
        assertTrue(buildScript.contains("dependsOn tasks.named('classes'), tasks.named('testClasses')"))

        String sourcePrefixes = capturedList(buildScript, 'forbiddenLoaderImports')
        String classPrefixes = capturedList(buildScript, 'forbiddenLoaderInternalNames')
        for (String prefix : [
                'net.minecraftforge', 'net.neoforged', 'net.fabricmc',
                'org.quiltmc', 'dev.architectury', 'architectury'
        ]) {
            assertTrue(sourcePrefixes.contains("'${prefix}'"), prefix)
            assertTrue(classPrefixes.contains("'${prefix.replace('.', '/')}'"), prefix)
        }
        assertFalse(sourcePrefixes.contains("'net.minecraft'"))
        assertFalse(classPrefixes.contains("'net/minecraft'"))

        assertTrue(buildScript.contains(
                "tasks.register('verifyExactArchiveContents', VerifyExactArchiveContents)"
        ))
        assertTrue(buildScript.contains("archiveFile.set(tasks.named('jar', Jar).flatMap { it.archiveFile })"))
        assertTrue(buildScript.contains('productionClassRoots.from(sourceSets.main.output.classesDirs)'))
        assertTrue(buildScript.contains('productionResourceRoot.set(sourceSets.main.output.resourcesDir)'))
        assertTrue(buildScript.contains('testOutputRoots.from(sourceSets.test.output)'))
        assertTrue(buildScript.contains('expectedProductionResourceEntries.set(expectedCommonResourceEntries)'))
        assertTrue(buildScript.contains("generatedArchiveEntries.set(['META-INF/MANIFEST.MF'])"))
        String expectedResources = capturedList(buildScript, 'expectedCommonResourceEntries')
        for (String expectedResource : [
                'pack.mcmeta',
                'assets/areamusic/lang/en_us.json',
                'assets/areamusic/lang/zh_cn.json'
        ]) {
            assertTrue(expectedResources.contains("'${expectedResource}'"), expectedResource)
        }
        assertFalse(buildScript.contains('forbiddenCommonJarEntries'))
        assertTrue(buildScript.contains(
                'dependsOn verifySourceBoundaries, verifyClassBoundaries, verifyExactArchiveContents'
        ))
    }

    private static List<String> relativeJavaFiles(Path root) {
        if (!Files.isDirectory(root)) {
            return []
        }
        return Files.walk(root).withCloseable { paths ->
            paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith('.java') }
                    .map { root.relativize(it).toString().replace('\\', '/') }
                    .sorted()
                    .toList()
        }
    }

    private static String capturedList(String buildScript, String variableName) {
        def matcher = buildScript =~ /(?s)def ${variableName} = \[(.*?)]/
        assertTrue(matcher.find(), "missing ${variableName} list".toString())
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
