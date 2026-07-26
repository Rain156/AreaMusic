package datura.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import org.objectweb.asm.commons.ClassRemapper
import org.objectweb.asm.commons.Remapper

import java.nio.file.Files
import java.util.Collections

@CacheableTask
abstract class VerifyClassBoundaries extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getProductionSourceFiles()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestSourceFiles()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getProductionClassRoots()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getTestClassRoots()

    @Input
    abstract Property<String> getBoundaryName()

    @Input
    abstract ListProperty<String> getForbiddenInternalNamePrefixes()

    @Input
    @Optional
    abstract Property<Integer> getExpectedClassMajor()

    @Input
    abstract Property<Boolean> getSeparatedMode()

    VerifyClassBoundaries() {
        separatedMode.convention(false)
    }

    @TaskAction
    void verifyClassReferences() {
        boolean hasSeparatedInputs = !productionSourceFiles.files.isEmpty() ||
                !testSourceFiles.files.isEmpty() ||
                !productionClassRoots.files.isEmpty() ||
                !testClassRoots.files.isEmpty()
        if (separatedMode.get()) {
            if (!classRoots.files.isEmpty()) {
                throw new GradleException(
                        "${boundaryName.get()} compiled class boundary configuration invalid: " +
                                'separated mode cannot use classRoots'
                )
            }
            verifySeparatedClassSets()
            return
        }
        if (hasSeparatedInputs) {
            throw new GradleException(
                    "${boundaryName.get()} compiled class boundary configuration invalid: " +
                            'legacy mode cannot use separated inputs'
            )
        }

        List<ClassInput> classes = classInputs(classRoots.files)
        if (classes.isEmpty()) {
            throw new GradleException(
                    "${boundaryName.get()} compiled class boundary verification found no class files"
            )
        }

        List<String> violations = []
        inspectClasses(classes, '', violations)
        failOnViolations(violations)

        logger.lifecycle(
                'Verified {} compiled class boundary: {} class files, {} forbidden prefixes',
                boundaryName.get(),
                classes.size(),
                forbiddenInternalNamePrefixes.get().size()
        )
    }

    private void verifySeparatedClassSets() {
        List<SourceInput> productionSources = sourceInputs(productionSourceFiles.files)
        List<SourceInput> tests = sourceInputs(testSourceFiles.files)
        List<ClassInput> productionClasses = classInputs(productionClassRoots.files)
        List<ClassInput> testClasses = classInputs(testClassRoots.files)
        List<String> violations = []

        requireNonEmpty('production source set', productionSources, violations)
        requireNonEmpty('test source set', tests, violations)
        requireNonEmpty('production compiled class set', productionClasses, violations)
        requireNonEmpty('test compiled class set', testClasses, violations)
        requirePrimaryClasses('production', productionSources, productionClasses, violations)
        requirePrimaryClasses('test', tests, testClasses, violations)
        inspectClasses(productionClasses, 'production ', violations)
        inspectClasses(testClasses, 'test ', violations)
        failOnViolations(violations)

        logger.lifecycle(
                'Verified {} separated compiled class boundaries: {} production sources, {} production classes, {} test sources, {} test classes, {} forbidden prefixes',
                boundaryName.get(),
                productionSources.size(),
                productionClasses.size(),
                tests.size(),
                testClasses.size(),
                forbiddenInternalNamePrefixes.get().size()
        )
    }

    private void inspectClasses(
            List<ClassInput> classes,
            String displayPrefix,
            List<String> violations
    ) {
        List<String> prefixes = forbiddenInternalNamePrefixes.get()
        classes.each { classInput ->
            String displayPath = displayPrefix + classInput.relativePath
            CollectingRemapper remapper = new CollectingRemapper(prefixes)
            try {
                byte[] classBytes = Files.readAllBytes(classInput.file.toPath())
                ClassReader reader = new ClassReader(classBytes)
                String declaredClassEntry = reader.className + '.class'
                if (declaredClassEntry != classInput.relativePath) {
                    violations.add(
                            "${displayPath} declares ${declaredClassEntry} " +
                                    "(expected ${classInput.relativePath})"
                    )
                }
                if (expectedClassMajor.present) {
                    int actualClassMajor = reader.readUnsignedShort(6)
                    int requiredClassMajor = expectedClassMajor.get()
                    if (actualClassMajor != requiredClassMajor) {
                        violations.add(
                                "${displayPath} has class major version " +
                                        "${actualClassMajor} (expected ${requiredClassMajor})"
                        )
                    }
                }
                ClassWriter sink = new ClassWriter(0)
                reader.accept(
                        new ClassRemapper(sink, remapper),
                        0
                )
            } catch (Exception malformedClass) {
                throw new GradleException(
                        "${boundaryName.get()} compiled class boundary verification could not parse " +
                                "${displayPath}",
                        malformedClass
                )
            }
            remapper.forbiddenNames.each { internalName ->
                String forbiddenPrefix = prefixes.find { prefix ->
                    VerifyClassBoundaries.isWithinPrefix(internalName, prefix)
                }
                violations.add(
                        "${displayPath} references ${internalName} " +
                                "(forbidden prefix ${forbiddenPrefix})"
                )
            }
        }
    }

    private void failOnViolations(List<String> violations) {
        if (!violations.isEmpty()) {
            throw new GradleException(
                    "${boundaryName.get()} compiled class boundary violation:\n - " +
                            violations.sort().join('\n - ')
            )
        }
    }

    private static void requireNonEmpty(String setName, List<?> inputs, List<String> violations) {
        if (inputs.isEmpty()) {
            violations.add("${setName} is empty")
        }
    }

    private static void requirePrimaryClasses(
            String setName,
            List<SourceInput> sources,
            List<ClassInput> classes,
            List<String> violations
    ) {
        Set<String> classEntries = new TreeSet<>(classes.collect { it.relativePath })
        sources.each { source ->
            if (!classEntries.contains(source.expectedPrimaryClass)) {
                violations.add(
                        "${setName} source ${source.relativePath} has no compiled primary class " +
                                source.expectedPrimaryClass
                )
            }
        }
    }

    private static List<ClassInput> classInputs(Set<File> configuredRoots) {
        List<ClassInput> classes = []
        configuredRoots.sort { left, right ->
            left.absolutePath <=> right.absolutePath
        }.each { root ->
            if (root.isDirectory()) {
                root.eachFileRecurse(groovy.io.FileType.FILES) { candidate ->
                    if (candidate.name.endsWith('.class')) {
                        classes.add(new ClassInput(
                                candidate,
                                root.toPath().relativize(candidate.toPath()).toString()
                                        .replace(File.separator, '/')
                        ))
                    }
                }
            } else if (root.isFile() && root.name.endsWith('.class')) {
                classes.add(new ClassInput(root, root.name))
            }
        }
        classes.sort { left, right ->
            int byPath = left.relativePath <=> right.relativePath
            return byPath != 0 ? byPath : left.file.absolutePath <=> right.file.absolutePath
        }
        return classes
    }

    private static List<SourceInput> sourceInputs(Set<File> configuredSources) {
        List<File> javaSources = []
        configuredSources.sort { left, right ->
            left.absolutePath <=> right.absolutePath
        }.each { configuredSource ->
            if (configuredSource.isDirectory()) {
                configuredSource.eachFileRecurse(groovy.io.FileType.FILES) { candidate ->
                    if (candidate.name.endsWith('.java')) {
                        javaSources.add(candidate)
                    }
                }
            } else if (configuredSource.isFile() && configuredSource.name.endsWith('.java')) {
                javaSources.add(configuredSource)
            }
        }
        return javaSources.sort { left, right ->
            left.absolutePath <=> right.absolutePath
        }.collect { source ->
            String sourceText = Files.readString(source.toPath())
            def packageMatcher = sourceText =~ /(?m)^\s*package\s+([A-Za-z_$][A-Za-z0-9_$]*(?:\.[A-Za-z_$][A-Za-z0-9_$]*)*)\s*;/
            String packagePath = packageMatcher.find() ? packageMatcher.group(1).replace('.', '/') + '/' : ''
            String simpleName = source.name.substring(0, source.name.length() - '.java'.length())
            String expectedClass = packagePath + simpleName + '.class'
            return new SourceInput(source, expectedClass.replaceFirst(/\.class$/, '.java'), expectedClass)
        }
    }

    private static boolean isWithinPrefix(String internalName, String prefix) {
        return internalName == prefix || internalName.startsWith(prefix + '/')
    }

    private static final class CollectingRemapper extends Remapper {
        private final List<String> forbiddenPrefixes
        final Set<String> forbiddenNames = new TreeSet<>()

        CollectingRemapper(List<String> forbiddenPrefixes) {
            super()
            this.forbiddenPrefixes = Collections.unmodifiableList(new ArrayList<>(forbiddenPrefixes))
        }

        @Override
        String map(String internalName) {
            if (internalName != null && forbiddenPrefixes.any { prefix ->
                VerifyClassBoundaries.isWithinPrefix(internalName, prefix)
            }) {
                forbiddenNames.add(internalName)
            }
            return internalName
        }
    }

    private static final class ClassInput {
        final File file
        final String relativePath

        ClassInput(File file, String relativePath) {
            this.file = file
            this.relativePath = relativePath
        }
    }

    private static final class SourceInput {
        final File file
        final String relativePath
        final String expectedPrimaryClass

        SourceInput(File file, String relativePath, String expectedPrimaryClass) {
            this.file = file
            this.relativePath = relativePath
            this.expectedPrimaryClass = expectedPrimaryClass
        }
    }
}
