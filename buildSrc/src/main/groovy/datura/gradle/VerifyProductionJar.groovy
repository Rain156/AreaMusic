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
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

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
    abstract ListProperty<String> getRequiredResourceEntries()

    @Input
    abstract ListProperty<String> getRequiredProviderEntries()

    @Input
    abstract Property<String> getReobfuscationEvidenceEntry()

    @Input
    abstract Property<String> getExpectedSrgName()

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

            Set<String> jorbisEntries = counts.keySet().findAll { it.startsWith('com/jcraft/jorbis/') }
            if (!jorbisEntries.isEmpty()) {
                errors << "JOrbis must be supplied by Minecraft, but the production JAR contains: ${jorbisEntries}"
            }

            Set<String> testDependencyEntries = counts.keySet().findAll {
                it.startsWith('junit/') ||
                        it.startsWith('org/junit/') ||
                        it.startsWith('org/opentest4j/') ||
                        it.startsWith('org/apiguardian/')
            }
            if (!testDependencyEntries.isEmpty()) {
                errors << "test-framework entries are forbidden: ${testDependencyEntries}"
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
            if (forgeClassEntries.isEmpty()) {
                errors << "no Forge main classes were found in ${forgeMainClassRoots.files}"
            }
            VerifyProductionJar.requireExactlyOnce('Forge main class', forgeClassEntries, counts, errors)

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

            Set<String> expectedClassEntries = new TreeSet<>()
            expectedClassEntries.addAll(coreClassEntries)
            expectedClassEntries.addAll(commonClassEntries)
            expectedClassEntries.addAll(forgeClassEntries)
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
            Set<String> leakedTestOutputs = testOutputEntries.findAll { counts.containsKey(it) }
            if (!leakedTestOutputs.isEmpty()) {
                errors << "test outputs/resources leaked into the production JAR: ${leakedTestOutputs}"
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

            String modsToml = VerifyProductionJar.readUtf8Entry(zip, 'META-INF/mods.toml')
            if (modsToml != null) {
                String quotedModId = java.util.regex.Pattern.quote(expectedModId.get())
                String quotedVersion = java.util.regex.Pattern.quote(expectedModVersion.get())
                String modIdPattern = '(?m)^\\s*modId\\s*=\\s*"' + quotedModId + '"\\s*$'
                if (VerifyProductionJar.matchCount(modsToml, modIdPattern) != 1) {
                    errors << "mods.toml must contain exactly one primary modId = '${expectedModId.get()}'"
                }
                String versionPattern = '(?m)^\\s*version\\s*=\\s*"' + quotedVersion + '"\\s*$'
                if (VerifyProductionJar.matchCount(modsToml, versionPattern) != 1) {
                    errors << "mods.toml must contain exactly one version = '${expectedModVersion.get()}'"
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

            String evidenceEntry = reobfuscationEvidenceEntry.get()
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
                String classConstants = new String(productionClass, java.nio.charset.StandardCharsets.ISO_8859_1)
                if (!classConstants.contains(expectedSrgName.get())) {
                    errors << "production class '${evidenceEntry}' lacks expected SRG symbol '${expectedSrgName.get()}'"
                }
                if (classConstants.contains(forbiddenMojangName.get())) {
                    errors << "production class '${evidenceEntry}' still contains Mojang symbol '${forbiddenMojangName.get()}'"
                }
            }

            if (!errors.isEmpty()) {
                throw new GradleException("Production JAR audit failed for ${archive}:\n - ${errors.join('\n - ')}")
            }

            logger.lifecycle(
                    'Verified production JAR {}: {} entries, {} staged codec entries ({} codec classes), {} core classes, {} common classes, {} Forge classes, {} production classes',
                    archive.name,
                    entries.size(),
                    codecEntries.size(),
                    codecClassEntries.size(),
                    coreClassEntries.size(),
                    commonClassEntries.size(),
                    forgeClassEntries.size(),
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
