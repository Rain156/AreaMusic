package datura.gradle

import org.apache.commons.compress.archivers.zip.ZipArchiveEntry
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.testfixtures.ProjectBuilder
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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
                'development getInstance'.bytes
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
    void acceptsMojangNamedProductionClassesWhenReobfuscationAuditIsDisabled() {
        Fixture fixture = newFixture()
        fixture.disableReobfuscationAudit()
        fixture.replaceArchiveEntry(
                Fixture.EVIDENCE_ENTRY,
                Files.readAllBytes(fixture.commonClasses.resolve(Fixture.EVIDENCE_ENTRY))
        )

        fixture.verify()
    }

    @Test
    void disabledReobfuscationAuditStillRejectsUnexpectedProductionEntries() {
        Fixture fixture = newFixture()
        fixture.disableReobfuscationAudit()
        fixture.addArchiveEntry('rogue/Unexpected.class', classBytes(61, 'rogue'))

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains(
                'unexpected production classes: [rogue/Unexpected.class]'
        ))
    }

    @Test
    void disabledReobfuscationAuditStillRejectsTheWrongClassFileMajor() {
        Fixture fixture = newFixture()
        fixture.disableReobfuscationAudit()
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
        fixture.disableReobfuscationAudit()
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
    void rejectsAnUnexpectedProductionResource() {
        Fixture fixture = newFixture()
        fixture.addArchiveEntry('rogue/unexpected.txt', 'rogue'.bytes)

        GradleException failure = assertAuditFails(fixture)

        assertTrue(failure.message.contains('unexpected production resources: [rogue/unexpected.txt]'))
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
        assertTrue(failure.message.contains("lacks expected SRG symbol 'm_91087_'"))
        assertTrue(failure.message.contains("still contains Mojang symbol 'getInstance'"))
    }

    private Fixture newFixture() {
        return new Fixture(temporaryDirectory.resolve(UUID.randomUUID().toString()))
    }

    private static GradleException assertAuditFails(Fixture fixture) {
        return assertThrows(GradleException) { fixture.verify() }
    }

    private static byte[] classBytes(int major, String payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream()
        output.write([
                0xCA,
                0xFE,
                0xBA,
                0xBE,
                0x00,
                0x00,
                (major >>> 8) & 0xFF,
                major & 0xFF
        ] as byte[])
        output.write(payload.getBytes(StandardCharsets.ISO_8859_1))
        return output.toByteArray()
    }

    private static final class Fixture {
        static final String EXPECTED_FILE_NAME = 'areamusic-forge-1.20.1-1.2.3.jar'
        static final String EVIDENCE_ENTRY = 'example/ForgeEvidence.class'
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
        final Path codecFiles
        final Path testOutputs
        final VerifyProductionJar task
        final List<ArchiveEntryData> archiveEntries = []
        File archive

        Fixture(Path root) {
            this.root = root
            Path projectDirectory = root.resolve('project')
            coreClasses = root.resolve('core-classes')
            commonClasses = root.resolve('common-classes')
            forgeClasses = root.resolve('forge-classes')
            codecFiles = root.resolve('codec-files')
            testOutputs = root.resolve('test-outputs')
            Path serviceSources = root.resolve('service-sources')
            [projectDirectory, coreClasses, commonClasses, forgeClasses, codecFiles, testOutputs, serviceSources].each {
                Files.createDirectories(it)
            }

            writeRelative(coreClasses, 'example/Core.class', classBytes(61, 'development core'))
            writeRelative(commonClasses, 'example/Common.class', classBytes(61, 'development common'))
            writeRelative(commonClasses, EVIDENCE_ENTRY, classBytes(61, 'development getInstance'))
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
            addArchiveEntry(EVIDENCE_ENTRY, classBytes(61, 'production m_91087_'))
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
            task.reobfuscationEvidenceEntry.set(EVIDENCE_ENTRY)
            task.expectedSrgName.set('m_91087_')
            task.forbiddenMojangName.set('getInstance')
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

        void disableReobfuscationAudit() {
            def getter = task.class.methods.find {
                it.name == 'getVerifyReobfuscation' && it.parameterCount == 0
            }
            assertNotNull(getter, 'VerifyProductionJar must expose verifyReobfuscation')
            getter.invoke(task).set(false)
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
