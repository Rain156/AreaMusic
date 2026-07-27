package datura.gradle

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.Opcodes
import org.objectweb.asm.Type

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class VerifyProductionJarTest {
    @TempDir
    Path temporaryDirectory

    @Test
    void acceptsACompleteSyntheticProductionJar() {
        Fixture fixture = newFixture()

        fixture.verify()
    }

    @Test
    void productionTestClassAllowlistsDefaultToEmpty() {
        Fixture fixture = newFixture()
        def entriesGetter = fixture.task.class.methods.find {
            it.name == 'getAllowedProductionTestClassEntries' && it.parameterCount == 0
        }
        def referencesGetter = fixture.task.class.methods.find {
            it.name == 'getAllowedProductionTestClassReferences' && it.parameterCount == 0
        }

        assertNotNull(entriesGetter)
        assertNotNull(referencesGetter)
        assertTrue(entriesGetter.invoke(fixture.task).get().isEmpty())
        assertTrue(referencesGetter.invoke(fixture.task).get().isEmpty())
    }

    @Test
    void ignoresTestOutputEntriesThatAreExpectedProductionEntries() {
        Fixture fixture = newFixture()
        fixture.writeRelative(
                fixture.testOutputs,
                'example/Core.class',
                'test task copy of production core'.bytes
        )

        fixture.verify()
    }

    @Test
    void ignoresCodecClassAlsoPresentUnderTestOutputs() {
        Fixture fixture = newFixture()
        fixture.writeRelative(
                fixture.testOutputs,
                'example/Codec.class',
                'test task copy of production codec'.bytes
        )

        fixture.verify()
    }

    @Test
    void ignoresExpectedProductionResourceAlsoPresentUnderTestOutputs() {
        Fixture fixture = newFixture()
        fixture.writeRelative(
                fixture.testOutputs,
                'pack.mcmeta',
                'test task copy of production metadata'.bytes
        )

        fixture.verify()
    }

    @Test
    void rejectsAnArchivedEntryExclusiveToTestOutputs() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('test-only-resource.txt', 'leaked test resource'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'test outputs/resources leaked into the production JAR: [test-only-resource.txt]'
        ))
    }

    @Test
    void rejectsAForgeOnlyClassAsTheCommonReobfuscationEvidence() {
        Fixture fixture = newFixture()
        Files.delete(fixture.commonClasses.resolve(Fixture.EVIDENCE_ENTRY))
        fixture.writeRelative(
                fixture.forgeClasses,
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, [Fixture.MOJANG_NAME], [], [], [])
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'common development class for reobfuscation comparison is missing: ' + Fixture.EVIDENCE_ENTRY
        ))
    }

    @Test
    void rejectsAMisnamedArtifact() {
        Fixture fixture = newFixture()
        fixture.archive = fixture.root.resolve('wrong-name.jar').toFile()

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains("expected artifact filename '${Fixture.EXPECTED_FILE_NAME}'"))
    }

    @Test
    void rejectsAProjectClassWithTheWrongClassFileMajor() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                'example/Core.class',
                classBytes(65, 'production core')
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "project class 'example/Core.class' must use class-file major 61, found 65"
        ))
    }

    @Test
    void acceptsMojangNamedProductionClassWithExpectedSymbolAndNoSrgSymbol() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                Files.readAllBytes(fixture.commonClasses.resolve(Fixture.EVIDENCE_ENTRY))
        )

        fixture.verify()
    }

    @Test
    void rejectsMojangNamingEvidenceThatOnlyLoadsExpectedNameAsAString() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, [], [Fixture.MOJANG_NAME], [], [])
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "production class '${Fixture.EVIDENCE_ENTRY}' lacks expected Mojang method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.MOJANG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void rejectsMojangNamingEvidenceWithOnlyALongerMethodName() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, ['getXOffset'], [], [], [])
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "lacks expected Mojang method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.MOJANG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void acceptsMojangNamingEvidenceFromAnExactMethodHandleReference() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, [], [], [Fixture.MOJANG_NAME], [])
        )

        fixture.verify()
    }

    @Test
    void rejectsMojangNamedProductionClassMissingExpectedMojangSymbol() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, ['getY'], [], [], [])
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "production class '${Fixture.EVIDENCE_ENTRY}' lacks expected Mojang method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.MOJANG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void rejectsMojangNamedProductionClassContainingForbiddenSrgSymbol() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(
                        61,
                        [Fixture.MOJANG_NAME, Fixture.SRG_NAME],
                        [],
                        [],
                        []
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "production class '${Fixture.EVIDENCE_ENTRY}' still contains forbidden SRG method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.SRG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void rejectsForbiddenSrgMethodHandleNestedInMojangConstantDynamicEvidence() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(
                        61,
                        [Fixture.MOJANG_NAME],
                        [],
                        [],
                        [Fixture.SRG_NAME]
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "still contains forbidden SRG method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.SRG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void disabledReobfuscationAuditStillRejectsUnexpectedProductionEntries() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.addArchiveEntry('rogue/Unexpected.class', classBytes(61, 'rogue'))

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'unexpected production classes: [rogue/Unexpected.class]'
        ))
    }

    @Test
    void disabledReobfuscationAuditStillRejectsTheWrongClassFileMajor() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                'example/Core.class',
                classBytes(65, 'production core')
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "project class 'example/Core.class' must use class-file major 61, found 65"
        ))
    }

    @Test
    void disabledReobfuscationAuditStillRejectsIncorrectMetadata() {
        Fixture fixture = newFixture()
        fixture.useMojangNamingMode()
        fixture.replaceArchiveEntry(
                'META-INF/mods.toml',
                'modId="areamusic"\nversion="9.9.9"\n'.bytes
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "mods.toml must contain exactly one version = '1.2.3'"
        ))
    }

    @Test
    void rejectsAnUnexpectedProductionClass() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('rogue/Unexpected.class', 'rogue'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('unexpected production classes: [rogue/Unexpected.class]'))
    }

    @Test
    void rejectsForbiddenEntriesEvenWhenTheyAreDeclaredAsExpectedPlatformOutput() {
        [
                'org/spockframework/runtime/SpockRuntime.class',
                'org/gradle/api/Project.class',
                'org/gradle/testkit/runner/GradleRunner.class',
                'org/gradle/test/fixtures/ProjectBuilder.class',
                'example/InjectedTest.class',
                'net/fabricmc/loader/api/FabricLoader.class',
                'net/fabricmc/fabric/api/event/Event.class',
                'datura/areamusic/fabric/FabricAreaMusic.class',
                'dev/architectury/platform/Platform.class',
                'com/jcraft/jorbis/Info.class'
        ].each { entryName ->
            Fixture fixture = newFixture()
            fixture.useNeoForgeMode()
            fixture.addExpectedPlatformClass(
                    entryName,
                    classBytes(61, entryName - '.class')
            )

            GradleException failure = assertAuditFails(fixture)

            assertTrue(failure.message.contains(entryName), failure.message)
        }
    }

    @Test
    void rejectsForgeEntriesFromANeoForgeExpectedSourceRoot() {
        Fixture fixture = newFixture()
        fixture.useNeoForgeMode()
        String entryName = 'net/minecraftforge/fml/ModLoader.class'
        fixture.addExpectedPlatformClass(entryName, classBytes(61, entryName - '.class'))

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(entryName), failure.message)
    }

    @Test
    void rejectsForgeLoaderEntriesFromAForgeExpectedSourceRoot() {
        Fixture fixture = newFixture()
        String entryName = 'net/minecraftforge/fml/ModLoader.class'
        fixture.addExpectedPlatformClass(entryName, classBytes(61, entryName - '.class'))

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(entryName), failure.message)
    }

    @Test
    void permitsForgeLoaderReferencesFromAForgeProjectClass() {
        Fixture fixture = newFixture()
        fixture.addExpectedPlatformClass(
                'example/ForgeIntegration.class',
                classWithFieldReference(
                        61,
                        'example/ForgeIntegration',
                        'net/minecraftforge/fml/ModLoader'
                )
        )

        fixture.verify()
    }

    @Test
    void rejectsTestLikeEntriesFromAForgeExpectedSourceRoot() {
        Fixture fixture = newFixture()
        String entryName = 'example/InjectedTest.class'
        fixture.addExpectedPlatformClass(entryName, classBytes(61, entryName - '.class'))

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(entryName), failure.message)
    }

    @Test
    void rejectsTestLikeReferencesFromAForgeExpectedProjectClass() {
        Fixture fixture = newFixture()
        String referencedInternalName = 'example/InjectedFixture'
        fixture.addExpectedPlatformClass(
                'example/Contaminated.class',
                classWithFieldReference(
                        61,
                        'example/Contaminated',
                        referencedInternalName
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(referencedInternalName), failure.message)
    }

    @Test
    void rejectsForbiddenReferencesFromAnOtherwiseExpectedProjectClass() {
        Fixture fixture = newFixture()
        fixture.useNeoForgeMode()
        String entryName = 'example/Contaminated.class'
        fixture.addExpectedPlatformClass(
                entryName,
                classWithFieldReference(
                        61,
                        'example/Contaminated',
                        'net/fabricmc/api/Environment'
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('net/fabricmc/api/Environment'), failure.message)
    }

    @Test
    void permitsTheIntentionalLegacyForgeMinecraftGameTestEntrypoint() {
        Fixture fixture = newFixture()
        String entryName = 'datura/areamusic/gametest/AreaMusicGameTests.class'
        fixture.allowProductionTestClass(
                entryName,
                ['net/minecraft/gametest/framework/GameTest']
        )
        fixture.addExpectedPlatformClass(
                entryName,
                classWithFieldReference(
                        61,
                        'datura/areamusic/gametest/AreaMusicGameTests',
                        'net/minecraft/gametest/framework/GameTest'
                )
        )

        fixture.verify()
    }

    @Test
    void exactGameTestReferenceAllowlistDoesNotApplyToOtherProductionClasses() {
        Fixture fixture = newFixture()
        String gameTestEntry = 'datura/areamusic/gametest/AreaMusicGameTests.class'
        String gameTestReference = 'net/minecraft/gametest/framework/GameTest'
        fixture.allowProductionTestClass(gameTestEntry, [gameTestReference])
        fixture.addExpectedPlatformClass(
                gameTestEntry,
                classWithFieldReference(
                        61,
                        gameTestEntry - '.class',
                        gameTestReference
                )
        )
        fixture.addExpectedPlatformClass(
                'example/Contaminated.class',
                classWithFieldReference(
                        61,
                        'example/Contaminated',
                        gameTestReference
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('example/Contaminated.class'), failure.message)
        assertTrue(failure.message.contains(gameTestReference), failure.message)
    }

    @Test
    void rejectsClientOnlyReachabilityHiddenInRecursiveConstantDynamicArguments() {
        Fixture fixture = newFixture()
        fixture.useNeoForgeMode()
        fixture.replaceExpectedPlatformClass(
                'datura/areamusic/AreaMusic.class',
                classWithFieldReference(
                        61,
                        'datura/areamusic/AreaMusic',
                        'datura/areamusic/server/Reachable'
                )
        )
        fixture.addExpectedPlatformClass(
                'datura/areamusic/server/Reachable.class',
                classWithRecursiveConstantDynamicReference(
                        61,
                        'datura/areamusic/server/Reachable',
                        'net/minecraft/client/Minecraft'
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('net/minecraft/client/Minecraft'), failure.message)
        assertTrue(failure.message.contains(
                'datura/areamusic/AreaMusic -> datura/areamusic/server/Reachable'
        ), failure.message)
    }

    @Test
    void rejectsAnUnexpectedProductionResource() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('rogue/unexpected.txt', 'rogue'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('unexpected production resources: [rogue/unexpected.txt]'))
    }

    @Test
    void rejectsNestedJarEntries() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('META-INF/jars/dependency.jar', 'nested'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'nested JAR entries are forbidden: [META-INF/jars/dependency.jar]'
        ))
    }

    @Test
    void rejectsAMissingCommonProductionClass() {
        Fixture fixture = newFixture()
        fixture.removeArchiveEntry('example/Common.class')

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains("common main class 'example/Common.class' must occur exactly once, found 0"))
        assertTrue(failure.message.contains('missing production classes: [example/Common.class]'))
    }

    @Test
    void rejectsAnArchivedClassThatIsNotInTheCommonOutput() {
        Fixture fixture = newFixture()
        Files.delete(fixture.commonClasses.resolve('example/Common.class'))

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('unexpected production classes: [example/Common.class]'))
    }

    @Test
    void rejectsOverlappingCoreAndForgeClassCategories() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.coreClasses, 'shared/Overlap.class', 'core overlap'.bytes)
        fixture.writeRelative(fixture.forgeClasses, 'shared/Overlap.class', 'forge overlap'.bytes)
        fixture.addArchiveEntry('shared/Overlap.class', 'production overlap'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('core/Forge class overlap: [shared/Overlap.class]'))
    }

    @Test
    void rejectsOverlappingCoreAndCommonClassCategories() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.coreClasses, 'shared/Overlap.class', 'core overlap'.bytes)
        fixture.writeRelative(fixture.commonClasses, 'shared/Overlap.class', 'common overlap'.bytes)
        fixture.addArchiveEntry('shared/Overlap.class', 'production overlap'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('core/common class overlap: [shared/Overlap.class]'))
    }

    @Test
    void rejectsOverlappingCommonAndForgeClassCategories() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.commonClasses, 'shared/Overlap.class', 'common overlap'.bytes)
        fixture.writeRelative(fixture.forgeClasses, 'shared/Overlap.class', 'forge overlap'.bytes)
        fixture.addArchiveEntry('shared/Overlap.class', 'production overlap'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('common/Forge class overlap: [shared/Overlap.class]'))
    }

    @Test
    void rejectsOverlappingCommonAndCodecClassCategories() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.commonClasses, 'shared/Overlap.class', 'common overlap'.bytes)
        fixture.writeRelative(fixture.codecFiles, 'shared/Overlap.class', 'codec overlap'.bytes)
        fixture.addArchiveEntry('shared/Overlap.class', 'production overlap'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('common/codec class overlap: [shared/Overlap.class]'))
    }

    @Test
    void rejectsOverlappingCoreAndCodecClassCategories() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.coreClasses, 'shared/Overlap.class', 'core overlap'.bytes)
        fixture.writeRelative(fixture.codecFiles, 'shared/Overlap.class', 'codec overlap'.bytes)
        fixture.addArchiveEntry('shared/Overlap.class', 'production overlap'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('core/codec class overlap: [shared/Overlap.class]'))
    }

    @Test
    void rejectsOverlappingForgeAndCodecClassCategories() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.forgeClasses, 'shared/Overlap.class', 'forge overlap'.bytes)
        fixture.writeRelative(fixture.codecFiles, 'shared/Overlap.class', 'codec overlap'.bytes)
        fixture.addArchiveEntry('shared/Overlap.class', 'production overlap'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('Forge/codec class overlap: [shared/Overlap.class]'))
    }

    @Test
    void rejectsAMissingExpectedProductionClass() {
        Fixture fixture = newFixture()
        fixture.removeArchiveEntry('example/Core.class')

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('example/Core.class'))
    }

    @Test
    void rejectsMalformedPackMetadataEvenWhenExpectedFieldsArePresent() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                'pack.mcmeta',
                '{"pack":{"description":"areamusic resources","pack_format":15}'.bytes
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack.mcmeta is not valid JSON'))
    }

    @Test
    void rejectsTrailingTextAfterPackMetadata() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                'pack.mcmeta',
                (fixture.packMetadata('15') + ' trailing').bytes
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack.mcmeta is not valid JSON'))
    }

    @Test
    void rejectsTrailingCommaInPackMetadata() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                'pack.mcmeta',
                '{"pack":{"description":"areamusic resources","pack_format":15,}}'.bytes
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack.mcmeta is not valid JSON'))
    }

    @Test
    void rejectsDuplicateKeysInPackMetadata() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                'pack.mcmeta',
                '{"pack":{"description":"areamusic resources","pack_format":16,"pack_format":15}}'.bytes
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack.mcmeta is not valid JSON'))
    }

    @Test
    void rejectsPackMetadataWithoutAPackObject() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                'pack.mcmeta',
                '{"description":"areamusic resources","pack_format":15}'.bytes
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack.mcmeta must contain a pack object'))
    }

    @Test
    void rejectsNonIntegralPackFormat() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry('pack.mcmeta', fixture.packMetadata('15.5').bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack_format'))
    }

    @Test
    void acceptsNumericallyIntegralDecimalPackFormat() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry('pack.mcmeta', fixture.packMetadata('15.0').bytes)

        fixture.verify()
    }

    @Test
    void rejectsStringPackFormat() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry('pack.mcmeta', fixture.packMetadata('"15"').bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack_format'))
    }

    @Test
    void rejectsPrefixPackFormat() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry('pack.mcmeta', fixture.packMetadata('15oops').bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack.mcmeta is not valid JSON'))
    }

    @Test
    void rejectsWrongPackFormat() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry('pack.mcmeta', fixture.packMetadata('16').bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('pack_format'))
    }

    @Test
    void rejectsDuplicateZipEntries() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('pack.mcmeta', fixture.packMetadata('15').bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains("duplicate ZIP entry 'pack.mcmeta' occurs 2 times"))
    }

    @Test
    void rejectsBadRemappingEvidence() {
        Fixture fixture = newFixture()
        byte[] developmentBytes = Files.readAllBytes(
                fixture.commonClasses.resolve(Fixture.EVIDENCE_ENTRY)
        )
        fixture.replaceArchiveEntry(Fixture.EVIDENCE_ENTRY, developmentBytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('is byte-identical to the Mojang-named development class'))
        assertTrue(failure.message.contains(
                "lacks expected SRG method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.SRG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
        assertTrue(failure.message.contains(
                "still contains forbidden Mojang method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.MOJANG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void rejectsSrgNamingEvidenceThatOnlyLoadsExpectedNameAsAString() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, [], [Fixture.SRG_NAME], [], [])
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "lacks expected SRG method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.SRG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void rejectsSrgNamingEvidenceWithOnlyALongerMethodName() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, [Fixture.SRG_NAME + 'Suffix'], [], [], [])
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "lacks expected SRG method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.SRG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    @Test
    void acceptsSrgNamingEvidenceFromAnExactMethodHandleReference() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(61, [], [], [Fixture.SRG_NAME], [])
        )

        fixture.verify()
    }

    @Test
    void rejectsForbiddenMojangMethodHandleNestedInSrgConstantDynamicEvidence() {
        Fixture fixture = newFixture()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                namingEvidenceClassBytes(
                        61,
                        [Fixture.SRG_NAME],
                        [],
                        [],
                        [Fixture.MOJANG_NAME]
                )
        )

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                "still contains forbidden Mojang method reference " +
                        "'${Fixture.METHOD_OWNER}#${Fixture.MOJANG_NAME}${Fixture.METHOD_DESCRIPTOR}'"
        ))
    }

    private Fixture newFixture() {
        return new Fixture(temporaryDirectory.resolve(UUID.randomUUID().toString()))
    }

    private static GradleException assertAuditFails(Fixture fixture) {
        return assertThrows(GradleException) { fixture.verify() }
    }

    private static byte[] classBytes(int major, String payload) {
        return classBytes(major, 'example/SyntheticClass', payload)
    }

    private static byte[] classBytes(int major, String internalName, String payload) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
                major,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName,
                null,
                'java/lang/Object',
                null
        )
        writer.visitSource(payload, null)
        writeConstructor(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] classWithFieldReference(
            int major,
            String internalName,
            String referencedInternalName
    ) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
                major,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName,
                null,
                'java/lang/Object',
                null
        )
        writer.visitField(
                Opcodes.ACC_PRIVATE,
                'reference',
                "L${referencedInternalName};",
                null,
                null
        ).visitEnd()
        writeConstructor(writer)
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] classWithRecursiveConstantDynamicReference(
            int major,
            String internalName,
            String referencedInternalName
    ) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
                major,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                internalName,
                null,
                'java/lang/Object',
                null
        )
        writeConstructor(writer)
        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                'example/Bootstrap',
                'bootstrap',
                '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;' +
                        'Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;',
                false
        )
        ConstantDynamic inner = new ConstantDynamic(
                'inner',
                'Ljava/lang/Object;',
                bootstrap,
                Type.getObjectType(referencedInternalName)
        )
        ConstantDynamic outer = new ConstantDynamic(
                'outer',
                'Ljava/lang/Object;',
                bootstrap,
                inner
        )
        def method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                'reference',
                '()V',
                null,
                null
        )
        method.visitCode()
        method.visitLdcInsn(outer)
        method.visitInsn(Opcodes.POP)
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static byte[] namingEvidenceClassBytes(
            int major,
            List<String> invokedNames,
            List<String> stringConstants,
            List<String> methodHandleNames,
            List<String> constantDynamicHandleNames
    ) {
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS)
        writer.visit(
                major,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER,
                'example/ForgeEvidence',
                null,
                'java/lang/Object',
                null
        )
        writeConstructor(writer)
        def method = writer.visitMethod(
                Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC,
                'evidence',
                '()V',
                null,
                null
        )
        method.visitCode()
        stringConstants.each { constant ->
            method.visitLdcInsn(constant)
            method.visitInsn(Opcodes.POP)
        }
        invokedNames.each { name ->
            method.visitInsn(Opcodes.ACONST_NULL)
            method.visitMethodInsn(
                    Opcodes.INVOKEVIRTUAL,
                    Fixture.METHOD_OWNER,
                    name,
                    Fixture.METHOD_DESCRIPTOR,
                    false
            )
            method.visitInsn(Opcodes.POP)
        }
        methodHandleNames.each { name ->
            method.visitLdcInsn(methodHandle(name))
            method.visitInsn(Opcodes.POP)
        }
        Handle bootstrap = new Handle(
                Opcodes.H_INVOKESTATIC,
                'example/Bootstrap',
                'bootstrap',
                '(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;' +
                        'Ljava/lang/Class;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;',
                false
        )
        constantDynamicHandleNames.eachWithIndex { name, index ->
            method.visitLdcInsn(new ConstantDynamic(
                    "evidence${index}",
                    'Ljava/lang/Object;',
                    bootstrap,
                    methodHandle(name)
            ))
            method.visitInsn(Opcodes.POP)
        }
        method.visitInsn(Opcodes.RETURN)
        method.visitMaxs(0, 0)
        method.visitEnd()
        writer.visitEnd()
        return writer.toByteArray()
    }

    private static Handle methodHandle(String name) {
        return new Handle(
                Opcodes.H_INVOKEVIRTUAL,
                Fixture.METHOD_OWNER,
                name,
                Fixture.METHOD_DESCRIPTOR,
                false
        )
    }

    private static void writeConstructor(ClassWriter writer) {
        def constructor = writer.visitMethod(Opcodes.ACC_PUBLIC, '<init>', '()V', null, null)
        constructor.visitCode()
        constructor.visitVarInsn(Opcodes.ALOAD, 0)
        constructor.visitMethodInsn(
                Opcodes.INVOKESPECIAL,
                'java/lang/Object',
                '<init>',
                '()V',
                false
        )
        constructor.visitInsn(Opcodes.RETURN)
        constructor.visitMaxs(0, 0)
        constructor.visitEnd()
    }

    private static final class Fixture {
        static final String EXPECTED_FILE_NAME = 'areamusic-forge-1.20.1-1.2.3.jar'
        static final String EVIDENCE_ENTRY = 'example/ForgeEvidence.class'
        static final String METHOD_OWNER = 'net/minecraft/core/BlockPos'
        static final String METHOD_DESCRIPTOR = '()I'
        static final String MOJANG_NAME = 'getX'
        static final String SRG_NAME = 'm_123341_'
        static final List<String> REQUIRED_RESOURCES = [
                'META-INF/MANIFEST.MF',
                'META-INF/mods.toml',
                'pack.mcmeta',
                'assets/areamusic/lang/en_us.json',
                'assets/areamusic/lang/zh_cn.json',
                'META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt',
                'META-INF/licenses/LGPL-2.1.txt',
                'META-INF/services/javax.sound.sampled.spi.AudioFileReader',
                'META-INF/services/javax.sound.sampled.spi.FormatConversionProvider'
        ].asImmutable()

        final Path root
        final Path coreClasses
        final Path commonClasses
        final Path forgeClasses
        final Path neoforgeClasses
        final Path codecFiles
        final Path testOutputs
        final VerifyProductionJar task
        final List<ArchiveEntryData> archiveEntries = []
        File archive
        boolean neoForgeMode

        Fixture(Path root) {
            this.root = root
            Path projectDirectory = root.resolve('project')
            coreClasses = root.resolve('core-classes')
            commonClasses = root.resolve('common-classes')
            forgeClasses = root.resolve('forge-classes')
            neoforgeClasses = root.resolve('neoforge-classes')
            codecFiles = root.resolve('codec-files')
            testOutputs = root.resolve('test-outputs')
            Path serviceSources = root.resolve('service-sources')
            [projectDirectory, coreClasses, commonClasses, forgeClasses, neoforgeClasses,
             codecFiles, testOutputs, serviceSources].each {
                Files.createDirectories(it)
            }

            writeRelative(coreClasses, 'example/Core.class', classBytes(61, 'development core'))
            writeRelative(commonClasses, 'example/Common.class', classBytes(61, 'development common'))
            writeRelative(
                    commonClasses,
                    EVIDENCE_ENTRY,
                    namingEvidenceClassBytes(61, [MOJANG_NAME], [], [], [])
            )
            writeRelative(forgeClasses, 'example/Forge.class', classBytes(61, 'development forge'))
            writeRelative(codecFiles, 'example/Codec.class', 'staged codec'.bytes)
            writeRelative(codecFiles, 'META-INF/codec.properties', 'codec metadata'.bytes)
            writeRelative(testOutputs, 'example/TestOnly.class', 'test class'.bytes)
            writeRelative(testOutputs, 'test-only-resource.txt', 'test resource'.bytes)

            Path audioReaderService = serviceSources.resolve('javax.sound.sampled.spi.AudioFileReader')
            Path conversionService = serviceSources.resolve('javax.sound.sampled.spi.FormatConversionProvider')
            Files.writeString(audioReaderService, 'example.AudioReader\n', StandardCharsets.UTF_8)
            Files.writeString(conversionService, 'example.FormatConverter\n', StandardCharsets.UTF_8)

            addArchiveEntry('example/Core.class', classBytes(61, 'production core'))
            addArchiveEntry('example/Common.class', classBytes(61, 'production common'))
            addArchiveEntry(
                    EVIDENCE_ENTRY,
                    namingEvidenceClassBytes(61, [SRG_NAME], [], [], [])
            )
            addArchiveEntry('example/Forge.class', classBytes(61, 'production forge'))
            addArchiveEntry('example/Codec.class', 'staged codec'.bytes)
            addArchiveEntry('META-INF/codec.properties', 'codec metadata'.bytes)
            addArchiveEntry('META-INF/MANIFEST.MF', manifest().bytes)
            addArchiveEntry(
                    'META-INF/mods.toml',
                    'modId="areamusic"\nversion="1.2.3"\n'.bytes
            )
            addArchiveEntry('pack.mcmeta', packMetadata('15').bytes)
            addArchiveEntry(
                    'assets/areamusic/lang/en_us.json',
                    '{"key.areamusic.example":"Example"}'.bytes
            )
            addArchiveEntry(
                    'assets/areamusic/lang/zh_cn.json',
                    '{"key.areamusic.example":"示例"}'.getBytes(StandardCharsets.UTF_8)
            )
            addArchiveEntry('META-INF/AREA_MUSIC_THIRD_PARTY_NOTICES.txt', 'notices'.bytes)
            addArchiveEntry('META-INF/licenses/LGPL-2.1.txt', 'license'.bytes)
            addArchiveEntry(
                    'META-INF/services/javax.sound.sampled.spi.AudioFileReader',
                    Files.readAllBytes(audioReaderService)
            )
            addArchiveEntry(
                    'META-INF/services/javax.sound.sampled.spi.FormatConversionProvider',
                    Files.readAllBytes(conversionService)
            )

            archive = root.resolve(EXPECTED_FILE_NAME).toFile()
            Project project = ProjectBuilder.builder().withProjectDir(projectDirectory.toFile()).build()
            task = project.tasks.create('verifyProductionJarFixture', VerifyProductionJar)
            task.productionJar.set(archive)
            task.codecDirectory.set(codecFiles.toFile())
            task.coreMainClassRoots.from(coreClasses.toFile())
            task.commonMainClassRoots.from(commonClasses.toFile())
            task.forgeMainClassRoots.from(forgeClasses.toFile())
            task.neoforgeMainClassRoots.from(neoforgeClasses.toFile())
            task.testOutputRoots.from(testOutputs.toFile())
            task.serviceSourceFiles.from(audioReaderService.toFile(), conversionService.toFile())
            task.expectedFileName.set(EXPECTED_FILE_NAME)
            task.expectedModId.set('areamusic')
            task.expectedModVersion.set('1.2.3')
            task.expectedImplementationTitle.set('AreaMusic')
            task.expectedPackFormat.set(15)
            def classMajorGetter = task.class.methods.find {
                it.name == 'getExpectedProjectClassMajor' && it.parameterCount == 0
            }
            if (classMajorGetter != null) {
                classMajorGetter.invoke(task).set(61)
            }
            def reobfuscationGetter = task.class.methods.find {
                it.name == 'getVerifyReobfuscation' && it.parameterCount == 0
            }
            if (reobfuscationGetter != null) {
                reobfuscationGetter.invoke(task).set(true)
            }
            task.requiredResourceEntries.set(REQUIRED_RESOURCES)
            task.requiredProviderEntries.set(['example/Codec.class'])
            setTaskProperty(
                    ['getNamingEvidenceEntry', 'getReobfuscationEvidenceEntry'],
                    EVIDENCE_ENTRY,
                    true
            )
            setTaskProperty(['getExpectedSrgName'], SRG_NAME, true)
            setTaskProperty(['getForbiddenMojangName'], MOJANG_NAME, true)
            setTaskProperty(['getNamingEvidenceMethodOwner'], METHOD_OWNER, false)
            setTaskProperty(['getNamingEvidenceMethodDescriptor'], METHOD_DESCRIPTOR, false)
        }

        void verify() {
            task.productionJar.set(archive)
            writeArchive()
            task.verifyArchive()
        }

        void addArchiveEntry(String name, byte[] bytes) {
            archiveEntries.add(new ArchiveEntryData(name, bytes))
        }

        void replaceArchiveEntry(String name, byte[] bytes) {
            archiveEntries.removeAll { it.name == name }
            addArchiveEntry(name, bytes)
        }

        void removeArchiveEntry(String name) {
            archiveEntries.removeAll { it.name == name }
        }

        void addExpectedPlatformClass(String entryName, byte[] bytes) {
            writeRelative(neoForgeMode ? neoforgeClasses : forgeClasses, entryName, bytes)
            addArchiveEntry(entryName, bytes)
        }

        void replaceExpectedPlatformClass(String entryName, byte[] bytes) {
            writeRelative(neoForgeMode ? neoforgeClasses : forgeClasses, entryName, bytes)
            replaceArchiveEntry(entryName, bytes)
        }

        void useNeoForgeMode() {
            neoForgeMode = true
            Files.delete(forgeClasses.resolve('example/Forge.class'))
            removeArchiveEntry('example/Forge.class')
            task.forgeMainClassRoots.setFrom([])
            task.neoforgeMainClassRoots.setFrom(neoforgeClasses.toFile())
            useMojangNamingMode()
            addExpectedPlatformClass(
                    'example/NeoForge.class',
                    classBytes(61, 'example/NeoForge', 'development neoforge')
            )
            addExpectedPlatformClass(
                    'datura/areamusic/AreaMusic.class',
                    classBytes(61, 'datura/areamusic/AreaMusic', 'entrypoint')
            )
            addExpectedPlatformClass(
                    'datura/areamusic/server/NeoForgeAreaMusicServer.class',
                    classBytes(
                            61,
                            'datura/areamusic/server/NeoForgeAreaMusicServer',
                            'server subscriber'
                    )
            )
        }

        void allowProductionTestClass(String entryName, List<String> references) {
            task.allowedProductionTestClassEntries.add(entryName)
            task.allowedProductionTestClassReferences.addAll(references)
        }

        void useMojangNamingMode() {
            setTaskProperty(['getVerifyReobfuscation'], false, true)
            setTaskProperty(
                    ['getNamingEvidenceEntry', 'getReobfuscationEvidenceEntry'],
                    EVIDENCE_ENTRY,
                    true
            )
            setTaskProperty(['getExpectedMojangName'], MOJANG_NAME, false)
            setTaskProperty(['getForbiddenSrgName'], SRG_NAME, false)
        }

        private void setTaskProperty(List<String> getterNames, Object value, boolean required) {
            def getter = task.class.methods.find {
                getterNames.contains(it.name) && it.parameterCount == 0
            }
            if (required) {
                assertNotNull(
                        getter,
                        "VerifyProductionJar must expose one of ${getterNames}"
                )
            }
            if (getter != null) {
                getter.invoke(task).set(value)
            }
        }

        void writeRelative(Path directory, String relativeName, byte[] bytes) {
            Path target = directory.resolve(relativeName)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }

        String packMetadata(String packFormatLiteral) {
            return '{"pack":{"description":"areamusic resources","pack_format":' +
                    packFormatLiteral + '}}'
        }

        private void writeArchive() {
            Files.createDirectories(archive.toPath().parent)
            ZipArchiveOutputStream output = new ZipArchiveOutputStream(archive)
            try {
                archiveEntries.each { entryData ->
                    ZipArchiveEntry entry = new ZipArchiveEntry(entryData.name)
                    entry.time = 0L
                    output.putArchiveEntry(entry)
                    output.write(entryData.bytes)
                    output.closeArchiveEntry()
                }
                output.finish()
            } finally {
                output.close()
            }
        }

        private static String manifest() {
            return 'Manifest-Version: 1.0\r\n' +
                    'Specification-Title: areamusic\r\n' +
                    'Implementation-Title: AreaMusic\r\n' +
                    'Implementation-Version: 1.2.3\r\n' +
                    '\r\n'
        }
    }

    private static final class ArchiveEntryData {
        final String name
        final byte[] bytes

        ArchiveEntryData(String name, byte[] bytes) {
            this.name = name
            this.bytes = bytes
        }
    }
}
