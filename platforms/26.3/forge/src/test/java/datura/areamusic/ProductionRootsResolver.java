package datura.areamusic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class ProductionRootsResolver implements DedicatedServerClassGraph.ClassBytesResolver {
    static final String PROPERTY_NAME =
            "areamusic.forge.dedicatedServerProductionRoots";

    private final List<Path> roots;

    ProductionRootsResolver(List<Path> roots) {
        Set<Path> normalizedRoots = new LinkedHashSet<>();
        for (Path root : roots) {
            normalizedRoots.add(root.toAbsolutePath().normalize());
        }
        if (normalizedRoots.isEmpty()) {
            throw new AssertionError("No dedicated-server production class roots were configured");
        }
        this.roots = List.copyOf(normalizedRoots);
    }

    static ProductionRootsResolver fromSystemProperty() {
        String encodedRoots = System.getProperty(PROPERTY_NAME);
        if (encodedRoots == null || encodedRoots.isBlank()) {
            throw new AssertionError(
                    "Missing dedicated-server production roots system property: "
                            + PROPERTY_NAME
            );
        }
        List<Path> roots = Arrays.stream(encodedRoots.split(
                        Pattern.quote(File.pathSeparator),
                        -1
                ))
                .filter(value -> !value.isBlank())
                .map(Path::of)
                .toList();
        return new ProductionRootsResolver(roots);
    }

    @Override
    public byte[] resolve(String internalName) throws IOException {
        String entryName = internalName + ".class";
        List<ResolvedClass> matches = new ArrayList<>();
        for (Path root : roots) {
            byte[] bytes = read(root, entryName);
            if (bytes != null) {
                matches.add(new ResolvedClass(root, bytes));
            }
        }
        if (matches.size() > 1) {
            throw new AssertionError(
                    "Production class collision for '"
                            + internalName
                            + "' across roots "
                            + matches.stream().map(ResolvedClass::root).toList()
            );
        }
        return matches.isEmpty() ? null : matches.get(0).bytes();
    }

    private static byte[] read(Path root, String entryName) throws IOException {
        if (Files.isDirectory(root)) {
            Path classFile = root.resolve(entryName).normalize();
            if (!classFile.startsWith(root)) {
                throw new AssertionError(
                        "Production class path escapes its root: " + classFile
                );
            }
            return Files.isRegularFile(classFile) ? Files.readAllBytes(classFile) : null;
        }
        if (Files.isRegularFile(root)) {
            try (ZipFile archive = new ZipFile(root.toFile())) {
                ZipEntry entry = archive.getEntry(entryName);
                if (entry == null || entry.isDirectory()) {
                    return null;
                }
                try (InputStream input = archive.getInputStream(entry)) {
                    return input.readAllBytes();
                }
            }
        }
        throw new AssertionError(
                "Dedicated-server production class root is missing or unsupported: " + root
        );
    }

    private record ResolvedClass(Path root, byte[] bytes) {
    }
}
