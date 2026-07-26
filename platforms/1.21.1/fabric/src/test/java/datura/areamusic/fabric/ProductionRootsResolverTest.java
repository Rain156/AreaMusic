package datura.areamusic.fabric;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProductionRootsResolverTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void productionClientLinkCannotBeHiddenBySafeTestClasspathBytes() throws Exception {
        String root = "datura/areamusic/mutation/ShadowedRoot";
        String clientOnly = "net/minecraft/client/Minecraft";
        Path productionJar = writeJar(
                temporaryDirectory.resolve("production.jar"),
                root,
                SyntheticClassGraphFixture.classWithFieldType(root, clientOnly)
        );
        Path testOnlyRoot = temporaryDirectory.resolve("test-classes");
        writeClass(
                testOnlyRoot,
                root,
                SyntheticClassGraphFixture.linkingClass(root, "java/lang/Object")
        );

        AssertionError failure = withContextClassLoader(
                testOnlyRoot,
                () -> assertThrows(
                        AssertionError.class,
                        () -> DedicatedServerClassGraph.verify(
                                root,
                                new ProductionRootsResolver(List.of(productionJar))
                        )
                )
        );

        assertTrue(failure.getMessage().contains(root + " -> " + clientOnly));
    }

    @Test
    void missingProductionClassCannotBeSuppliedByTestClasspathBytes() throws Exception {
        String root = "datura/areamusic/mutation/MissingRoot";
        String helper = "datura/areamusic/mutation/TestOnlyHelper";
        Path productionRoot = temporaryDirectory.resolve("production-classes");
        writeClass(
                productionRoot,
                root,
                SyntheticClassGraphFixture.linkingClass(root, helper)
        );
        Path testOnlyRoot = temporaryDirectory.resolve("test-classes");
        writeClass(
                testOnlyRoot,
                helper,
                SyntheticClassGraphFixture.linkingClass(helper, "java/lang/Object")
        );

        AssertionError failure = withContextClassLoader(
                testOnlyRoot,
                () -> assertThrows(
                        AssertionError.class,
                        () -> DedicatedServerClassGraph.verify(
                                root,
                                new ProductionRootsResolver(List.of(productionRoot))
                        )
                )
        );

        assertTrue(failure.getMessage().contains(
                "Missing reachable project class on dedicated-server path: "
                        + root + " -> " + helper
        ));
    }

    @Test
    void duplicateLogicalClassAcrossProductionRootsIsAnExplicitCollision() throws IOException {
        String root = "datura/areamusic/mutation/CollidingRoot";
        byte[] classBytes = SyntheticClassGraphFixture.linkingClass(root, "java/lang/Object");
        Path directoryRoot = temporaryDirectory.resolve("production-classes");
        writeClass(directoryRoot, root, classBytes);
        Path jarRoot = writeJar(
                temporaryDirectory.resolve("production.jar"),
                root,
                classBytes
        );

        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> DedicatedServerClassGraph.verify(
                        root,
                        new ProductionRootsResolver(List.of(directoryRoot, jarRoot))
                )
        );

        assertTrue(failure.getMessage().contains(
                "Production class collision for '" + root + "'"
        ));
        assertTrue(failure.getMessage().contains(directoryRoot.toAbsolutePath().toString()));
        assertTrue(failure.getMessage().contains(jarRoot.toAbsolutePath().toString()));
    }

    private static void writeClass(Path root, String internalName, byte[] bytes)
            throws IOException {
        Path classFile = root.resolve(internalName + ".class");
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytes);
    }

    private static Path writeJar(Path jar, String internalName, byte[] bytes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new JarEntry(internalName + ".class"));
            output.write(bytes);
            output.closeEntry();
        }
        return jar;
    }

    private static <T> T withContextClassLoader(
            Path testOnlyRoot,
            ThrowingSupplier<T> action
    ) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        try (URLClassLoader testClassLoader = new URLClassLoader(
                new java.net.URL[]{testOnlyRoot.toUri().toURL()},
                previous
        )) {
            thread.setContextClassLoader(testClassLoader);
            return action.get();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }

    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }
}
