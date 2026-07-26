package datura.gradle

import groovy.json.JsonSlurper
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyFabricProductionJar extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getProductionJar()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getCodecDirectory()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getCoreMainClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getCommonMainClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getFabricMainClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestOutputRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getCoreTestOutputRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getCommonTestOutputRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getFabricTestOutputRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getServiceSourceFiles()

    @Input
    abstract Property<String> getExpectedFileName()

    @Input
    abstract Property<String> getExpectedModId()

    @Input
    abstract Property<String> getExpectedModVersion()

    @Input
    abstract Property<String> getExpectedModName()

    @Input
    abstract Property<String> getExpectedModDescription()

    @Input
    abstract Property<String> getExpectedModLicense()

    @Input
    abstract Property<String> getExpectedModAuthors()

    @Input
    abstract Property<String> getExpectedMinecraftVersion()

    @Input
    abstract Property<String> getExpectedLoaderVersion()

    @Input
    abstract Property<String> getExpectedFabricApiVersion()

    @Input
    abstract Property<Integer> getExpectedJavaVersion()

    @Input
    abstract Property<String> getExpectedMainEntrypoint()

    @Input
    abstract Property<String> getExpectedClientEntrypoint()

    @Input
    abstract Property<String> getExpectedImplementationTitle()

    @Input
    abstract Property<Integer> getExpectedPackFormat()

    @Input
    abstract Property<Integer> getExpectedProjectClassMajor()

    @Input
    abstract ListProperty<String> getRequiredResourceEntries()

    @Input
    abstract ListProperty<String> getRequiredProviderEntries()

    @Input
    abstract Property<String> getRemappingEvidenceEntry()

    @Input
    abstract Property<String> getExpectedIntermediaryName()

    @Input
    abstract Property<String> getForbiddenMojangName()

    @TaskAction
    void verifyArchive() {
        File archive = productionJar.get().asFile
        List<String> errors = []
        if (archive.name != expectedFileName.get()) {
            errors << "expected artifact filename '${expectedFileName.get()}', found '${archive.name}'"
        }
        if (!archive.isFile()) {
            throw new GradleException("Fabric production JAR audit failed: artifact does not exist: ${archive}")
        }

        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)
        try {
            List<java.util.zip.ZipEntry> entries = java.util.Collections.list(zip.entries())
            Map<String, Integer> entryCounts = new TreeMap<>()
            entries.each { entry ->
                entryCounts[entry.name] = (entryCounts[entry.name] ?: 0) + 1
            }
            entryCounts.findAll { name, count -> count != 1 }.each { name, count ->
                errors << "duplicate ZIP entry '${name}' occurs ${count} times"
            }
            VerifyProductionJar.requireExactlyOnce(
                    'required audio provider class',
                    new TreeSet<>(requiredProviderEntries.get()),
                    entryCounts,
                    errors
            )
            VerifyProductionJar.requireExactlyOnce(
                    'main entrypoint class',
                    [entrypointClassEntry(expectedMainEntrypoint.get())] as Set<String>,
                    entryCounts,
                    errors
            )
            VerifyProductionJar.requireExactlyOnce(
                    'client entrypoint class',
                    [entrypointClassEntry(expectedClientEntrypoint.get())] as Set<String>,
                    entryCounts,
                    errors
            )
            Set<String> archiveEntryNames = new TreeSet<>(entries.findAll {
                !it.directory
            }.collect { it.name })
            List<File> curatedServiceSources = new ArrayList<>(serviceSourceFiles.files)
            curatedServiceSources.sort { left, right ->
                left.absolutePath <=> right.absolutePath
            }
            if (curatedServiceSources.size() != 2) {
                errors << "expected exactly two curated service source files, found ${curatedServiceSources}"
            }
            Set<String> expectedServiceEntries = new TreeSet<>(curatedServiceSources.collect {
                "META-INF/services/${it.name}" as String
            })
            Set<String> actualServiceEntries = new TreeSet<>(archiveEntryNames.findAll {
                it.startsWith('META-INF/services/')
            })
            if (actualServiceEntries != expectedServiceEntries) {
                errors << "service entries differ from the curated set; expected ${expectedServiceEntries}, found ${actualServiceEntries}"
            }
            curatedServiceSources.each { source ->
                String entryName = "META-INF/services/${source.name}"
                byte[] archivedBytes = VerifyProductionJar.readEntry(zip, entryName)
                if (archivedBytes != null && !java.util.Arrays.equals(
                        archivedBytes, java.nio.file.Files.readAllBytes(source.toPath())
                )) {
                    errors << "service descriptor '${entryName}' is not byte-identical to ${source}"
                }
            }
            String fabricMetadata = VerifyProductionJar.readUtf8Entry(zip, 'fabric.mod.json')
            Object parsedFabricMetadata = null
            if (fabricMetadata != null) {
                try {
                    VerifyProductionJar.validateStrictJson(fabricMetadata)
                    parsedFabricMetadata = new JsonSlurper().parseText(fabricMetadata)
                } catch (Exception parseFailure) {
                    errors << "fabric.mod.json is not valid JSON: ${parseFailure.message}"
                }
            }
            if (parsedFabricMetadata instanceof Map) {
                Map<?, ?> metadata = (Map<?, ?>) parsedFabricMetadata
                if (!VerifyProductionJar.isIntegralNumberEqualTo(
                        metadata.get('schemaVersion'), 1
                )) {
                    errors << "fabric.mod.json schemaVersion must be the integral number 1, found '${metadata.get('schemaVersion')}'"
                }
                [
                        id         : expectedModId.get(),
                        version    : expectedModVersion.get(),
                        name       : expectedModName.get(),
                        description: expectedModDescription.get(),
                        license    : expectedModLicense.get(),
                        environment: '*'
                ].each { field, expected ->
                    if (metadata.get(field) != expected) {
                        errors << "fabric.mod.json ${field} must be '${expected}', found '${metadata.get(field)}'"
                    }
                }
                List<String> expectedAuthors = [expectedModAuthors.get()]
                if (metadata.get('authors') != expectedAuthors) {
                    errors << "fabric.mod.json authors must be exactly ${expectedAuthors}, found ${metadata.get('authors')}"
                }

                Object entrypointsValue = metadata.get('entrypoints')
                if (!(entrypointsValue instanceof Map)) {
                    errors << 'fabric.mod.json entrypoints must be an object'
                } else {
                    Map<?, ?> entrypoints = (Map<?, ?>) entrypointsValue
                    if (entrypoints.keySet() as Set != ['main', 'client'] as Set) {
                        errors << "fabric.mod.json entrypoints must contain exactly main and client, found ${entrypoints.keySet()}"
                    }
                    if (entrypoints.get('main') != [expectedMainEntrypoint.get()]) {
                        errors << "fabric.mod.json main entrypoint must be exactly '${expectedMainEntrypoint.get()}', found '${entrypoints.get('main')}'"
                    }
                    if (entrypoints.get('client') != [expectedClientEntrypoint.get()]) {
                        errors << "fabric.mod.json client entrypoint must be exactly '${expectedClientEntrypoint.get()}', found '${entrypoints.get('client')}'"
                    }
                }
                Object dependsValue = metadata.get('depends')
                if (!(dependsValue instanceof Map)) {
                    errors << 'fabric.mod.json depends must be an object'
                } else {
                    Map<?, ?> depends = (Map<?, ?>) dependsValue
                    String expectedLoaderConstraint = ">=${expectedLoaderVersion.get()}"
                    String expectedFabricApiConstraint =
                            ">=${expectedFabricApiVersion.get()}"
                    if (depends.get('fabricloader') != expectedLoaderConstraint) {
                        errors << "fabricloader dependency must be '${expectedLoaderConstraint}', found '${depends.get('fabricloader')}'"
                    }
                    [
                            minecraft   : expectedMinecraftVersion.get(),
                            java        : ">=${expectedJavaVersion.get()}",
                            'fabric-api': expectedFabricApiConstraint
                    ].each { dependency, expectedConstraint ->
                        if (depends.get(dependency) != expectedConstraint) {
                            errors << "${dependency} dependency must be '${expectedConstraint}', found '${depends.get(dependency)}'"
                        }
                    }
                    Set<String> expectedDependencyKeys = [
                            'fabricloader', 'minecraft', 'java', 'fabric-api'
                    ] as Set
                    if (depends.keySet() as Set != expectedDependencyKeys) {
                        errors << "fabric.mod.json depends must contain exactly ${expectedDependencyKeys}, found ${depends.keySet()}"
                    }
                    if (depends.containsKey('architectury')) {
                        errors << 'fabric.mod.json must not declare Architectury'
                    }
                }
            } else if (parsedFabricMetadata != null) {
                errors << 'fabric.mod.json must be a JSON object'
            }
            String packMetadata = VerifyProductionJar.readUtf8Entry(zip, 'pack.mcmeta')
            Object parsedPackMetadata = null
            if (packMetadata != null) {
                try {
                    VerifyProductionJar.validateStrictJson(packMetadata)
                    parsedPackMetadata = new JsonSlurper().parseText(packMetadata)
                } catch (Exception parseFailure) {
                    errors << "pack.mcmeta is not valid JSON: ${parseFailure.message}"
                }
            }
            if (parsedPackMetadata instanceof Map) {
                Object packValue = ((Map<?, ?>) parsedPackMetadata).get('pack')
                if (!(packValue instanceof Map)) {
                    errors << 'pack.mcmeta must contain a pack object'
                } else {
                    Map<?, ?> pack = (Map<?, ?>) packValue
                    String expectedDescription = "${expectedModId.get()} resources"
                    if (pack.get('description') != expectedDescription) {
                        errors << "pack.mcmeta pack.description must be '${expectedDescription}', found '${pack.get('description')}'"
                    }
                    if (!VerifyProductionJar.isIntegralNumberEqualTo(
                            pack.get('pack_format'), expectedPackFormat.get()
                    )) {
                        errors << "pack.mcmeta pack_format must be the integral number ${expectedPackFormat.get()}, found '${pack.get('pack_format')}'"
                    }
                }
            } else if (parsedPackMetadata != null) {
                errors << 'pack.mcmeta must be a JSON object'
            }
            ['en_us', 'zh_cn'].each { locale ->
                String language = VerifyProductionJar.readUtf8Entry(
                        zip, "assets/${expectedModId.get()}/lang/${locale}.json"
                )
                if (language != null && !language.contains(".${expectedModId.get()}.")) {
                    errors << "${locale}.json does not contain ${expectedModId.get()} translation keys"
                }
            }
            byte[] manifestBytes = VerifyProductionJar.readEntry(zip, 'META-INF/MANIFEST.MF')
            if (manifestBytes != null) {
                java.util.jar.Manifest manifest = new java.util.jar.Manifest(
                        new ByteArrayInputStream(manifestBytes)
                )
                java.util.jar.Attributes attributes = manifest.mainAttributes
                VerifyProductionJar.requireAttribute(
                        attributes, 'Specification-Title', expectedModId.get(), errors
                )
                VerifyProductionJar.requireAttribute(
                        attributes,
                        'Implementation-Title',
                        expectedImplementationTitle.get(),
                        errors
                )
                VerifyProductionJar.requireAttribute(
                        attributes,
                        'Implementation-Version',
                        expectedModVersion.get(),
                        errors
                )
                VerifyProductionJar.requireAttribute(
                        attributes,
                        'Fabric-Minecraft-Version',
                        expectedMinecraftVersion.get(),
                        errors
                )
                if (attributes.getValue('Implementation-Timestamp') != null) {
                    errors << 'manifest must not contain wall-clock Implementation-Timestamp'
                }
            }
            requiredResourceEntries.get().findAll {
                it != 'META-INF/MANIFEST.MF'
            }.each { entryName ->
                byte[] resourceBytes = VerifyProductionJar.readEntry(zip, entryName)
                if (resourceBytes != null) {
                    String resourceText = new String(
                            resourceBytes, java.nio.charset.StandardCharsets.UTF_8
                    )
                    if (resourceText =~ /\$\{[^}]+}/) {
                        errors << "resource '${entryName}' contains an unexpanded template token"
                    }
                }
            }
            Set<String> codecEntries = VerifyProductionJar.relativeFileNames(
                    [codecDirectory.get().asFile] as Set<File>, false
            )
            Set<String> expectedResourceEntries = new TreeSet<>(codecEntries.findAll {
                !it.endsWith('.class')
            })
            expectedResourceEntries.addAll(requiredResourceEntries.get())
            Set<String> actualResourceEntries = new TreeSet<>(archiveEntryNames.findAll {
                !it.endsWith('.class')
            })
            Set<String> missingResourceEntries = new TreeSet<>(expectedResourceEntries)
            missingResourceEntries.removeAll(actualResourceEntries)
            if (!missingResourceEntries.isEmpty()) {
                errors << "missing production resources: ${missingResourceEntries}"
            }
            Set<String> unexpectedResourceEntries = new TreeSet<>(actualResourceEntries)
            unexpectedResourceEntries.removeAll(expectedResourceEntries)
            if (!unexpectedResourceEntries.isEmpty()) {
                errors << "unexpected production resources: ${unexpectedResourceEntries}"
            }
            Set<String> actualClassEntries = new TreeSet<>(
                    entries.findAll {
                        !it.directory && it.name.endsWith('.class')
                    }.collect { it.name }
            )
            Set<String> nestedJarEntries = new TreeSet<>(entries.findAll {
                !it.directory && it.name.toLowerCase(Locale.ROOT).endsWith('.jar')
            }.collect { it.name })
            if (!nestedJarEntries.isEmpty()) {
                errors << "nested JAR entries are forbidden: ${nestedJarEntries}"
            }
            Set<String> forbiddenPlatformClasses = new TreeSet<>(actualClassEntries.findAll {
                it.startsWith('net/fabricmc/loader/') ||
                        it.startsWith('net/fabricmc/fabric/') ||
                        it.startsWith('net/minecraftforge/') ||
                        it.startsWith('net/neoforged/') ||
                        it.startsWith('dev/architectury/')
            })
            if (!forbiddenPlatformClasses.isEmpty()) {
                errors << "loader/platform dependency classes are forbidden: ${forbiddenPlatformClasses}"
            }
            Set<String> testOutputEntries = VerifyProductionJar.relativeFileNames(
                    testOutputRoots.files, false
            )
            if (testOutputEntries.isEmpty()) {
                errors << 'no compiled test outputs/resources were found; leakage detection would be vacuous'
            }
            [
                    core  : coreTestOutputRoots,
                    common: commonTestOutputRoots,
                    fabric: fabricTestOutputRoots
            ].each { category, roots ->
                if (VerifyProductionJar.relativeFileNames(roots.files, false).isEmpty()) {
                    errors << "no ${category} compiled test outputs/resources were found; leakage detection would be vacuous"
                }
            }
            Set<String> coreClassEntries = VerifyProductionJar.relativeFileNames(
                    coreMainClassRoots.files, true
            )
            Set<String> commonClassEntries = VerifyProductionJar.relativeFileNames(
                    commonMainClassRoots.files, true
            )
            Set<String> fabricClassEntries = VerifyProductionJar.relativeFileNames(
                    fabricMainClassRoots.files, true
            )
            if (coreClassEntries.isEmpty()) {
                errors << "no core main classes were found in ${coreMainClassRoots.files}"
            }
            if (commonClassEntries.isEmpty()) {
                errors << "no common main classes were found in ${commonMainClassRoots.files}"
            }
            if (fabricClassEntries.isEmpty()) {
                errors << "no Fabric main classes were found in ${fabricMainClassRoots.files}"
            }
            Set<String> codecClassEntries = new TreeSet<>(codecEntries.findAll {
                it.endsWith('.class')
            })

            [
                    'core/common'  : VerifyProductionJar.intersection(
                            coreClassEntries, commonClassEntries
                    ),
                    'core/Fabric'  : VerifyProductionJar.intersection(
                            coreClassEntries, fabricClassEntries
                    ),
                    'core/codec'   : VerifyProductionJar.intersection(
                            coreClassEntries, codecClassEntries
                    ),
                    'common/Fabric': VerifyProductionJar.intersection(
                            commonClassEntries, fabricClassEntries
                    ),
                    'common/codec' : VerifyProductionJar.intersection(
                            commonClassEntries, codecClassEntries
                    ),
                    'Fabric/codec' : VerifyProductionJar.intersection(
                            fabricClassEntries, codecClassEntries
                    )
            ].each { label, overlap ->
                if (!overlap.isEmpty()) {
                    errors << "${label} class overlap: ${overlap}"
                }
            }

            Set<String> expectedClassEntries = new TreeSet<>()
            expectedClassEntries.addAll(coreClassEntries)
            expectedClassEntries.addAll(commonClassEntries)
            expectedClassEntries.addAll(fabricClassEntries)
            expectedClassEntries.addAll(codecClassEntries)
            Set<String> expectedProductionEntries = new TreeSet<>(expectedClassEntries)
            expectedProductionEntries.addAll(expectedResourceEntries)

            Set<String> missingClassEntries = new TreeSet<>(expectedClassEntries)
            missingClassEntries.removeAll(actualClassEntries)
            if (!missingClassEntries.isEmpty()) {
                errors << "missing production classes: ${missingClassEntries}"
            }
            Set<String> unexpectedClassEntries = new TreeSet<>(actualClassEntries)
            unexpectedClassEntries.removeAll(expectedClassEntries)
            if (!unexpectedClassEntries.isEmpty()) {
                errors << "unexpected production classes: ${unexpectedClassEntries}"
            }
            Set<String> projectClassEntries = new TreeSet<>()
            projectClassEntries.addAll(coreClassEntries)
            projectClassEntries.addAll(commonClassEntries)
            projectClassEntries.addAll(fabricClassEntries)
            Set<String> exclusivelyTestOutputEntries = new TreeSet<>(testOutputEntries)
            exclusivelyTestOutputEntries.removeAll(expectedProductionEntries)
            Set<String> leakedTestOutputs = VerifyProductionJar.intersection(
                    exclusivelyTestOutputEntries, archiveEntryNames
            )
            if (!leakedTestOutputs.isEmpty()) {
                errors << "test outputs/resources leaked into the production JAR: ${leakedTestOutputs}"
            }
            projectClassEntries.each { entryName ->
                byte[] classBytes = VerifyProductionJar.readEntry(zip, entryName)
                if (classBytes != null) {
                    if (classBytes.length < 8) {
                        errors << "project class '${entryName}' is too short to contain a class-file header"
                    } else {
                        int major = ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF)
                        if (major != expectedProjectClassMajor.get()) {
                            errors << "project class '${entryName}' must use class-file major ${expectedProjectClassMajor.get()}, found ${major}"
                        }
                    }
                }
            }
            String evidenceEntry = remappingEvidenceEntry.get()
            byte[] productionEvidence = VerifyProductionJar.readEntry(zip, evidenceEntry)
            File developmentEvidence = VerifyProductionJar.findRelativeFile(
                    commonMainClassRoots.files, evidenceEntry
            )
            if (productionEvidence == null) {
                errors << "remapping evidence class is missing: ${evidenceEntry}"
            } else if (developmentEvidence == null) {
                errors << "common development class for remapping comparison is missing: ${evidenceEntry}"
            } else if (java.util.Arrays.equals(
                    productionEvidence,
                    java.nio.file.Files.readAllBytes(developmentEvidence.toPath())
            )) {
                errors << "production class '${evidenceEntry}' is byte-identical to the Mojang-named development class"
            }
            if (productionEvidence != null) {
                String classConstants = new String(
                        productionEvidence, java.nio.charset.StandardCharsets.ISO_8859_1
                )
                if (!classConstants.contains(expectedIntermediaryName.get())) {
                    errors << "production class '${evidenceEntry}' lacks expected intermediary symbol '${expectedIntermediaryName.get()}'"
                }
                if (classConstants.contains(forbiddenMojangName.get())) {
                    errors << "production class '${evidenceEntry}' still contains Mojang symbol '${forbiddenMojangName.get()}'"
                }
            }
            if (!errors.isEmpty()) {
                throw new GradleException(
                        "Fabric production JAR audit failed for ${archive}:\n - ${errors.join('\n - ')}"
                )
            }
        } finally {
            zip.close()
        }
    }

    private static String entrypointClassEntry(String entrypoint) {
        return entrypoint.replace('.', '/') + '.class'
    }
}
