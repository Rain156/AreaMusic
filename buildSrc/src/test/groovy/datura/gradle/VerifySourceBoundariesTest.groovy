package datura.gradle

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

import java.nio.file.Files
import java.nio.file.Path
import javax.lang.model.SourceVersion

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue
import static org.junit.jupiter.api.Assumptions.assumeTrue

class VerifySourceBoundariesTest {
    private static final List<String> LOADER_PREFIXES = [
            'net.minecraftforge',
            'net.neoforged',
            'net.fabricmc',
            'org.quiltmc',
            'dev.architectury',
            'architectury'
    ].asImmutable()

    @TempDir
    Path temporaryDirectory

    @Test
    void commonAllowsVanillaMinecraftImports() {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('example/Allowed.java', '''
                package example;
                import net.minecraft.network.FriendlyByteBuf;
                final class Allowed { FriendlyByteBuf buffer; }
                ''')

        task.verifyImports()
    }

    @ParameterizedTest
    @ValueSource(strings = [
            'net.minecraftforge.network.NetworkEvent',
            'net.neoforged.fml.common.Mod',
            'net.fabricmc.api.ModInitializer',
            'org.quiltmc.loader.api.ModContainer',
            'dev.architectury.event.Event',
            'architectury.common.CommonPlugin'
    ])
    void commonRejectsEveryLoaderAndArchitecturyImport(String forbiddenImport) {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('example/Forbidden.java', """
                package example;
                import ${forbiddenImport};
                final class Forbidden {}
                """)

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains("common source boundary violation"))
        assertTrue(failure.message.contains("example/Forbidden.java:3 imports ${forbiddenImport}"))
    }

    @ParameterizedTest
    @ValueSource(strings = [
            'net.minecraft.core.BlockPos',
            'net.minecraftforge.network.NetworkEvent',
            'net.neoforged.fml.common.Mod',
            'net.fabricmc.api.ModInitializer',
            'org.quiltmc.loader.api.ModContainer',
            'dev.architectury.event.Event',
            'architectury.common.CommonPlugin'
    ])
    void coreRejectsMinecraftEveryLoaderAndArchitecturyImport(String forbiddenImport) {
        VerifySourceBoundaries task = newTask('core', ['net.minecraft'] + LOADER_PREFIXES)
        writeSource('example/Forbidden.java', """
                package example;
                import ${forbiddenImport};
                final class Forbidden {}
                """)

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains("core source boundary violation"))
        assertTrue(failure.message.contains("example/Forbidden.java:3 imports ${forbiddenImport}"))
    }

    @Test
    void reportsAllViolationsInStablePathAndLineOrder() {
        VerifySourceBoundaries task = newTask('core', ['net.minecraft'] + LOADER_PREFIXES)
        writeSource('z/Second.java', '''
                package z;
                import net.minecraft.core.BlockPos;
                final class Second { BlockPos value; }
                ''')
        writeSource('a/First.java', '''
                package a;
                import static net.minecraftforge.fml.loading.FMLPaths.GAMEDIR;
                import net.fabricmc.api.ModInitializer;
                final class First implements ModInitializer { public void onInitialize() {} }
                ''')

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        int first = failure.message.indexOf('a/First.java:3 imports net.minecraftforge.fml.loading.FMLPaths.GAMEDIR')
        int second = failure.message.indexOf('a/First.java:4 imports net.fabricmc.api.ModInitializer')
        int third = failure.message.indexOf('z/Second.java:3 imports net.minecraft.core.BlockPos')
        assertTrue(first >= 0 && first < second && second < third)
    }

    @Test
    void rejectsAForbiddenFullyQualifiedTypeReferenceWithoutAnImport() {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('example/Forbidden.java', '''
                package example;
                final class Forbidden {
                    net.minecraftforge.network.NetworkEvent.Context context;
                }
                ''')

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains('example/Forbidden.java:4 references net.minecraftforge.network.NetworkEvent.Context'))
    }

    @Test
    void rejectsAForbiddenImportSplitByCommentsAndNewlines() {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('example/Forbidden.java', '''
                package example;
                import net./* split to evade a line matcher */
                        minecraftforge.network.NetworkEvent;
                final class Forbidden {}
                ''')

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains('imports net.minecraftforge.network.NetworkEvent'))
    }

    @Test
    void rejectsAForbiddenPackageDeclaration() {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('net/minecraftforge/example/Forbidden.java', '''
                package net.minecraftforge.example;
                final class Forbidden {}
                ''')

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains('declares package net.minecraftforge.example'))
    }

    @Test
    void acceptsPseudoImportsInsideBlockCommentsAndTextBlocks() {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('example/Allowed.java', '''
                package example;
                /*
                import net.minecraftforge.network.NetworkEvent;
                */
                final class Allowed {
                    String documentation = """
                            import net.fabricmc.api.ModInitializer;
                            """;
                }
                ''')

        task.verifyImports()
    }

    @Test
    void failsClosedWhenJavaSourceCannotBeParsed() {
        VerifySourceBoundaries task = newTask('common', LOADER_PREFIXES)
        writeSource('example/Broken.java', '''
                package example;
                final class Broken {
                ''')

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains('could not be parsed'))
        assertTrue(failure.message.contains('example/Broken.java'))
    }

    @Test
    void parsesStableJava21SyntaxWhenTheModuleToolchainRequestsJava21() {
        int latestSupported = Integer.parseInt(
                SourceVersion.latestSupported().name().substring('RELEASE_'.length())
        )
        assumeTrue(latestSupported >= 21, 'Java 21 syntax requires a Java 21+ daemon compiler')
        VerifySourceBoundaries task = newTask('future-common', LOADER_PREFIXES)
        assertNotNull(task.hasProperty('sourceLanguageVersion'), 'typed language-version input is missing')
        writeSource('example/Allowed.java', '''
                package example;
                final class Allowed {
                    static int length(Object value) {
                        return switch (value) {
                            case String text -> text.length();
                            default -> 0;
                        };
                    }
                }
                ''')

        task.sourceLanguageVersion.set(21)

        task.verifyImports()
    }

    @Test
    void sourceLanguageVersionIsATypedGradleCacheKeyInput() {
        def getter = VerifySourceBoundaries.getMethod('getSourceLanguageVersion')

        assertEquals(Property, getter.returnType)
        assertNotNull(getter.getAnnotation(Input))
    }

    @Test
    void failsClearlyWhenRequestedLanguageExceedsTheDaemonCompiler() {
        VerifySourceBoundaries task = newTask('future-common', LOADER_PREFIXES)
        assertNotNull(task.hasProperty('sourceLanguageVersion'), 'typed language-version input is missing')
        int latestSupported = Integer.parseInt(
                SourceVersion.latestSupported().name().substring('RELEASE_'.length())
        )
        task.sourceLanguageVersion.set(latestSupported + 1)
        writeSource('example/Allowed.java', 'package example; final class Allowed {}')

        GradleException failure = assertThrows(GradleException, task::verifyImports)

        assertTrue(failure.message.contains("supports at most Java ${latestSupported}"))
        assertTrue(failure.message.contains("requested Java ${latestSupported + 1}"))
    }

    private VerifySourceBoundaries newTask(String boundaryName, List<String> forbiddenPrefixes) {
        Path projectDirectory = temporaryDirectory.resolve(UUID.randomUUID().toString())
        Files.createDirectories(projectDirectory)
        Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
        VerifySourceBoundaries task = project.tasks.create(
                "verify${boundaryName.capitalize()}SourceBoundaries",
                VerifySourceBoundaries
        )
        task.sourceFiles.from(project.fileTree(temporaryDirectory.toFile()) {
            include '**/*.java'
        })
        task.sourceRoot.set(temporaryDirectory.toFile())
        task.boundaryName.set(boundaryName)
        task.forbiddenImportPrefixes.set(forbiddenPrefixes)
        task.sourceLanguageVersion.set(17)
        return task
    }

    private void writeSource(String relativePath, String source) {
        Path target = temporaryDirectory.resolve(relativePath)
        Files.createDirectories(target.parent)
        Files.writeString(target, source.stripIndent())
    }
}
