package datura.areamusic.fabric.config;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

public final class FabricClientConfig {
    public static final String FILE_NAME = "areamusic-client.properties";

    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double DEFAULT_VOLUME = 1.0;
    private static final String VOLUME_PREFIX = "volume=";

    private final Path configDirectory;
    private final Path configFile;
    private volatile double volume;

    private FabricClientConfig(Path configDirectory, double volume) {
        this.configDirectory = configDirectory;
        configFile = configDirectory.resolve(FILE_NAME);
        this.volume = volume;
    }

    public static FabricClientConfig load(Path configDirectory) {
        Path normalizedDirectory = Objects.requireNonNull(configDirectory, "configDirectory")
                .toAbsolutePath()
                .normalize();
        Path file = normalizedDirectory.resolve(FILE_NAME);
        if (!Files.exists(file)) {
            return new FabricClientConfig(normalizedDirectory, DEFAULT_VOLUME);
        }
        if (!Files.isRegularFile(file)) {
            LOGGER.warn("AreaMusic client config is not a regular file: {}; using defaults", file);
            return new FabricClientConfig(normalizedDirectory, DEFAULT_VOLUME);
        }

        try {
            return new FabricClientConfig(normalizedDirectory, readVolume(file));
        } catch (IOException | IllegalArgumentException failure) {
            LOGGER.warn("Could not load AreaMusic client config {}; using defaults", file, failure);
            return new FabricClientConfig(normalizedDirectory, DEFAULT_VOLUME);
        }
    }

    public double volume() {
        return volume;
    }

    public synchronized void setVolume(double value) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("AreaMusic volume must be finite");
        }
        volume = clamp(value);
        saveQuietly();
    }

    private static double readVolume(Path file) throws IOException {
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.size() != 1 || !lines.get(0).startsWith(VOLUME_PREFIX)) {
            throw new IllegalArgumentException("Expected exactly one volume property");
        }
        String rawValue = lines.get(0).substring(VOLUME_PREFIX.length());
        if (rawValue.isEmpty()) {
            throw new IllegalArgumentException("Volume value is empty");
        }
        double parsed = Double.parseDouble(rawValue);
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException("Volume value must be finite");
        }
        return clamp(parsed);
    }

    private void saveQuietly() {
        Path temporaryFile = configDirectory.resolve(FILE_NAME + ".tmp");
        try {
            Files.createDirectories(configDirectory);
            Files.writeString(
                    temporaryFile,
                    VOLUME_PREFIX + Double.toString(volume) + "\n",
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE
            );
            try {
                Files.move(
                        temporaryFile,
                        configFile,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporaryFile, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failure) {
            LOGGER.warn("Could not save AreaMusic client config {}", configFile, failure);
        } finally {
            try {
                Files.deleteIfExists(temporaryFile);
            } catch (IOException cleanupFailure) {
                LOGGER.warn("Could not remove AreaMusic temporary config {}", temporaryFile, cleanupFailure);
            }
        }
    }

    private static double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
