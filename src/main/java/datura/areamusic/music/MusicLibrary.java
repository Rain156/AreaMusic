package datura.areamusic.music;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

public final class MusicLibrary {
    public static final Set<String> SUPPORTED_EXTENSIONS = Set.of("ogg", "mp3", "wav", "flac");

    private final Path root;
    private final Map<String, Path> files;

    private MusicLibrary(Path root, Map<String, Path> files) {
        this.root = root;
        this.files = Collections.unmodifiableMap(new LinkedHashMap<>(files));
    }

    public static MusicLibrary empty(Path root) {
        return new MusicLibrary(root.toAbsolutePath().normalize(), Map.of());
    }

    public static MusicLibrary scan(Path root) throws IOException, ScanException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Files.createDirectories(normalizedRoot);
        if (Files.isSymbolicLink(normalizedRoot)) {
            throw new ScanException("Music root must not be a symbolic link: " + normalizedRoot);
        }

        Map<String, Path> indexed = new TreeMap<>();
        Map<String, String> caseFoldedIds = new LinkedHashMap<>();
        try (Stream<Path> walked = Files.walk(normalizedRoot)) {
            for (Path path : (Iterable<Path>) walked::iterator) {
                if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
                    continue;
                }

                Path normalized = path.toAbsolutePath().normalize();
                if (!normalized.startsWith(normalizedRoot)) {
                    continue;
                }

                String id = normalizedRoot.relativize(normalized).toString()
                        .replace(path.getFileSystem().getSeparator(), "/");
                if (!isSupported(id)) {
                    continue;
                }

                String folded = id.toLowerCase(Locale.ROOT);
                String existing = caseFoldedIds.putIfAbsent(folded, id);
                if (existing != null && !existing.equals(id)) {
                    throw new ScanException("Music IDs differ only by case: " + existing + " and " + id);
                }
                indexed.put(id, normalized);
            }
        }
        return new MusicLibrary(normalizedRoot, indexed);
    }

    public Path root() {
        return root;
    }

    public Set<String> ids() {
        return files.keySet();
    }

    public Optional<Path> find(String musicId) {
        return Optional.ofNullable(files.get(musicId));
    }

    public boolean contains(String musicId) {
        return files.containsKey(musicId);
    }

    private static boolean isSupported(String id) {
        int separator = id.lastIndexOf('/');
        int dot = id.lastIndexOf('.');
        return dot > separator && dot < id.length() - 1
                && SUPPORTED_EXTENSIONS.contains(id.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    public static final class ScanException extends IOException {
        public ScanException(String message) {
            super(message);
        }
    }
}
