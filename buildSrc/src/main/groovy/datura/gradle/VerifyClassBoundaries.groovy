package datura.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
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

    @Input
    abstract Property<String> getBoundaryName()

    @Input
    abstract ListProperty<String> getForbiddenInternalNamePrefixes()

    @TaskAction
    void verifyClassReferences() {
        List<File> roots = classRoots.files.sort { left, right ->
            left.absolutePath <=> right.absolutePath
        }
        List<ClassInput> classes = []
        roots.each { root ->
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
        if (classes.isEmpty()) {
            throw new GradleException(
                    "${boundaryName.get()} compiled class boundary verification found no class files"
            )
        }

        List<String> prefixes = forbiddenInternalNamePrefixes.get()
        List<String> violations = []
        classes.each { classInput ->
            CollectingRemapper remapper = new CollectingRemapper(prefixes)
            try {
                ClassReader reader = new ClassReader(Files.readAllBytes(classInput.file.toPath()))
                ClassWriter sink = new ClassWriter(0)
                reader.accept(
                        new ClassRemapper(sink, remapper),
                        0
                )
            } catch (Exception malformedClass) {
                throw new GradleException(
                        "${boundaryName.get()} compiled class boundary verification could not parse " +
                                "${classInput.relativePath}",
                        malformedClass
                )
            }
            remapper.forbiddenNames.each { internalName ->
                String forbiddenPrefix = prefixes.find { prefix ->
                    VerifyClassBoundaries.isWithinPrefix(internalName, prefix)
                }
                violations.add(
                        "${classInput.relativePath} references ${internalName} " +
                                "(forbidden prefix ${forbiddenPrefix})"
                )
            }
        }

        if (!violations.isEmpty()) {
            throw new GradleException(
                    "${boundaryName.get()} compiled class boundary violation:\n - " +
                            violations.sort().join('\n - ')
            )
        }

        logger.lifecycle(
                'Verified {} compiled class boundary: {} class files, {} forbidden prefixes',
                boundaryName.get(),
                classes.size(),
                prefixes.size()
        )
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
}
