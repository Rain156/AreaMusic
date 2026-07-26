package datura.gradle

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import java.util.zip.ZipFile

@CacheableTask
abstract class VerifyArchiveEntriesAbsent extends DefaultTask {
    @InputFile
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract RegularFileProperty getArchiveFile()

    @Input
    abstract ListProperty<String> getForbiddenEntries()

    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getForbiddenContentRoots()

    @TaskAction
    void verifyArchive() {
        File archive = archiveFile.get().asFile
        Set<String> forbidden = new TreeSet<>(forbiddenEntries.get())
        forbiddenContentRoots.files.sort { left, right ->
            left.absolutePath <=> right.absolutePath
        }.each { root ->
            if (root.isDirectory()) {
                root.eachFileRecurse(groovy.io.FileType.FILES) { candidate ->
                    forbidden.add(
                            root.toPath().relativize(candidate.toPath()).toString()
                                    .replace(File.separator, '/')
                    )
                }
            } else if (root.isFile()) {
                forbidden.add(root.name)
            }
        }
        Set<String> present = new TreeSet<>()
        ZipFile zip = new ZipFile(archive)
        try {
            zip.entries().each { entry ->
                if (!entry.directory && forbidden.contains(entry.name)) {
                    present.add(entry.name)
                }
            }
        } finally {
            zip.close()
        }

        if (!present.isEmpty()) {
            throw new GradleException(
                    "Archive ${archive.name} contains forbidden entries: ${present}"
            )
        }

        logger.lifecycle(
                'Verified archive {} excludes {} entries',
                archive.name,
                forbidden.size()
        )
    }
}
