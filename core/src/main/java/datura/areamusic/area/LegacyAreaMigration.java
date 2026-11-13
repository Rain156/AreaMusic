package datura.areamusic.area;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/**
 * Copies area files from the pre-v2 {@code config/areamusic/<saveId>/} folder into the world folder.
 * Legacy files are never modified or removed.
 */
public final class LegacyAreaMigration {
    public static final String MARKER_FILE_NAME = "MIGRATED.txt";

    private LegacyAreaMigration() {
    }

    public static Result migrateIfNeeded(Path legacyDirectory, AreaStorage target) throws IOException {
        Objects.requireNonNull(legacyDirectory, "legacyDirectory");
        Objects.requireNonNull(target, "target");
        if (Files.exists(target.directory(), LinkOption.NOFOLLOW_LINKS)
                || !Files.isDirectory(legacyDirectory)) {
            return Result.skipped();
        }
        List<Path> files;
        try (Stream<Path> listed = Files.list(legacyDirectory)) {
            files = listed
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.endsWith(".json") && !name.startsWith(".");
                    })
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        if (files.isEmpty()) {
            return Result.skipped();
        }

        AreaJsonCodec codec = new AreaJsonCodec();
        int migrated = 0;
        List<String> errors = new ArrayList<>();
        for (Path file : files) {
            String fileName = file.getFileName().toString();
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file)) {
                errors.add(fileName + ": symbolic links and non-files are not supported");
                continue;
            }
            String areaId = fileName.substring(0, fileName.length() - ".json".length());
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                target.save(codec.read(areaId, reader));
                migrated++;
            } catch (Exception exception) {
                String message = exception.getMessage();
                errors.add(fileName + ": "
                        + (message == null || message.isBlank()
                        ? exception.getClass().getSimpleName()
                        : message));
            }
        }
        Files.createDirectories(target.directory());
        Files.writeString(
                legacyDirectory.resolve(MARKER_FILE_NAME),
                "AreaMusic copied " + migrated + " area file(s) from this folder to:"
                        + System.lineSeparator()
                        + target.directory()
                        + System.lineSeparator()
                        + "The files here are no longer read. They were left untouched as a backup."
                        + System.lineSeparator(),
                StandardCharsets.UTF_8
        );
        return new Result(true, migrated, errors);
    }

    public record Result(boolean ran, int migrated, List<String> errors) {
        public Result {
            errors = List.copyOf(errors);
        }

        static Result skipped() {
            return new Result(false, 0, List.of());
        }
    }
}
