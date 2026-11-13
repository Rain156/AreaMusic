package datura.areamusic.net;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Server options, kept in a plain properties file so that every loader reads the same thing.
 * Nobody needs to open it: the defaults are what a normal server wants.
 */
public record ServerConfig(ServerSync.Settings sync, int editPermissionLevel, List<String> warnings) {
    public static final String FILE_NAME = "areamusic-server.properties";
    public static final ServerConfig DEFAULT = new ServerConfig(ServerSync.Settings.DEFAULT, 2, List.of());
    private static final String TEMPLATE = """
            # AreaMusic server options. Delete a line to restore its default.

            # Send music files to players automatically. false = every player must copy the files
            # into their own areamusic folder, as in AreaMusic 0.x.
            distributeMusic=true

            # Largest file an editor may upload, in MiB.
            maxUploadMiB=32

            # Total size the music library may reach through uploads, in MiB.
            maxLibraryMiB=1024

            # Download speed limit for each player, in KiB per second.
            downloadKiBPerSecondPerPlayer=2560

            # Permission level needed to edit areas (0-4). The owner of a single-player world can
            # always edit.
            editPermissionLevel=2
            """;

    public ServerConfig {
        warnings = List.copyOf(warnings);
    }

    /** Reads the file, creating it with defaults when missing. Bad values fall back to defaults. */
    public static ServerConfig load(Path file) {
        List<String> warnings = new ArrayList<>();
        Properties properties = new Properties();
        try {
            if (Files.notExists(file)) {
                Files.createDirectories(file.toAbsolutePath().getParent());
                Files.writeString(file, TEMPLATE, StandardCharsets.UTF_8);
            }
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
        } catch (IOException | IllegalArgumentException exception) {
            warnings.add("Could not read " + file.getFileName() + ": " + exception.getMessage());
            return new ServerConfig(DEFAULT.sync(), DEFAULT.editPermissionLevel(), warnings);
        }
        boolean distribute = !"false".equalsIgnoreCase(
                properties.getProperty("distributeMusic", "true").strip());
        long maxUpload = number(properties, "maxUploadMiB", 32, 1, 4096, warnings) << 20;
        long maxLibrary = number(properties, "maxLibraryMiB", 1024, 1, 1 << 20, warnings) << 20;
        long kibPerSecond = number(properties, "downloadKiBPerSecondPerPlayer", 2560, 32, 1 << 20, warnings);
        int level = (int) number(properties, "editPermissionLevel", 2, 0, 4, warnings);
        return new ServerConfig(
                new ServerSync.Settings(distribute, maxUpload, maxLibrary, kibPerSecond * 1024 / 20),
                level, warnings);
    }

    private static long number(
            Properties properties, String key, long fallback, long min, long max, List<String> warnings
    ) {
        String text = properties.getProperty(key);
        if (text == null) {
            return fallback;
        }
        try {
            long value = Long.parseLong(text.strip());
            if (value >= min && value <= max) {
                return value;
            }
        } catch (NumberFormatException ignored) {
            // Reported below.
        }
        warnings.add(key + " must be a whole number from " + min + " to " + max + "; using " + fallback);
        return fallback;
    }
}
