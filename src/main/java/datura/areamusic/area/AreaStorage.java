package datura.areamusic.area;

import datura.areamusic.music.MusicLibrary;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

public final class AreaStorage {
    private static final int MAX_READABLE_SAVE_NAME = 48;

    private final Path directory;
    private final AreaJsonCodec codec;

    public AreaStorage(Path directory, AreaJsonCodec codec) {
        this.directory = directory.toAbsolutePath().normalize();
        this.codec = codec;
    }

    public static String saveId(String worldDirectoryName) {
        if (worldDirectoryName == null || worldDirectoryName.isBlank()) {
            throw new IllegalArgumentException("World directory name must not be blank");
        }

        StringBuilder readable = new StringBuilder();
        boolean lastWasReplacement = false;
        for (int index = 0; index < worldDirectoryName.length() && readable.length() < MAX_READABLE_SAVE_NAME; index++) {
            char character = worldDirectoryName.charAt(index);
            if (isSafeFileCharacter(character)) {
                readable.append(character);
                lastWasReplacement = false;
            } else if (!lastWasReplacement && readable.length() > 0) {
                readable.append('_');
                lastWasReplacement = true;
            }
        }
        while (!readable.isEmpty() && readable.charAt(readable.length() - 1) == '_') {
            readable.deleteCharAt(readable.length() - 1);
        }
        if (readable.isEmpty()) {
            readable.append("world");
        }
        return readable + "-" + shortHash(worldDirectoryName);
    }

    public static Path directory(Path configRoot, String saveId) {
        Path base = configRoot.toAbsolutePath().normalize().resolve("areamusic");
        Path resolved = base.resolve(saveId).normalize();
        if (!resolved.startsWith(base)) {
            throw new IllegalArgumentException("Save ID escapes the AreaMusic config directory");
        }
        return resolved;
    }

    public Path directory() {
        return directory;
    }

    public List<AreaDefinition> load(MusicLibrary musicLibrary) throws IOException, LoadException {
        Files.createDirectories(directory);
        List<Path> files;
        try (Stream<Path> listed = Files.list(directory)) {
            files = listed
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }

        List<AreaDefinition> loaded = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                errors.add(fileName + ": symbolic links and non-files are not supported");
                continue;
            }

            String areaId = fileName.substring(0, fileName.length() - ".json".length());
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                AreaDefinition area = codec.read(areaId, reader);
                if (!musicLibrary.contains(area.musicId())) {
                    throw new IllegalArgumentException("MusicID is not present in the server library: " + area.musicId());
                }
                loaded.add(area);
            } catch (Exception exception) {
                errors.add(fileName + ": " + rootMessage(exception));
            }
        }

        if (!errors.isEmpty()) {
            throw new LoadException(errors);
        }
        return List.copyOf(loaded);
    }

    public synchronized void create(AreaDefinition area) throws IOException {
        Files.createDirectories(directory);
        Path target = directory.resolve(area.id() + ".json").normalize();
        if (!target.startsWith(directory)) {
            throw new IOException("Area path escapes the save directory");
        }
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new AreaAlreadyExistsException(area.id());
        }

        Path temporary = directory.resolve("." + area.id() + "-" + UUID.randomUUID() + ".tmp");
        try {
            Files.writeString(
                    temporary,
                    codec.write(area),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean isSafeFileCharacter(char character) {
        return character >= 'a' && character <= 'z'
                || character >= 'A' && character <= 'Z'
                || character >= '0' && character <= '9'
                || character == '.' || character == '_' || character == '-';
    }

    private static String shortHash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(8);
            for (int index = 0; index < 4; index++) {
                hex.append(String.format("%02x", digest[index]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }

    public static final class LoadException extends Exception {
        private final List<String> errors;

        public LoadException(List<String> errors) {
            super(String.join(System.lineSeparator(), errors));
            this.errors = List.copyOf(errors);
        }

        public List<String> errors() {
            return errors;
        }
    }

    public static final class AreaAlreadyExistsException extends IOException {
        public AreaAlreadyExistsException(String areaId) {
            super("Area already exists: " + areaId);
        }
    }
}
