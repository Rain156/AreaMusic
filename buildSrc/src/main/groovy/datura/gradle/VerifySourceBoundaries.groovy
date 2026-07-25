package datura.gradle

import com.sun.source.tree.CompilationUnitTree
import com.sun.source.tree.IdentifierTree
import com.sun.source.tree.ImportTree
import com.sun.source.tree.MemberSelectTree
import com.sun.source.tree.PackageTree
import com.sun.source.tree.Tree
import com.sun.source.util.JavacTask
import com.sun.source.util.TreeScanner
import com.sun.source.util.Trees
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

import javax.tools.Diagnostic
import javax.tools.DiagnosticCollector
import javax.tools.JavaCompiler
import javax.tools.JavaFileObject
import javax.tools.StandardJavaFileManager
import javax.tools.ToolProvider
import javax.lang.model.SourceVersion
import java.nio.charset.StandardCharsets
import java.nio.file.Path

@CacheableTask
abstract class VerifySourceBoundaries extends DefaultTask {
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract ConfigurableFileCollection getSourceFiles()

    @InputDirectory
    @PathSensitive(PathSensitivity.RELATIVE)
    abstract DirectoryProperty getSourceRoot()

    @Input
    abstract Property<String> getBoundaryName()

    @Input
    abstract ListProperty<String> getForbiddenImportPrefixes()

    @Input
    abstract Property<Integer> getSourceLanguageVersion()

    @TaskAction
    void verifyImports() {
        Path root = sourceRoot.get().asFile.toPath().toAbsolutePath().normalize()
        List<File> sources = sourceFiles.files.findAll {
            it.isFile() && it.name.endsWith('.java')
        }.sort { left, right ->
            VerifySourceBoundaries.relativePath(root, left) <=> VerifySourceBoundaries.relativePath(root, right)
        }
        List<String> prefixes = forbiddenImportPrefixes.get()

        JavaCompiler compiler = ToolProvider.systemJavaCompiler
        if (compiler == null) {
            throw new GradleException(
                    "${boundaryName.get()} source boundary verification requires a full JDK compiler"
            )
        }
        int requestedLanguageVersion = sourceLanguageVersion.get()
        int latestSupportedLanguageVersion = Integer.parseInt(
                SourceVersion.latestSupported().name().substring('RELEASE_'.length())
        )
        if (requestedLanguageVersion > latestSupportedLanguageVersion) {
            throw new GradleException(
                    "${boundaryName.get()} source boundary compiler supports at most Java " +
                            "${latestSupportedLanguageVersion}, but the module requested Java " +
                            "${requestedLanguageVersion}"
            )
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>()
        StandardJavaFileManager fileManager = compiler.getStandardFileManager(
                diagnostics,
                Locale.ROOT,
                StandardCharsets.UTF_8
        )
        try {
            Iterable<? extends JavaFileObject> javaFiles = fileManager.getJavaFileObjectsFromFiles(sources)
            JavacTask javacTask = (JavacTask) compiler.getTask(
                    null,
                    fileManager,
                    diagnostics,
                    ['-proc:none', '--release', requestedLanguageVersion.toString()],
                    null,
                    javaFiles
            )
            List<CompilationUnitTree> compilationUnits = javacTask.parse().collect { it }
            List<Diagnostic<? extends JavaFileObject>> parseErrors = diagnostics.diagnostics.findAll {
                it.kind == Diagnostic.Kind.ERROR
            }
            if (!parseErrors.isEmpty()) {
                List<String> formattedDiagnostics = parseErrors.collect { diagnostic ->
                    VerifySourceBoundaries.formatDiagnostic(root, diagnostic)
                }.sort()
                throw new GradleException(
                        "${boundaryName.get()} source boundary verification failed because Java source could not be parsed:\n - " +
                                formattedDiagnostics.join('\n - ')
                )
            }

            Trees trees = Trees.instance(javacTask)
            List<Violation> violations = []
            compilationUnits.each { unit ->
                VerifySourceBoundaries.inspectCompilationUnit(root, unit, trees, prefixes, violations)
            }
            violations.sort { left, right ->
                int byPath = left.relativePath <=> right.relativePath
                if (byPath != 0) {
                    return byPath
                }
                int byLine = left.line <=> right.line
                if (byLine != 0) {
                    return byLine
                }
                int byName = left.qualifiedName <=> right.qualifiedName
                return byName != 0 ? byName : left.action <=> right.action
            }

            if (!violations.isEmpty()) {
                throw new GradleException(
                        "${boundaryName.get()} source boundary violation:\n - " +
                                violations.collect { it.message() }.join('\n - ')
                )
            }
        } catch (IOException parseFailure) {
            throw new GradleException(
                    "${boundaryName.get()} source boundary verification could not parse Java sources",
                    parseFailure
            )
        } finally {
            fileManager.close()
        }

        logger.lifecycle(
                'Verified {} source boundary: {} Java files, {} forbidden prefixes',
                boundaryName.get(),
                sources.size(),
                prefixes.size()
        )
    }

    private static void inspectCompilationUnit(
            Path root,
            CompilationUnitTree unit,
            Trees trees,
            List<String> prefixes,
            List<Violation> violations
    ) {
        String relative = VerifySourceBoundaries.relativePath(
                root,
                new File(unit.sourceFile.toUri())
        )
        PackageTree packageTree = unit.package
        if (packageTree != null) {
            String packageName = VerifySourceBoundaries.qualifiedName(packageTree.packageName)
            VerifySourceBoundaries.recordViolation(
                    relative,
                    VerifySourceBoundaries.lineOf(unit, trees, packageTree),
                    'declares package',
                    packageName,
                    prefixes,
                    violations
            )
            packageTree.annotations.each { annotation ->
                VerifySourceBoundaries.scanTree(
                        relative, unit, trees, annotation, prefixes, violations
                )
            }
        }

        unit.imports.each { ImportTree importTree ->
            String importedName = VerifySourceBoundaries.qualifiedName(importTree.qualifiedIdentifier)
            VerifySourceBoundaries.recordViolation(
                    relative,
                    VerifySourceBoundaries.lineOf(unit, trees, importTree),
                    'imports',
                    importedName,
                    prefixes,
                    violations
            )
        }

        unit.typeDecls.each { declaration ->
            VerifySourceBoundaries.scanTree(
                    relative, unit, trees, declaration, prefixes, violations
            )
        }
    }

    private static void scanTree(
            String relative,
            CompilationUnitTree unit,
            Trees trees,
            Tree tree,
            List<String> prefixes,
            List<Violation> violations
    ) {
        new TreeScanner<Void, Void>() {
            @Override
            Void visitMemberSelect(MemberSelectTree memberSelect, Void unused) {
                String selectedName = VerifySourceBoundaries.qualifiedName(memberSelect)
                String forbiddenPrefix = VerifySourceBoundaries.findForbiddenPrefix(
                        selectedName,
                        prefixes
                )
                if (forbiddenPrefix != null) {
                    violations.add(new Violation(
                            relative,
                            VerifySourceBoundaries.lineOf(unit, trees, memberSelect),
                            'references',
                            selectedName,
                            forbiddenPrefix
                    ))
                    return null
                }
                return super.visitMemberSelect(memberSelect, unused)
            }
        }.scan(tree, null)
    }

    private static void recordViolation(
            String relative,
            long line,
            String action,
            String qualifiedName,
            List<String> prefixes,
            List<Violation> violations
    ) {
        String forbiddenPrefix = VerifySourceBoundaries.findForbiddenPrefix(qualifiedName, prefixes)
        if (forbiddenPrefix != null) {
            violations.add(new Violation(relative, line, action, qualifiedName, forbiddenPrefix))
        }
    }

    private static String findForbiddenPrefix(String qualifiedName, List<String> prefixes) {
        if (qualifiedName == null) {
            return null
        }
        return prefixes.find { prefix ->
            qualifiedName == prefix || qualifiedName.startsWith(prefix + '.')
        }
    }

    private static String qualifiedName(Tree tree) {
        if (tree instanceof IdentifierTree) {
            return ((IdentifierTree) tree).name.toString()
        }
        if (tree instanceof MemberSelectTree) {
            MemberSelectTree selection = (MemberSelectTree) tree
            String expression = VerifySourceBoundaries.qualifiedName(selection.expression)
            return expression == null ? null : "${expression}.${selection.identifier}"
        }
        return tree == null ? null : tree.toString()
    }

    private static long lineOf(CompilationUnitTree unit, Trees trees, Tree tree) {
        long position = trees.sourcePositions.getStartPosition(unit, tree)
        return position < 0L ? 1L : unit.lineMap.getLineNumber(position)
    }

    private static String formatDiagnostic(
            Path root,
            Diagnostic<? extends JavaFileObject> diagnostic
    ) {
        String sourceName = '<unknown source>'
        if (diagnostic.source != null) {
            try {
                sourceName = VerifySourceBoundaries.relativePath(
                        root,
                        new File(diagnostic.source.toUri())
                )
            } catch (Exception ignored) {
                sourceName = diagnostic.source.name
            }
        }
        return "${sourceName}:${diagnostic.lineNumber}: could not be parsed: " +
                diagnostic.getMessage(Locale.ROOT)
    }

    private static String relativePath(Path root, File source) {
        Path absoluteSource = source.toPath().toAbsolutePath().normalize()
        return root.relativize(absoluteSource).toString().replace(File.separator, '/')
    }

    private static final class Violation {
        final String relativePath
        final long line
        final String action
        final String qualifiedName
        final String forbiddenPrefix

        Violation(
                String relativePath,
                long line,
                String action,
                String qualifiedName,
                String forbiddenPrefix
        ) {
            this.relativePath = relativePath
            this.line = line
            this.action = action
            this.qualifiedName = qualifiedName
            this.forbiddenPrefix = forbiddenPrefix
        }

        String message() {
            return "${relativePath}:${line} ${action} ${qualifiedName} (forbidden prefix ${forbiddenPrefix})"
        }
    }
}
