package datura.gradle

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadFeature
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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ConstantDynamic
import org.objectweb.asm.Handle
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

abstract class VerifyProductionJar extends DefaultTask {
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
    abstract ConfigurableFileCollection getForgeMainClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getNeoforgeMainClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestOutputRoots()

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
    abstract Property<String> getExpectedImplementationTitle()

    @Input
    abstract Property<Integer> getExpectedPackFormat()

    @Input
    abstract Property<Integer> getExpectedProjectClassMajor()

    @Input
    abstract Property<String> getMetadataEntry()

    @Input
    abstract ListProperty<String> getRequiredResourceEntries()

    @Input
    abstract ListProperty<String> getRequiredProviderEntries()

    @Input
    abstract Property<Boolean> getVerifyReobfuscation()

    @Input
    @Optional
    abstract Property<String> getNamingEvidenceEntry()

    @Input
    @Optional
    abstract Property<String> getExpectedSrgName()

    @Input
    @Optional
    abstract Property<String> getForbiddenMojangName()

    @Input
    @Optional
    abstract Property<String> getExpectedMojangName()

    @Input
    @Optional
    abstract Property<String> getForbiddenSrgName()

    @Input
    @Optional
    abstract Property<String> getNamingEvidenceMethodOwner()

    @Input
    @Optional
    abstract Property<String> getNamingEvidenceMethodDescriptor()

    VerifyProductionJar() {
        metadataEntry.convention('META-INF/mods.toml')
    }

    @TaskAction
    void verifyArchive() {
        File archive = productionJar.get().asFile
        List<String> errors = []
        String verifiedNamingKind = null
        String verifiedNamingEntry = null
        String verifiedExpectedName = null
        String verifiedForbiddenName = null

        if (archive.name != expectedFileName.get()) {
            errors << "expected artifact filename '${expectedFileName.get()}', found '${archive.name}'"
        }
        if (!archive.isFile()) {
            throw new GradleException("Production JAR audit failed: artifact does not exist: ${archive}")
        }

        java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive)
        try {
            List<java.util.zip.ZipEntry> entries = java.util.Collections.list(zip.entries())
            Map<String, Integer> counts = new TreeMap<>()
            entries.each { entry ->
                counts[entry.name] = (counts[entry.name] ?: 0) + 1
            }
            Set<String> actualClassEntries = new TreeSet<>(entries.findAll {
                !it.directory && it.name.endsWith('.class')
            }.collect { it.name })

            counts.findAll { name, count -> count != 1 }.each { name, count ->
                errors << "duplicate ZIP entry '${name}' occurs ${count} times"
            }

            Set<String> nestedJars = counts.keySet().findAll {
                it.toLowerCase(Locale.ROOT).endsWith('.jar')
            }
            if (!nestedJars.isEmpty()) {
                errors << "nested JAR entries are forbidden: ${nestedJars}"
            }

            Set<String> codecEntries = VerifyProductionJar.relativeFileNames(
                    [codecDirectory.get().asFile] as Set<File>,
                    false
            )
            if (codecEntries.isEmpty()) {
                errors << "staged codec directory is empty: ${codecDirectory.get().asFile}"
            }
            VerifyProductionJar.requireExactlyOnce('staged codec', codecEntries, counts, errors)
            Set<String> codecClassEntries = new TreeSet<>(codecEntries.findAll {
                it.endsWith('.class')
            })
            if (codecClassEntries.isEmpty()) {
                errors << "no staged codec classes were found in ${codecDirectory.get().asFile}"
            }

            Set<String> coreClassEntries = VerifyProductionJar.relativeFileNames(coreMainClassRoots.files, true)
            if (coreClassEntries.isEmpty()) {
                errors << "no core main classes were found in ${coreMainClassRoots.files}"
            }
            VerifyProductionJar.requireExactlyOnce('core main class', coreClassEntries, counts, errors)

            Set<String> commonClassEntries = VerifyProductionJar.relativeFileNames(commonMainClassRoots.files, true)
            if (commonClassEntries.isEmpty()) {
                errors << "no common main classes were found in ${commonMainClassRoots.files}"
            }
            VerifyProductionJar.requireExactlyOnce('common main class', commonClassEntries, counts, errors)

            Set<String> forgeClassEntries = VerifyProductionJar.relativeFileNames(forgeMainClassRoots.files, true)
            Set<String> neoforgeClassEntries = VerifyProductionJar.relativeFileNames(
                    neoforgeMainClassRoots.files,
                    true
            )
            if (forgeClassEntries.isEmpty() && neoforgeClassEntries.isEmpty()) {
                errors << "no Forge main classes were found in ${forgeMainClassRoots.files}"
            }
            VerifyProductionJar.requireExactlyOnce('Forge main class', forgeClassEntries, counts, errors)
            VerifyProductionJar.requireExactlyOnce('NeoForge main class', neoforgeClassEntries, counts, errors)

            Set<String> platformClassEntries = new TreeSet<>(forgeClassEntries)
            platformClassEntries.addAll(neoforgeClassEntries)

            boolean nativeNeoForgeJar = !neoforgeClassEntries.isEmpty()
            Map<String, List<String>> forbiddenCategories =
                    VerifyProductionJar.forbiddenClassCategories(nativeNeoForgeJar)
            forbiddenCategories.each { category, prefixes ->
                Set<String> forbiddenEntries = new TreeSet<>(counts.keySet().findAll { entryName ->
                    prefixes.any { prefix -> entryName.startsWith(prefix) }
                })
                if (!forbiddenEntries.isEmpty()) {
                    errors << "forbidden ${category} entries: ${forbiddenEntries}"
                }
            }
            if (nativeNeoForgeJar) {
                Set<String> testLikeEntries = new TreeSet<>(counts.keySet().findAll {
                    VerifyProductionJar.isTestFixtureClassEntry(it)
                })
                if (!testLikeEntries.isEmpty()) {
                    errors << "test fixture/Test class entries are forbidden: ${testLikeEntries}"
                }
            }

            Set<String> coreForgeOverlap = VerifyProductionJar.intersection(
                    coreClassEntries, forgeClassEntries
            )
            Set<String> coreCommonOverlap = VerifyProductionJar.intersection(
                    coreClassEntries, commonClassEntries
            )
            Set<String> coreCodecOverlap = VerifyProductionJar.intersection(
                    coreClassEntries, codecClassEntries
            )
            Set<String> commonForgeOverlap = VerifyProductionJar.intersection(
                    commonClassEntries, forgeClassEntries
            )
            Set<String> commonCodecOverlap = VerifyProductionJar.intersection(
                    commonClassEntries, codecClassEntries
            )
            Set<String> forgeCodecOverlap = VerifyProductionJar.intersection(
                    forgeClassEntries, codecClassEntries
            )
            Set<String> neoforgeCommonOverlap = VerifyProductionJar.intersection(
                    neoforgeClassEntries, commonClassEntries
            )
            Set<String> neoforgeCoreOverlap = VerifyProductionJar.intersection(
                    neoforgeClassEntries, coreClassEntries
            )
            Set<String> neoforgeCodecOverlap = VerifyProductionJar.intersection(
                    neoforgeClassEntries, codecClassEntries
            )
            Set<String> forgeNeoforgeOverlap = VerifyProductionJar.intersection(
                    forgeClassEntries, neoforgeClassEntries
            )
            if (!coreCommonOverlap.isEmpty()) {
                errors << "core/common class overlap: ${coreCommonOverlap}"
            }
            if (!coreForgeOverlap.isEmpty()) {
                errors << "core/Forge class overlap: ${coreForgeOverlap}"
            }
            if (!coreCodecOverlap.isEmpty()) {
                errors << "core/codec class overlap: ${coreCodecOverlap}"
            }
            if (!commonForgeOverlap.isEmpty()) {
                errors << "common/Forge class overlap: ${commonForgeOverlap}"
            }
            if (!commonCodecOverlap.isEmpty()) {
                errors << "common/codec class overlap: ${commonCodecOverlap}"
            }
            if (!forgeCodecOverlap.isEmpty()) {
                errors << "Forge/codec class overlap: ${forgeCodecOverlap}"
            }
            if (!neoforgeCommonOverlap.isEmpty()) {
                errors << "NeoForge/common class overlap: ${neoforgeCommonOverlap}"
            }
            if (!neoforgeCoreOverlap.isEmpty()) {
                errors << "NeoForge/core class overlap: ${neoforgeCoreOverlap}"
            }
            if (!neoforgeCodecOverlap.isEmpty()) {
                errors << "NeoForge/codec class overlap: ${neoforgeCodecOverlap}"
            }
            if (!forgeNeoforgeOverlap.isEmpty()) {
                errors << "Forge/NeoForge class overlap: ${forgeNeoforgeOverlap}"
            }

            Set<String> expectedClassEntries = new TreeSet<>()
            expectedClassEntries.addAll(coreClassEntries)
            expectedClassEntries.addAll(commonClassEntries)
            expectedClassEntries.addAll(platformClassEntries)
            expectedClassEntries.addAll(codecClassEntries)
            Set<String> missingClassEntries = new TreeSet<>(expectedClassEntries)
            missingClassEntries.removeAll(actualClassEntries)
            Set<String> unexpectedClassEntries = new TreeSet<>(actualClassEntries)
            unexpectedClassEntries.removeAll(expectedClassEntries)
            if (!missingClassEntries.isEmpty()) {
                errors << "missing production classes: ${missingClassEntries}"
            }
            if (!unexpectedClassEntries.isEmpty()) {
                errors << "unexpected production classes: ${unexpectedClassEntries}"
            }

            VerifyProductionJar.requireExactlyOnce(
                    'required audio provider class',
                    new TreeSet<>(requiredProviderEntries.get()),
                    counts,
                    errors
            )

            Set<String> testOutputEntries = VerifyProductionJar.relativeFileNames(testOutputRoots.files, false)
            if (testOutputEntries.isEmpty()) {
                errors << "no compiled test outputs/resources were found; leakage detection would be vacuous"
            }

            Set<File> serviceSources = new TreeSet<>({ File left, File right ->
                left.absolutePath <=> right.absolutePath
            } as Comparator<File>)
            serviceSources.addAll(serviceSourceFiles.files)
            if (serviceSources.size() != 2) {
                errors << "expected exactly two curated service source files, found ${serviceSources}"
            }
            Set<String> expectedServiceEntries = new TreeSet<>(serviceSources.collect {
                "META-INF/services/${it.name}" as String
            })
            Set<String> actualServiceEntries = new TreeSet<>(counts.keySet().findAll {
                it.startsWith('META-INF/services/') && !it.endsWith('/')
            })
            if (actualServiceEntries != expectedServiceEntries) {
                errors << "service entries differ from the curated set; expected ${expectedServiceEntries}, found ${actualServiceEntries}"
            }
            VerifyProductionJar.requireExactlyOnce('curated service descriptor', expectedServiceEntries, counts, errors)
            serviceSources.each { source ->
                String entryName = "META-INF/services/${source.name}"
                byte[] archivedBytes = VerifyProductionJar.readEntry(zip, entryName)
                if (archivedBytes != null && !java.util.Arrays.equals(archivedBytes, java.nio.file.Files.readAllBytes(source.toPath()))) {
                    errors << "service descriptor '${entryName}' is not byte-identical to ${source}"
                }
            }

            Set<String> requiredResources = new TreeSet<>(requiredResourceEntries.get())
            VerifyProductionJar.requireExactlyOnce('required resource', requiredResources, counts, errors)
            requiredResources.each { entryName ->
                byte[] bytes = VerifyProductionJar.readEntry(zip, entryName)
                if (bytes != null && bytes.length == 0) {
                    errors << "required resource '${entryName}' is empty"
                }
            }

            Set<String> expectedResourceEntries = new TreeSet<>(codecEntries.findAll {
                !it.endsWith('.class')
            })
            expectedResourceEntries.addAll(requiredResources)
            Set<String> expectedProductionEntries = new TreeSet<>(expectedClassEntries)
            expectedProductionEntries.addAll(expectedResourceEntries)
            Set<String> exclusivelyTestOutputEntries = new TreeSet<>(testOutputEntries)
            exclusivelyTestOutputEntries.removeAll(expectedProductionEntries)
            Set<String> leakedTestOutputs = VerifyProductionJar.intersection(
                    new TreeSet<>(counts.keySet()),
                    exclusivelyTestOutputEntries
            )
            if (!leakedTestOutputs.isEmpty()) {
                errors << "test outputs/resources leaked into the production JAR: ${leakedTestOutputs}"
            }
            Set<String> actualResourceEntries = new TreeSet<>(entries.findAll {
                !it.directory && !it.name.endsWith('.class')
            }.collect { it.name })
            Set<String> missingResourceEntries = new TreeSet<>(expectedResourceEntries)
            missingResourceEntries.removeAll(actualResourceEntries)
            Set<String> unexpectedResourceEntries = new TreeSet<>(actualResourceEntries)
            unexpectedResourceEntries.removeAll(expectedResourceEntries)
            if (!missingResourceEntries.isEmpty()) {
                errors << "missing production resources: ${missingResourceEntries}"
            }
            if (!unexpectedResourceEntries.isEmpty()) {
                errors << "unexpected production resources: ${unexpectedResourceEntries}"
            }

            Set<String> projectClassEntries = new TreeSet<>()
            projectClassEntries.addAll(coreClassEntries)
            projectClassEntries.addAll(commonClassEntries)
            projectClassEntries.addAll(forgeClassEntries)
            projectClassEntries.addAll(neoforgeClassEntries)
            Map<String, byte[]> projectClassBytes = new TreeMap<>()
            projectClassEntries.each { entryName ->
                byte[] classBytes = VerifyProductionJar.readEntry(zip, entryName)
                if (classBytes != null) {
                    String internalName = entryName.substring(0, entryName.length() - '.class'.length())
                    projectClassBytes[internalName] = classBytes
                    if (classBytes.length < 8) {
                        errors << "project class '${entryName}' is too short to contain a class-file header"
                    } else {
                        int major = ((classBytes[6] & 0xFF) << 8) | (classBytes[7] & 0xFF)
                        if (major != expectedProjectClassMajor.get()) {
                            errors << "project class '${entryName}' must use class-file major ${expectedProjectClassMajor.get()}, found ${major}"
                        }
                    }
                    try {
                        Set<String> references = FinalJarClassGraph.references(classBytes)
                        forbiddenCategories.each { category, prefixes ->
                            Set<String> forbiddenReferences = new TreeSet<>(references.findAll { reference ->
                                prefixes.any { prefix -> reference.startsWith(prefix) }
                            })
                            if (!forbiddenReferences.isEmpty()) {
                                errors << "project class '${entryName}' has forbidden ${category} references: ${forbiddenReferences}"
                            }
                        }
                        if (nativeNeoForgeJar) {
                            Set<String> testLikeReferences = new TreeSet<>(references.findAll {
                                VerifyProductionJar.isTestFixtureInternalName(it)
                            })
                            if (!testLikeReferences.isEmpty()) {
                                errors << "project class '${entryName}' has forbidden test fixture/Test class references: ${testLikeReferences}"
                            }
                        }
                    } catch (RuntimeException invalidClass) {
                        errors << "project class '${entryName}' is not a valid class file: ${invalidClass.message}"
                    }
                }
            }

            if (nativeNeoForgeJar) {
                errors.addAll(FinalJarClassGraph.verifyDedicatedServerReachability(
                        projectClassBytes,
                        [
                                'datura/areamusic/AreaMusic',
                                'datura/areamusic/server/NeoForgeAreaMusicServer'
                        ],
                        [
                                'net/minecraft/client/',
                                'com/mojang/blaze3d/',
                                'net/neoforged/neoforge/client/',
                                'datura/areamusic/client/'
                        ]
                ))
            }

            Set<String> projectTextResources = requiredResources.findAll {
                it != 'META-INF/MANIFEST.MF'
            }
            projectTextResources.each { entryName ->
                byte[] bytes = VerifyProductionJar.readEntry(zip, entryName)
                if (bytes != null) {
                    String text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                    if (text =~ /\$\{[^}]+}/) {
                        errors << "resource '${entryName}' contains an unexpanded template token"
                    }
                }
            }

            byte[] manifestBytes = VerifyProductionJar.readEntry(zip, 'META-INF/MANIFEST.MF')
            if (manifestBytes != null) {
                java.util.jar.Manifest manifest = new java.util.jar.Manifest(new ByteArrayInputStream(manifestBytes))
                java.util.jar.Attributes attributes = manifest.mainAttributes
                VerifyProductionJar.requireAttribute(
                        attributes, 'Specification-Title', expectedModId.get(), errors
                )
                VerifyProductionJar.requireAttribute(
                        attributes, 'Implementation-Title', expectedImplementationTitle.get(), errors
                )
                VerifyProductionJar.requireAttribute(
                        attributes, 'Implementation-Version', expectedModVersion.get(), errors
                )
                if (attributes.getValue('Implementation-Timestamp') != null) {
                    errors << "manifest must not contain wall-clock Implementation-Timestamp"
                }
            }

            String modsToml = VerifyProductionJar.readUtf8Entry(zip, metadataEntry.get())
            if (modsToml != null) {
                String quotedModId = java.util.regex.Pattern.quote(expectedModId.get())
                String quotedVersion = java.util.regex.Pattern.quote(expectedModVersion.get())
                String modIdPattern = '(?m)^\\s*modId\\s*=\\s*"' + quotedModId + '"\\s*$'
                if (VerifyProductionJar.matchCount(modsToml, modIdPattern) != 1) {
                    errors << "${metadataEntry.get()} must contain exactly one primary modId = '${expectedModId.get()}'"
                }
                String versionPattern = '(?m)^\\s*version\\s*=\\s*"' + quotedVersion + '"\\s*$'
                if (VerifyProductionJar.matchCount(modsToml, versionPattern) != 1) {
                    errors << "${metadataEntry.get()} must contain exactly one version = '${expectedModVersion.get()}'"
                }
            }

            String packMetadata = VerifyProductionJar.readUtf8Entry(zip, 'pack.mcmeta')
            if (packMetadata != null) {
                Object parsedMetadata = null
                boolean validJson = true
                try {
                    VerifyProductionJar.validateStrictJson(packMetadata)
                    parsedMetadata = new JsonSlurper().parseText(packMetadata)
                } catch (Exception parseFailure) {
                    validJson = false
                    errors << "pack.mcmeta is not valid JSON: ${parseFailure.message}"
                }
                if (validJson) {
                    if (!(parsedMetadata instanceof Map)) {
                        errors << 'pack.mcmeta must be a JSON object'
                    } else {
                        Object packValue = ((Map<?, ?>) parsedMetadata).get('pack')
                        if (!(packValue instanceof Map)) {
                            errors << 'pack.mcmeta must contain a pack object'
                        } else {
                            Map<?, ?> pack = (Map<?, ?>) packValue
                            String expectedDescription = "${expectedModId.get()} resources"
                            Object description = pack.get('description')
                            if (description != expectedDescription) {
                                errors << "pack.mcmeta pack.description must be '${expectedDescription}', found '${description}'"
                            }
                            Object packFormat = pack.get('pack_format')
                            if (!VerifyProductionJar.isIntegralNumberEqualTo(
                                    packFormat, expectedPackFormat.get()
                            )) {
                                errors << "pack.mcmeta pack_format must be the integral number ${expectedPackFormat.get()}, found '${packFormat}'"
                            }
                        }
                    }
                }
            }

            ['en_us', 'zh_cn'].each { locale ->
                String lang = VerifyProductionJar.readUtf8Entry(
                        zip, "assets/${expectedModId.get()}/lang/${locale}.json"
                )
                if (lang != null && !lang.contains(".${expectedModId.get()}.")) {
                    errors << "${locale}.json does not contain ${expectedModId.get()} translation keys"
                }
            }

            String evidenceEntry = namingEvidenceEntry.getOrNull()
            String evidenceMethodOwner = namingEvidenceMethodOwner.getOrNull()
            String evidenceMethodDescriptor = namingEvidenceMethodDescriptor.getOrNull()
            if (evidenceEntry == null) {
                errors << 'naming evidence entry is not configured'
            }
            if (evidenceMethodOwner == null) {
                errors << 'naming evidence method owner is not configured'
            }
            if (evidenceMethodDescriptor == null) {
                errors << 'naming evidence method descriptor is not configured'
            }
            if (verifyReobfuscation.get()) {
                String expectedName = expectedSrgName.getOrNull()
                String forbiddenName = forbiddenMojangName.getOrNull()
                if (expectedName == null) {
                    errors << 'expected SRG symbol is not configured'
                }
                if (forbiddenName == null) {
                    errors << 'forbidden Mojang symbol is not configured'
                }
                if (evidenceEntry != null) {
                    byte[] productionClass = VerifyProductionJar.readEntry(zip, evidenceEntry)
                    File developmentClass = VerifyProductionJar.findRelativeFile(
                            commonMainClassRoots.files, evidenceEntry
                    )
                    if (productionClass == null) {
                        errors << "reobfuscation evidence class is missing: ${evidenceEntry}"
                    } else if (developmentClass == null) {
                        errors << "common development class for reobfuscation comparison is missing: ${evidenceEntry}"
                    } else {
                        byte[] developmentBytes = java.nio.file.Files.readAllBytes(developmentClass.toPath())
                        if (java.util.Arrays.equals(productionClass, developmentBytes)) {
                            errors << "production class '${evidenceEntry}' is byte-identical to the Mojang-named development class"
                        }
                        Set<String> methodReferences = VerifyProductionJar.readMethodReferences(
                                productionClass,
                                evidenceEntry,
                                errors
                        )
                        String expectedReference = VerifyProductionJar.methodReference(
                                evidenceMethodOwner,
                                expectedName,
                                evidenceMethodDescriptor
                        )
                        String forbiddenReference = VerifyProductionJar.methodReference(
                                evidenceMethodOwner,
                                forbiddenName,
                                evidenceMethodDescriptor
                        )
                        if (methodReferences != null && expectedReference != null &&
                                !methodReferences.contains(expectedReference)) {
                            errors << "production class '${evidenceEntry}' lacks expected SRG method reference '${expectedReference}'"
                        }
                        if (methodReferences != null && forbiddenReference != null &&
                                methodReferences.contains(forbiddenReference)) {
                            errors << "production class '${evidenceEntry}' still contains forbidden Mojang method reference '${forbiddenReference}'"
                        }
                        if (methodReferences != null && expectedReference != null &&
                                forbiddenReference != null) {
                            verifiedNamingKind = 'SRG'
                            verifiedNamingEntry = evidenceEntry
                            verifiedExpectedName = expectedReference
                            verifiedForbiddenName = forbiddenReference
                        }
                    }
                }
            } else {
                String expectedName = expectedMojangName.getOrNull()
                String forbiddenName = forbiddenSrgName.getOrNull()
                if (expectedName == null) {
                    errors << 'expected Mojang symbol is not configured'
                }
                if (forbiddenName == null) {
                    errors << 'forbidden SRG symbol is not configured'
                }
                if (evidenceEntry != null) {
                    byte[] productionClass = VerifyProductionJar.readEntry(zip, evidenceEntry)
                    if (productionClass == null) {
                        errors << "naming evidence class is missing: ${evidenceEntry}"
                    } else {
                        Set<String> methodReferences = VerifyProductionJar.readMethodReferences(
                                productionClass,
                                evidenceEntry,
                                errors
                        )
                        String expectedReference = VerifyProductionJar.methodReference(
                                evidenceMethodOwner,
                                expectedName,
                                evidenceMethodDescriptor
                        )
                        String forbiddenReference = VerifyProductionJar.methodReference(
                                evidenceMethodOwner,
                                forbiddenName,
                                evidenceMethodDescriptor
                        )
                        if (methodReferences != null && expectedReference != null &&
                                !methodReferences.contains(expectedReference)) {
                            errors << "production class '${evidenceEntry}' lacks expected Mojang method reference '${expectedReference}'"
                        }
                        if (methodReferences != null && forbiddenReference != null &&
                                methodReferences.contains(forbiddenReference)) {
                            errors << "production class '${evidenceEntry}' still contains forbidden SRG method reference '${forbiddenReference}'"
                        }
                        if (methodReferences != null && expectedReference != null &&
                                forbiddenReference != null) {
                            verifiedNamingKind = 'Mojang'
                            verifiedNamingEntry = evidenceEntry
                            verifiedExpectedName = expectedReference
                            verifiedForbiddenName = forbiddenReference
                        }
                    }
                }
            }

            if (!errors.isEmpty()) {
                throw new GradleException("Production JAR audit failed for ${archive}:\n - ${errors.join('\n - ')}")
            }

            logger.lifecycle(
                    'Verified {} naming evidence in {}: contains {}, excludes {}',
                    verifiedNamingKind,
                    verifiedNamingEntry,
                    verifiedExpectedName,
                    verifiedForbiddenName
            )
            logger.lifecycle(
                    'Verified production JAR {}: {} entries, {} staged codec entries ({} codec classes), {} core classes, {} common classes, {} Forge classes, {} NeoForge classes, {} production classes',
                    archive.name,
                    entries.size(),
                    codecEntries.size(),
                    codecClassEntries.size(),
                    coreClassEntries.size(),
                    commonClassEntries.size(),
                    forgeClassEntries.size(),
                    neoforgeClassEntries.size(),
                    actualClassEntries.size()
            )
        } finally {
            zip.close()
        }
    }

    static Set<String> relativeFileNames(Set<File> roots, boolean classesOnly) {
        Set<String> names = new TreeSet<>()
        roots.findAll { it.isDirectory() }.sort { it.absolutePath }.each { root ->
            root.eachFileRecurse(groovy.io.FileType.FILES) { candidate ->
                String relative = root.toPath().relativize(candidate.toPath()).toString().replace(File.separator, '/')
                if (!classesOnly || relative.endsWith('.class')) {
                    names.add(relative)
                }
            }
        }
        return names
    }

    private static Map<String, List<String>> forbiddenClassCategories(boolean nativeNeoForgeJar) {
        Map<String, List<String>> categories = new LinkedHashMap<>()
        categories['JUnit'] = [
                'junit/', 'org/junit/', 'org/opentest4j/', 'org/apiguardian/'
        ]
        categories['Spock'] = ['org/spockframework/']
        categories['Gradle/TestKit'] = ['org/gradle/']
        categories['Fabric loader/API/project'] = [
                'net/fabricmc/', 'datura/areamusic/fabric/'
        ]
        categories['Architectury'] = ['dev/architectury/', 'architectury/']
        categories['JOrbis'] = ['com/jcraft/jorbis/']
        if (nativeNeoForgeJar) {
            categories['Forge loader/project'] = [
                    'net/minecraftforge/', 'datura/areamusic/forge/'
            ]
        }
        return categories
    }

    private static boolean isTestFixtureClassEntry(String entryName) {
        return entryName.endsWith('.class') && VerifyProductionJar.isTestFixtureInternalName(
                entryName.substring(0, entryName.length() - '.class'.length())
        )
    }

    private static boolean isTestFixtureInternalName(String internalName) {
        if (internalName == null) {
            return false
        }
        List<String> segments = internalName.split('/') as List<String>
        return segments.any { segment ->
            String outerName = segment.contains('$') ? segment.substring(0, segment.indexOf('$')) : segment
            outerName == 'Test' || outerName == 'Tests' || outerName == 'TestCase' ||
                    outerName.endsWith('Test') || outerName.endsWith('Tests') ||
                    outerName.endsWith('TestCase') || outerName.endsWith('Fixture') ||
                    outerName.endsWith('Fixtures') || outerName == 'testFixtures' ||
                    outerName == 'test-fixtures'
        }
    }

    static Set<String> readMethodReferences(
            byte[] classBytes,
            String evidenceEntry,
            List<String> errors
    ) {
        Set<String> references = new TreeSet<>()
        try {
            new ClassReader(classBytes).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                MethodVisitor visitMethod(
                        int access,
                        String name,
                        String descriptor,
                        String signature,
                        String[] exceptions
                ) {
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        void visitMethodInsn(
                                int opcode,
                                String owner,
                                String invokedName,
                                String invokedDescriptor,
                                boolean isInterface
                        ) {
                            references.add(VerifyProductionJar.methodReference(
                                    owner,
                                    invokedName,
                                    invokedDescriptor
                            ))
                        }

                        @Override
                        void visitLdcInsn(Object value) {
                            VerifyProductionJar.collectMethodReferences(value, references)
                        }

                        @Override
                        void visitInvokeDynamicInsn(
                                String invokedName,
                                String invokedDescriptor,
                                Handle bootstrapMethodHandle,
                                Object... bootstrapMethodArguments
                        ) {
                            VerifyProductionJar.collectMethodReferences(
                                    bootstrapMethodHandle,
                                    references
                            )
                            bootstrapMethodArguments.each { argument ->
                                VerifyProductionJar.collectMethodReferences(argument, references)
                            }
                        }
                    }
                }
            }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES)
            return references
        } catch (RuntimeException failure) {
            errors << "naming evidence class '${evidenceEntry}' is not a valid class file: ${failure.message}"
            return null
        }
    }

    private static void collectMethodReferences(Object value, Set<String> references) {
        if (value instanceof Handle) {
            Handle handle = (Handle) value
            if (VerifyProductionJar.isMethodHandle(handle.tag)) {
                references.add(VerifyProductionJar.methodReference(
                        handle.owner,
                        handle.name,
                        handle.desc
                ))
            }
        } else if (value instanceof ConstantDynamic) {
            ConstantDynamic dynamic = (ConstantDynamic) value
            VerifyProductionJar.collectMethodReferences(
                    dynamic.bootstrapMethod,
                    references
            )
            for (int index = 0; index < dynamic.bootstrapMethodArgumentCount; index++) {
                VerifyProductionJar.collectMethodReferences(
                        dynamic.getBootstrapMethodArgument(index),
                        references
                )
            }
        }
    }

    private static boolean isMethodHandle(int tag) {
        return tag == Opcodes.H_INVOKEVIRTUAL ||
                tag == Opcodes.H_INVOKESTATIC ||
                tag == Opcodes.H_INVOKESPECIAL ||
                tag == Opcodes.H_NEWINVOKESPECIAL ||
                tag == Opcodes.H_INVOKEINTERFACE
    }

    private static String methodReference(String owner, String name, String descriptor) {
        if (owner == null || name == null || descriptor == null) {
            return null
        }
        return "${owner}#${name}${descriptor}"
    }

    static File findRelativeFile(Set<File> roots, String relativeName) {
        return roots.findResult { root ->
            File candidate = new File(root, relativeName.replace('/', File.separator))
            candidate.isFile() ? candidate : null
        }
    }

    static Set<String> intersection(Set<String> left, Set<String> right) {
        Set<String> result = new TreeSet<>(left)
        result.retainAll(right)
        return result
    }

    static boolean isIntegralNumberEqualTo(Object actual, int expected) {
        if (!(actual instanceof Number)) {
            return false
        }
        try {
            BigDecimal numericValue = new BigDecimal(actual.toString())
            return numericValue.stripTrailingZeros().scale() <= 0 &&
                    numericValue.compareTo(BigDecimal.valueOf(expected)) == 0
        } catch (NumberFormatException ignored) {
            return false
        }
    }

    static void validateStrictJson(String json) {
        JsonFactory jsonFactory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .build()
        JsonParser parser = jsonFactory.createParser(json)
        try {
            JsonToken rootToken = parser.nextToken()
            if (rootToken == null) {
                throw new IllegalArgumentException('JSON document is empty')
            }
            parser.skipChildren()
            if (parser.nextToken() != null) {
                throw new IllegalArgumentException('JSON document contains trailing tokens')
            }
        } finally {
            parser.close()
        }
    }

    static void requireExactlyOnce(
            String description,
            Set<String> expectedEntries,
            Map<String, Integer> counts,
            List<String> errors
    ) {
        expectedEntries.each { entryName ->
            int count = counts[entryName] ?: 0
            if (count != 1) {
                errors << "${description} '${entryName}' must occur exactly once, found ${count}"
            }
        }
    }

    static byte[] readEntry(java.util.zip.ZipFile zip, String entryName) {
        java.util.zip.ZipEntry entry = zip.getEntry(entryName)
        if (entry == null) {
            return null
        }
        InputStream input = zip.getInputStream(entry)
        try {
            return input.readAllBytes()
        } finally {
            input.close()
        }
    }

    static String readUtf8Entry(java.util.zip.ZipFile zip, String entryName) {
        byte[] bytes = VerifyProductionJar.readEntry(zip, entryName)
        return bytes == null ? null : new String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }

    static void requireAttribute(
            java.util.jar.Attributes attributes,
            String name,
            String expected,
            List<String> errors
    ) {
        String actual = attributes.getValue(name)
        if (actual != expected) {
            errors << "manifest ${name} must be '${expected}', found '${actual}'"
        }
    }

    private static int matchCount(String text, String regex) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile(regex).matcher(text)
        int count = 0
        while (matcher.find()) {
            count++
        }
        return count
    }
}
