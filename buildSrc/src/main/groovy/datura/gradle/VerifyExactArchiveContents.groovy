package datura.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipFile

@CacheableTask
abstract class VerifyExactArchiveContents extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    abstract RegularFileProperty getArchiveFile()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getProductionClassRoots()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getProductionResourceRoot()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestOutputRoots()

    @Input
    abstract ListProperty<String> getExpectedProductionResourceEntries()

    @Input
    abstract ListProperty<String> getGeneratedArchiveEntries()

    @TaskAction
    void verifyArchiveContents() {
        File archive = archiveFile.get().asFile
        List<String> errors = []

        Map<String, Set<String>> productionClassOrigins = relativeFileOrigins(
                productionClassRoots.files,
                { String entry -> entry.endsWith('.class') }
        )
        Set<String> productionClasses = new TreeSet<>(productionClassOrigins.keySet())
        if (productionClasses.isEmpty()) {
            errors.add('production class inputs are empty')
        }
        productionClassOrigins.findAll { String ignored, Set<String> origins ->
            origins.size() > 1
        }.each { String entry, Set<String> origins ->
            errors.add("duplicate production class entry '${entry}' from ${origins}")
        }

        Set<String> productionResources = relativeFileNames(
                [productionResourceRoot.get().asFile] as Set<File>,
                { String ignored -> true }
        )
        if (productionResources.isEmpty()) {
            errors.add('production resource inputs are empty')
        }

        Set<String> expectedResources = normalizedEntries(
                expectedProductionResourceEntries.get(),
                'expected production resource',
                errors
        )
        if (expectedResources.isEmpty()) {
            errors.add('expected production resource entries are empty')
        }
        Set<String> missingResourceInputs = new TreeSet<>(expectedResources)
        missingResourceInputs.removeAll(productionResources)
        Set<String> unexpectedResourceInputs = new TreeSet<>(productionResources)
        unexpectedResourceInputs.removeAll(expectedResources)
        if (!missingResourceInputs.isEmpty()) {
            errors.add("missing production resource inputs: ${missingResourceInputs}")
        }
        if (!unexpectedResourceInputs.isEmpty()) {
            errors.add("unexpected production resource inputs: ${unexpectedResourceInputs}")
        }

        Set<String> generatedEntries = normalizedEntries(
                generatedArchiveEntries.get(),
                'generated archive',
                errors
        )
        if (generatedEntries.isEmpty()) {
            errors.add('generated archive entries are empty')
        }

        Set<String> testOutputs = relativeFileNames(
                testOutputRoots.files,
                { String ignored -> true }
        )
        if (testOutputs.isEmpty()) {
            errors.add('test output inputs are empty')
        }

        Set<String> expectedFiles = new TreeSet<>()
        addDisjoint('production classes', productionClasses, expectedFiles, errors)
        addDisjoint('production resources', expectedResources, expectedFiles, errors)
        addDisjoint('generated archive entries', generatedEntries, expectedFiles, errors)
        Set<String> expectedEntries = new TreeSet<>(expectedFiles)
        expectedEntries.addAll(parentDirectories(expectedFiles))

        if (!archive.isFile()) {
            errors.add("archive does not exist: ${archive}")
        } else {
            ZipFile zip = new ZipFile(archive)
            try {
                Map<String, Integer> counts = new TreeMap<>()
                Set<String> actualEntries = new TreeSet<>()
                Set<String> actualFiles = new TreeSet<>()
                zip.entries().each { entry ->
                    counts[entry.name] = (counts[entry.name] ?: 0) + 1
                    actualEntries.add(entry.name)
                    if (!entry.directory) {
                        actualFiles.add(entry.name)
                    }
                }

                counts.findAll { String name, Integer count -> count != 1 }.each {
                    String name, Integer count ->
                        errors.add("duplicate archive entry '${name}' occurs ${count} times")
                }

                Set<String> missingEntries = new TreeSet<>(expectedEntries)
                missingEntries.removeAll(actualEntries)
                Set<String> unexpectedEntries = new TreeSet<>(actualEntries)
                unexpectedEntries.removeAll(expectedEntries)
                if (!missingEntries.isEmpty()) {
                    errors.add("missing archive entries: ${missingEntries}")
                }
                if (!unexpectedEntries.isEmpty()) {
                    errors.add("unexpected archive entries: ${unexpectedEntries}")
                }

                Set<String> leakedTestOutputs = new TreeSet<>(testOutputs)
                leakedTestOutputs.removeAll(expectedFiles)
                leakedTestOutputs.retainAll(actualFiles)
                if (!leakedTestOutputs.isEmpty()) {
                    errors.add(
                            "test outputs leaked into the production archive: ${leakedTestOutputs}"
                    )
                }
            } finally {
                zip.close()
            }
        }

        if (!errors.isEmpty()) {
            throw new GradleException(
                    "Exact archive audit failed for ${archive}:\n - ${errors.join('\n - ')}"
            )
        }

        logger.lifecycle(
                'Verified exact archive {}: {} production classes, {} production resources, {} generated entries, {} total entries, {} test outputs excluded',
                archive.name,
                productionClasses.size(),
                expectedResources.size(),
                generatedEntries.size(),
                expectedEntries.size(),
                testOutputs.size()
        )
    }

    private static void addDisjoint(
            String inputName,
            Set<String> additions,
            Set<String> accumulated,
            List<String> errors
    ) {
        Set<String> overlap = new TreeSet<>(additions)
        overlap.retainAll(accumulated)
        if (!overlap.isEmpty()) {
            errors.add("${inputName} overlap earlier archive inputs: ${overlap}")
        }
        accumulated.addAll(additions)
    }

    private static Set<String> normalizedEntries(
            List<String> configuredEntries,
            String inputName,
            List<String> errors
    ) {
        Set<String> normalized = new TreeSet<>()
        configuredEntries.each { configuredEntry ->
            String entry = configuredEntry == null ? '' : configuredEntry.replace('\\', '/')
            if (entry.isBlank() || entry.startsWith('/') || entry.endsWith('/') ||
                    entry.split('/').contains('..')) {
                errors.add("invalid ${inputName} entry '${configuredEntry}'")
            } else if (!normalized.add(entry)) {
                errors.add("duplicate ${inputName} entry '${entry}'")
            }
        }
        return normalized
    }

    private static Set<String> relativeFileNames(
            Set<File> configuredRoots,
            Closure<Boolean> includeEntry
    ) {
        return new TreeSet<>(relativeFileOrigins(configuredRoots, includeEntry).keySet())
    }

    private static Map<String, Set<String>> relativeFileOrigins(
            Set<File> configuredRoots,
            Closure<Boolean> includeEntry
    ) {
        Map<String, Set<String>> originsByEntry = new TreeMap<>()
        configuredRoots.sort { File left, File right ->
            left.absolutePath <=> right.absolutePath
        }.each { root ->
            if (root.isDirectory()) {
                root.eachFileRecurse(groovy.io.FileType.FILES) { candidate ->
                    String entry = root.toPath().relativize(candidate.toPath()).toString()
                            .replace(File.separator, '/')
                    if (includeEntry.call(entry)) {
                        originsByEntry.computeIfAbsent(entry) { new TreeSet<>() }
                                .add(candidate.absolutePath)
                    }
                }
            } else if (root.isFile()) {
                String entry = root.name
                if (includeEntry.call(entry)) {
                    originsByEntry.computeIfAbsent(entry) { new TreeSet<>() }
                            .add(root.absolutePath)
                }
            }
        }
        return originsByEntry
    }

    private static Set<String> parentDirectories(Set<String> fileEntries) {
        Set<String> directories = new TreeSet<>()
        fileEntries.each { fileEntry ->
            int slash = fileEntry.indexOf('/')
            while (slash >= 0) {
                directories.add(fileEntry.substring(0, slash + 1))
                slash = fileEntry.indexOf('/', slash + 1)
            }
        }
        return directories
    }
}
