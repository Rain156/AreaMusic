package datura.gradle

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

import java.lang.reflect.Method
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertNotNull
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

class VerifyFabricProductionJarTest {
    @TempDir
    Path temporaryDirectory

    @Test
    void declaresTheExpectedFabricApiVersionAsATypedTaskInput() {
        Method getter = VerifyFabricProductionJar.declaredMethods.find {
            it.name == 'getExpectedFabricApiVersion'
        }

        assertNotNull(getter, 'expectedFabricApiVersion getter must exist')
        assertEquals(Property, getter.returnType)
        assertEquals(
                'org.gradle.api.provider.Property<java.lang.String>',
                getter.genericReturnType.typeName
        )
        assertNotNull(getter.getAnnotation(Input), 'expectedFabricApiVersion must be a task input')
    }

    @Test
    void supportsAnExactJava21RuntimeConstraintAsATypedTaskInput() {
        Method getter = VerifyFabricProductionJar.declaredMethods.find {
            it.name == 'getExpectedJavaVersion'
        }
        assertNotNull(getter, 'expectedJavaVersion must be a declared typed task input')
        assertEquals(
                'org.gradle.api.provider.Property<java.lang.Integer>',
                getter.genericReturnType.typeName
        )
        assertNotNull(getter.getAnnotation(Input), 'expectedJavaVersion must be a task input')

        Fixture java21 = newFixture()
        java21.task.expectedJavaVersion.set(21)
        java21.replaceArchiveEntry(
                'fabric.mod.json',
                java21.fabricMetadata().replace('"java":">=17"', '"java":">=21"').bytes
        )
        java21.verify()
    }

    @Test
    void acceptsACompleteSyntheticFabricProductionJar() {
        newFixture().verify()
    }

    @Test
    void rejectsAMisnamedProductionJar() {
        Fixture misnamed = newFixture()
        misnamed.archive = misnamed.root.resolve('wrong.jar').toFile()
        assertAuditMessage(misnamed, "expected artifact filename '${Fixture.EXPECTED_FILE_NAME}'")
    }

    @Test
    void rejectsAMissingProductionClass() {
        Fixture missing = newFixture()
        missing.removeArchiveEntry('example/Common.class')
        assertAuditMessage(missing, 'missing production classes: [example/Common.class]')
    }

    @Test
    void rejectsEmptyCoreMainClassesEvenWhenTheArchiveMatches() {
        Fixture emptyCore = newFixture()
        Files.delete(emptyCore.coreClasses.resolve('example/Core.class'))
        emptyCore.removeArchiveEntry('example/Core.class')

        assertAuditMessage(emptyCore, 'no core main classes were found')
    }

    @Test
    void rejectsEmptyCommonMainClassesEvenWhenTheArchiveMatches() {
        Fixture emptyCommon = newFixture()
        emptyCommon.useNonClassRemappingEvidence()
        for (String entry : ['example/Common.class', Fixture.EVIDENCE_ENTRY]) {
            Files.delete(emptyCommon.commonClasses.resolve(entry))
            emptyCommon.removeArchiveEntry(entry)
        }

        assertAuditMessage(emptyCommon, 'no common main classes were found')
    }

    @Test
    void rejectsEmptyFabricMainClassesEvenWhenTheArchiveMatches() {
        Fixture emptyFabric = newFixture()
        for (String entry : [
                'example/Fabric.class',
                Fixture.MAIN_ENTRYPOINT_CLASS,
                Fixture.CLIENT_ENTRYPOINT_CLASS
        ]) {
            Files.delete(emptyFabric.fabricClasses.resolve(entry))
            emptyFabric.removeArchiveEntry(entry)
        }

        assertAuditMessage(emptyFabric, 'no Fabric main classes were found')
    }

    @Test
    void rejectsEmptyTestOutputsWhenTheArchiveHasNoTestLeak() {
        Fixture emptyTests = newFixture()
        Files.delete(emptyTests.testOutputs.resolve('example/TestOnly.class'))
        Files.delete(emptyTests.testOutputs.resolve('test-only-resource.txt'))

        assertAuditMessage(
                emptyTests,
                'no compiled test outputs/resources were found; leakage detection would be vacuous'
        )
    }

    @Test
    void rejectsEachEmptyTestOutputCategoryEvenWhenTheOthersArePopulated() {
        for (String category : ['Core', 'Common', 'Fabric']) {
            Method getter = VerifyFabricProductionJar.declaredMethods.find {
                it.name == "get${category}TestOutputRoots"
            }
            assertNotNull(getter, "${category} test output roots must be a typed task input")
            assertNotNull(getter.getAnnotation(InputFiles),
                    "${category} test output roots must be input files")

            Fixture fixture = newFixture()
            Path empty = fixture.root.resolve("empty-${category.toLowerCase(Locale.ROOT)}-tests")
            Files.createDirectories(empty)
            switch (category) {
                case 'Core' -> fixture.task.coreTestOutputRoots.setFrom(empty.toFile())
                case 'Common' -> fixture.task.commonTestOutputRoots.setFrom(empty.toFile())
                case 'Fabric' -> fixture.task.fabricTestOutputRoots.setFrom(empty.toFile())
            }
            assertAuditMessage(
                    fixture,
                    "no ${category.toLowerCase(Locale.ROOT)} compiled test outputs/resources were found"
            )
        }
    }

    @Test
    void requiresTheMainEntrypointClassWhenTheFabricRootAndArchiveMatch() {
        Fixture missingMain = newFixture()
        Files.delete(missingMain.fabricClasses.resolve(Fixture.MAIN_ENTRYPOINT_CLASS))
        missingMain.removeArchiveEntry(Fixture.MAIN_ENTRYPOINT_CLASS)

        assertAuditMessage(
                missingMain,
                "main entrypoint class '${Fixture.MAIN_ENTRYPOINT_CLASS}' " +
                        'must occur exactly once, found 0'
        )
    }

    @Test
    void requiresTheClientEntrypointClassWhenTheFabricRootAndArchiveMatch() {
        Fixture missingClient = newFixture()
        Files.delete(missingClient.fabricClasses.resolve(Fixture.CLIENT_ENTRYPOINT_CLASS))
        missingClient.removeArchiveEntry(Fixture.CLIENT_ENTRYPOINT_CLASS)

        assertAuditMessage(
                missingClient,
                "client entrypoint class '${Fixture.CLIENT_ENTRYPOINT_CLASS}' " +
                        'must occur exactly once, found 0'
        )
    }

    @Test
    void rejectsAnUnexpectedProductionClass() {
        Fixture unexpected = newFixture()
        unexpected.addArchiveEntry('rogue/Unexpected.class', classBytes(61, 'rogue'))
        assertAuditMessage(unexpected, 'unexpected production classes: [rogue/Unexpected.class]')
    }

    @Test
    void rejectsAllSixPairwiseProductionClassCategoryOverlaps() {
        Map<String, List<String>> pairs = [
                'core/common'  : ['core', 'common'],
                'core/Fabric'  : ['core', 'fabric'],
                'core/codec'   : ['core', 'codec'],
                'common/Fabric': ['common', 'fabric'],
                'common/codec' : ['common', 'codec'],
                'Fabric/codec' : ['fabric', 'codec']
        ]

        pairs.each { label, categories ->
            Fixture fixture = newFixture()
            categories.each { category ->
                fixture.writeRelative(
                        fixture.categoryRoot(category),
                        'shared/Overlap.class',
                        classBytes(61, category)
                )
            }
            fixture.addArchiveEntry('shared/Overlap.class', classBytes(61, 'production'))

            assertAuditMessage(fixture, "${label} class overlap: [shared/Overlap.class]")
        }
    }

    @Test
    void requiresJava17ClassFilesForEveryProjectOwnedProductionClass() {
        for (String category : ['core', 'common', 'fabric']) {
            Fixture fixture = newFixture()
            String entry = "example/${category.capitalize()}TooNew.class"
            fixture.writeRelative(fixture.categoryRoot(category), entry, classBytes(65, 'too new'))
            fixture.addArchiveEntry(entry, classBytes(65, 'too new production'))

            assertAuditMessage(
                    fixture,
                    "project class '${entry}' must use class-file major 61, found 65"
            )
        }
    }

    @Test
    void permitsCodecClassFilesWithAThirdPartyBytecodeLevel() {
        Fixture fixture = newFixture()
        fixture.writeRelative(fixture.codecFiles, 'example/NewCodec.class', classBytes(65, 'codec'))
        fixture.addArchiveEntry('example/NewCodec.class', classBytes(65, 'codec'))

        fixture.verify()
    }

    @Test
    void rejectsAMissingRequiredAudioProviderClass() {
        Fixture fixture = newFixture()
        fixture.task.requiredProviderEntries.set(['example/MissingProvider.class'])

        assertAuditMessage(
                fixture,
                "required audio provider class 'example/MissingProvider.class' " +
                        'must occur exactly once, found 0'
        )
    }

    @Test
    void rejectsNestedJarEntries() {
        Fixture nested = newFixture()
        nested.addArchiveEntry('META-INF/jars/dependency.jar', 'nested'.bytes)
        assertAuditMessage(nested, 'nested JAR entries are forbidden')
    }

    @Test
    void rejectsLeakedTestOutputsAndResources() {
        Fixture testLeak = newFixture()
        testLeak.addArchiveEntry('example/TestOnly.class', classBytes(61, 'test'))
        assertAuditMessage(testLeak, 'test outputs/resources leaked into the production JAR')
    }

    @Test
    void acceptsATestOutputShadowWhenTheJarContainsThatExpectedProductionClass() {
        Fixture shadow = newFixture()
        shadow.writeRelative(
                shadow.testOutputs,
                'example/Core.class',
                classBytes(61, 'test shadow')
        )

        shadow.verify()
    }

    @Test
    void acceptsATestOutputShadowWhenTheJarContainsThatExpectedCodecClass() {
        Fixture shadow = newFixture()
        shadow.writeRelative(
                shadow.testOutputs,
                'example/Codec.class',
                classBytes(61, 'test shadow')
        )

        shadow.verify()
    }

    @Test
    void acceptsATestOutputShadowWhenTheJarContainsThatExpectedProductionResource() {
        Fixture shadow = newFixture()
        shadow.writeRelative(
                shadow.testOutputs,
                'META-INF/codec.properties',
                'test shadow'.bytes
        )

        shadow.verify()
    }

    @Test
    void rejectsLoaderOrArchitecturyClasses() {
        for (String forbidden : [
                'net/fabricmc/loader/api/FabricLoader.class',
                'net/fabricmc/fabric/api/networking/v1/ServerPlayNetworking.class',
                'net/minecraftforge/fml/common/Mod.class',
                'net/neoforged/fml/common/Mod.class',
                'dev/architectury/platform/Platform.class'
        ]) {
            Fixture fixture = newFixture()
            fixture.addArchiveEntry(forbidden, classBytes(61, 'leak'))
            assertAuditMessage(fixture, 'loader/platform dependency classes are forbidden')
        }
    }

    @Test
    void rejectsDuplicateZipEntries() {
        Fixture duplicate = newFixture()
        duplicate.addArchiveEntry('pack.mcmeta', duplicate.packMetadata().bytes)
        assertAuditMessage(duplicate, "duplicate ZIP entry 'pack.mcmeta' occurs 2 times")
    }

    @Test
    void rejectsNonCuratedServiceDescriptors() {
        Fixture service = newFixture()
        service.addArchiveEntry('META-INF/services/example.Uncurated', 'rogue'.bytes)
        assertAuditMessage(service, 'service entries differ from the curated set')
    }

    @Test
    void rejectsChangedCuratedServiceDescriptorBytes() {
        Fixture changedService = newFixture()
        changedService.replaceArchiveEntry(
                'META-INF/services/javax.sound.sampled.spi.AudioFileReader',
                'different\n'.bytes
        )
        assertAuditMessage(changedService, 'is not byte-identical')
    }

    @Test
    void rejectsMalformedOrDuplicateKeyFabricMetadata() {
        Fixture malformed = newFixture()
        malformed.replaceArchiveEntry('fabric.mod.json', '{"schemaVersion":1'.bytes)
        assertAuditMessage(malformed, 'fabric.mod.json is not valid JSON')

        Fixture duplicateKey = newFixture()
        duplicateKey.replaceArchiveEntry(
                'fabric.mod.json',
                duplicateKey.fabricMetadata().replaceFirst(
                        '"id":"areamusic"',
                        '"id":"wrong","id":"areamusic"'
                ).bytes
        )
        assertAuditMessage(duplicateKey, 'fabric.mod.json is not valid JSON')
    }

    @Test
    void requiresExactFabricMetadataIdentityFields() {
        [
                ['"schemaVersion":1', '"schemaVersion":2', 'schemaVersion'],
                ['"id":"areamusic"', '"id":"wrong"', "id must be 'areamusic'"],
                ['"version":"1.2.3"', '"version":"9.9.9"', "version must be '1.2.3'"],
                ['"name":"AreaMusic"', '"name":"Wrong"', "name must be 'AreaMusic'"],
                [
                        '"description":"Play local music automatically."',
                        '"description":"Wrong"',
                        "description must be 'Play local music automatically.'"
                ],
                ['"authors":["Datura"]', '"authors":["Other"]', 'authors must be exactly [Datura]'],
                [
                        '"license":"All Rights Reserved"',
                        '"license":"Wrong"',
                        "license must be 'All Rights Reserved'"
                ],
                ['"environment":"*"', '"environment":"client"', "environment must be '*'"]
        ].each { original, replacement, expectedMessage ->
            Fixture fixture = newFixture()
            fixture.replaceArchiveEntry(
                    'fabric.mod.json',
                    fixture.fabricMetadata().replace(original, replacement).bytes
            )
            assertAuditMessage(fixture, expectedMessage)
        }
    }

    @Test
    void requiresTheExactFabricEntrypoints() {
        Fixture wrongEntrypoint = newFixture()
        wrongEntrypoint.replaceArchiveEntry(
                'fabric.mod.json',
                wrongEntrypoint.fabricMetadata().replace(
                        'datura.areamusic.fabric.AreaMusicFabric',
                        'wrong.Main'
                ).bytes
        )
        assertAuditMessage(wrongEntrypoint, 'main entrypoint')
    }

    @Test
    void requiresTheExactFabricLoaderDependency() {
        Fixture wrongLoader = newFixture()
        wrongLoader.replaceArchiveEntry(
                'fabric.mod.json',
                wrongLoader.fabricMetadata().replace('>=0.19.3', '>=0.19.2').bytes
        )
        assertAuditMessage(wrongLoader, "fabricloader dependency must be '>=0.19.3'")
    }

    @Test
    void requiresTheExactFabricRuntimeDependencies() {
        [
                [
                        '"minecraft":"1.20.1"',
                        '"minecraft":"1.20.2"',
                        "minecraft dependency must be '1.20.1'"
                ],
                ['"java":">=17"', '"java":">=21"', "java dependency must be '>=17'"],
                [
                        '"fabric-api":">=0.92.11+1.20.1"',
                        '"fabric-api":">=0.92.11+1.20.1","rogue":"*"',
                        'depends must contain exactly'
                ]
        ].each { original, replacement, expectedMessage ->
            Fixture fixture = newFixture()
            fixture.replaceArchiveEntry(
                    'fabric.mod.json',
                    fixture.fabricMetadata().replace(original, replacement).bytes
            )
            assertAuditMessage(fixture, expectedMessage)
        }
    }

    @Test
    void rejectsTheWrongFabricApiVersion() {
        Fixture wrongVersion = newFixture()
        wrongVersion.replaceArchiveEntry(
                'fabric.mod.json',
                wrongVersion.fabricMetadata().replace(
                        '>=0.92.11+1.20.1',
                        '>=0.92.10+1.20.1'
                ).bytes
        )
        assertAuditMessage(
                wrongVersion,
                "fabric-api dependency must be '>=0.92.11+1.20.1'"
        )
    }

    @Test
    void rejectsAWildcardFabricApiVersion() {
        Fixture wildcard = newFixture()
        wildcard.replaceArchiveEntry(
                'fabric.mod.json',
                wildcard.fabricMetadata().replace(
                        '>=0.92.11+1.20.1',
                        '*'
                ).bytes
        )
        assertAuditMessage(
                wildcard,
                "fabric-api dependency must be '>=0.92.11+1.20.1'"
        )
    }

    @Test
    void forbidsAnArchitecturyDependency() {
        Fixture architectury = newFixture()
        architectury.replaceArchiveEntry(
                'fabric.mod.json',
                architectury.fabricMetadata().replace(
                        '"fabric-api":">=0.92.11+1.20.1"',
                        '"fabric-api":">=0.92.11+1.20.1","architectury":"*"'
                ).bytes
        )
        assertAuditMessage(architectury, 'must not declare Architectury')
    }

    @Test
    void rejectsUnexpandedTemplateTokens() {
        Fixture token = newFixture()
        token.replaceArchiveEntry(
                'fabric.mod.json',
                token.fabricMetadata().replace('AreaMusic', '${mod_name}').bytes
        )
        assertAuditMessage(token, 'contains an unexpanded template token')
    }

    @Test
    void rejectsUnexpectedProductionResources() {
        Fixture unexpected = newFixture()
        unexpected.addArchiveEntry('rogue/resource.txt', 'rogue'.bytes)
        assertAuditMessage(unexpected, 'unexpected production resources: [rogue/resource.txt]')
    }

    @Test
    void rejectsMissingProductionResources() {
        Fixture missing = newFixture()
        missing.removeArchiveEntry('META-INF/licenses/LGPL-2.1.txt')
        assertAuditMessage(
                missing,
                'missing production resources: [META-INF/licenses/LGPL-2.1.txt]'
        )
    }

    @Test
    void rejectsInvalidPackMetadataJson() {
        Fixture pack = newFixture()
        pack.replaceArchiveEntry('pack.mcmeta', '{"pack":{"pack_format":15,}}'.bytes)
        assertAuditMessage(pack, 'pack.mcmeta is not valid JSON')
    }

    @Test
    void requiresExactPackMetadataValues() {
        [
                [
                        '"description":"areamusic resources"',
                        '"description":"wrong"',
                        "pack.description must be 'areamusic resources'"
                ],
                ['"pack_format":15', '"pack_format":14', 'pack_format must be the integral number 15']
        ].each { original, replacement, expectedMessage ->
            Fixture fixture = newFixture()
            fixture.replaceArchiveEntry(
                    'pack.mcmeta',
                    fixture.packMetadata().replace(original, replacement).bytes
            )
            assertAuditMessage(fixture, expectedMessage)
        }
    }

    @Test
    void requiresBothLanguageResourcesToContainAreaMusicTranslationKeys() {
        ['en_us', 'zh_cn'].each { locale ->
            Fixture language = newFixture()
            language.replaceArchiveEntry("assets/areamusic/lang/${locale}.json", '{}'.bytes)
            assertAuditMessage(
                    language,
                    "${locale}.json does not contain areamusic translation keys"
            )
        }
    }

    @Test
    void requiresExactManifestIdentityWithoutATimestamp() {
        [
                [
                        'Specification-Title: areamusic',
                        'Specification-Title: wrong',
                        "manifest Specification-Title must be 'areamusic'"
                ],
                [
                        'Implementation-Title: AreaMusic',
                        'Implementation-Title: Wrong',
                        "manifest Implementation-Title must be 'AreaMusic'"
                ],
                [
                        'Implementation-Version: 1.2.3',
                        'Implementation-Version: 9.9.9',
                        "manifest Implementation-Version must be '1.2.3'"
                ],
                [
                        'Fabric-Minecraft-Version: 1.20.1',
                        'Fabric-Minecraft-Version: 1.21.1',
                        "manifest Fabric-Minecraft-Version must be '1.20.1'"
                ],
                [
                        '\r\n\r\n',
                        '\r\nImplementation-Timestamp: now\r\n\r\n',
                        'manifest must not contain wall-clock Implementation-Timestamp'
                ]
        ].each { original, replacement, expectedMessage ->
            Fixture fixture = newFixture()
            fixture.replaceArchiveEntry(
                    'META-INF/MANIFEST.MF',
                    fixture.manifest().replace(original, replacement).bytes
            )
            assertAuditMessage(fixture, expectedMessage)
        }
    }

    @Test
    void rejectsByteIdenticalRemappingEvidence() {
        Fixture identical = newFixture()
        identical.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                Files.readAllBytes(identical.commonClasses.resolve(Fixture.EVIDENCE_ENTRY))
        )
        assertAuditMessage(identical, 'is byte-identical to the Mojang-named development class')
    }

    @Test
    void requiresTheExpectedIntermediarySymbolInRemappingEvidence() {
        Fixture missingIntermediary = newFixture()
        missingIntermediary.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                classBytes(61, 'production renamed')
        )
        assertAuditMessage(
                missingIntermediary,
                "lacks expected intermediary symbol 'method_1551'"
        )
    }

    @Test
    void rejectsTheForbiddenMojangSymbolInRemappingEvidence() {
        Fixture mojangNamed = newFixture()
        mojangNamed.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                classBytes(61, 'production method_1551 getInstance')
        )
        assertAuditMessage(mojangNamed, "still contains Mojang symbol 'getInstance'")
    }

    private Fixture newFixture() {
        return new Fixture(temporaryDirectory.resolve(UUID.randomUUID().toString()))
    }

    private static void assertAuditMessage(Fixture fixture, String message) {
        assertTrue(assertAuditFails(fixture).message.contains(message),
                "audit failure must contain: ${message}")
    }

    private static GradleException assertAuditFails(Fixture fixture) {
        return assertThrows(GradleException) { fixture.verify() }
    }

    private static byte[] classBytes(int major, String payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        output.write([0xCA, 0xFE, 0xBA, 0xBE, 0x00, 0x00, (major >>> 8) & 0xFF, major & 0xFF] as byte[])
        output.write(payload.getBytes(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }

    private static final class Fixture {
        static final String EXPECTED_FILE_NAME = 'areamusic-fabric-1.20.1-1.2.3.jar'
        static final String EXPECTED_FABRIC_API_VERSION = '0.92.11+1.20.1'
        static final String EVIDENCE_ENTRY = 'example/RemapEvidence.class'
        static final String MAIN_ENTRYPOINT = 'datura.areamusic.fabric.AreaMusicFabric'
        static final String CLIENT_ENTRYPOINT =
                'datura.areamusic.fabric.client.FabricClientAreaMusic'
        static final String MAIN_ENTRYPOINT_CLASS =
                MAIN_ENTRYPOINT.replace('.', '/') + '.class'
        static final String CLIENT_ENTRYPOINT_CLASS =
                CLIENT_ENTRYPOINT.replace('.', '/') + '.class'
        static final List<String> REQUIRED_RESOURCES = [
                'META-INF/MANIFEST.MF',
                'fabric.mod.json',
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
        final Path fabricClasses
        final Path codecFiles
        final Path testOutputs
        final VerifyFabricProductionJar task
        final List<ArchiveEntryData> archiveEntries = []
        File archive

        Fixture(Path root) {
            this.root = root
            Path projectDirectory = root.resolve('project')
            coreClasses = root.resolve('core-classes')
            commonClasses = root.resolve('common-classes')
            fabricClasses = root.resolve('fabric-classes')
            codecFiles = root.resolve('codec-files')
            testOutputs = root.resolve('test-outputs')
            Path serviceSources = root.resolve('service-sources')
            [projectDirectory, coreClasses, commonClasses, fabricClasses, codecFiles,
             testOutputs, serviceSources].each { Files.createDirectories(it) }

            writeRelative(coreClasses, 'example/Core.class', classBytes(61, 'development core'))
            writeRelative(commonClasses, 'example/Common.class', classBytes(61, 'development common'))
            writeRelative(commonClasses, EVIDENCE_ENTRY, classBytes(61, 'development getInstance'))
            writeRelative(fabricClasses, 'example/Fabric.class', classBytes(61, 'development fabric'))
            writeRelative(
                    fabricClasses,
                    MAIN_ENTRYPOINT_CLASS,
                    classBytes(61, 'development main entrypoint')
            )
            writeRelative(
                    fabricClasses,
                    CLIENT_ENTRYPOINT_CLASS,
                    classBytes(61, 'development client entrypoint')
            )
            writeRelative(codecFiles, 'example/Codec.class', classBytes(52, 'codec'))
            writeRelative(codecFiles, 'META-INF/codec.properties', 'codec metadata'.bytes)
            writeRelative(testOutputs, 'example/TestOnly.class', classBytes(61, 'test'))
            writeRelative(testOutputs, 'test-only-resource.txt', 'test'.bytes)

            Path audioReaderService = serviceSources.resolve('javax.sound.sampled.spi.AudioFileReader')
            Path conversionService = serviceSources.resolve(
                    'javax.sound.sampled.spi.FormatConversionProvider'
            )
            Files.writeString(audioReaderService, 'example.AudioReader\n', StandardCharsets.UTF_8)
            Files.writeString(conversionService, 'example.FormatConverter\n', StandardCharsets.UTF_8)

            addArchiveEntry('example/Core.class', classBytes(61, 'production core'))
            addArchiveEntry('example/Common.class', classBytes(61, 'production common'))
            addArchiveEntry(EVIDENCE_ENTRY, classBytes(61, 'production method_1551'))
            addArchiveEntry('example/Fabric.class', classBytes(61, 'production fabric'))
            addArchiveEntry(
                    MAIN_ENTRYPOINT_CLASS,
                    classBytes(61, 'production main entrypoint')
            )
            addArchiveEntry(
                    CLIENT_ENTRYPOINT_CLASS,
                    classBytes(61, 'production client entrypoint')
            )
            addArchiveEntry('example/Codec.class', classBytes(52, 'codec'))
            addArchiveEntry('META-INF/codec.properties', 'codec metadata'.bytes)
            addArchiveEntry('META-INF/MANIFEST.MF', manifest().bytes)
            addArchiveEntry('fabric.mod.json', fabricMetadata().bytes)
            addArchiveEntry('pack.mcmeta', packMetadata().bytes)
            addArchiveEntry(
                    'assets/areamusic/lang/en_us.json',
                    '{"options.areamusic.volume":"AreaMusic Volume"}'.bytes
            )
            addArchiveEntry(
                    'assets/areamusic/lang/zh_cn.json',
                    '{"options.areamusic.volume":"AreaMusic 音量"}'.getBytes(StandardCharsets.UTF_8)
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
            task = project.tasks.create('verifyFabricProductionJarFixture', VerifyFabricProductionJar)
            task.productionJar.set(archive)
            task.codecDirectory.set(codecFiles.toFile())
            task.coreMainClassRoots.from(coreClasses.toFile())
            task.commonMainClassRoots.from(commonClasses.toFile())
            task.fabricMainClassRoots.from(fabricClasses.toFile())
            task.testOutputRoots.from(testOutputs.toFile())
            task.coreTestOutputRoots.from(testOutputs.toFile())
            task.commonTestOutputRoots.from(testOutputs.toFile())
            task.fabricTestOutputRoots.from(testOutputs.toFile())
            task.serviceSourceFiles.from(audioReaderService.toFile(), conversionService.toFile())
            task.expectedFileName.set(EXPECTED_FILE_NAME)
            task.expectedModId.set('areamusic')
            task.expectedModVersion.set('1.2.3')
            task.expectedModName.set('AreaMusic')
            task.expectedModDescription.set('Play local music automatically.')
            task.expectedModLicense.set('All Rights Reserved')
            task.expectedModAuthors.set('Datura')
            task.expectedMinecraftVersion.set('1.20.1')
            task.expectedLoaderVersion.set('0.19.3')
            task.expectedFabricApiVersion.set(EXPECTED_FABRIC_API_VERSION)
            task.expectedJavaVersion.set(17)
            task.expectedMainEntrypoint.set(MAIN_ENTRYPOINT)
            task.expectedClientEntrypoint.set(CLIENT_ENTRYPOINT)
            task.expectedImplementationTitle.set('AreaMusic')
            task.expectedPackFormat.set(15)
            task.expectedProjectClassMajor.set(61)
            task.requiredResourceEntries.set(REQUIRED_RESOURCES)
            task.requiredProviderEntries.set(['example/Codec.class'])
            task.remappingEvidenceEntry.set(EVIDENCE_ENTRY)
            task.expectedIntermediaryName.set('method_1551')
            task.forbiddenMojangName.set('getInstance')
        }

        Path categoryRoot(String category) {
            return switch (category) {
                case 'core' -> coreClasses
                case 'common' -> commonClasses
                case 'fabric' -> fabricClasses
                case 'codec' -> codecFiles
                default -> throw new AssertionError("unknown category ${category}")
            }
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

        void useNonClassRemappingEvidence() {
            String entry = 'synthetic/remapping-evidence.bin'
            writeRelative(
                    commonClasses,
                    entry,
                    classBytes(61, 'development getInstance')
            )
            addArchiveEntry(entry, classBytes(61, 'production method_1551'))
            task.requiredResourceEntries.set(REQUIRED_RESOURCES + [entry])
            task.remappingEvidenceEntry.set(entry)
        }

        void writeRelative(Path directory, String relativeName, byte[] bytes) {
            Path target = directory.resolve(relativeName)
            Files.createDirectories(target.parent)
            Files.write(target, bytes)
        }

        String packMetadata() {
            return '{"pack":{"description":"areamusic resources","pack_format":15}}'
        }

        String fabricMetadata() {
            return '{' +
                    '"schemaVersion":1,' +
                    '"id":"areamusic",' +
                    '"version":"1.2.3",' +
                    '"name":"AreaMusic",' +
                    '"description":"Play local music automatically.",' +
                    '"authors":["Datura"],' +
                    '"license":"All Rights Reserved",' +
                    '"environment":"*",' +
                    '"entrypoints":{' +
                    '"main":["' + MAIN_ENTRYPOINT + '"],' +
                    '"client":["' + CLIENT_ENTRYPOINT + '"]},' +
                    '"depends":{' +
                    '"fabricloader":">=0.19.3",' +
                    '"minecraft":"1.20.1",' +
                    '"java":">=17",' +
                    '"fabric-api":">=' + EXPECTED_FABRIC_API_VERSION + '"}}'
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

        static String manifest() {
            return 'Manifest-Version: 1.0\r\n' +
                    'Specification-Title: areamusic\r\n' +
                    'Implementation-Title: AreaMusic\r\n' +
                    'Implementation-Version: 1.2.3\r\n' +
                    'Fabric-Minecraft-Version: 1.20.1\r\n' +
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
