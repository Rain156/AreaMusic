package datura.areamusic.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Checks a file's leading bytes against its extension. Unlike {@link AudioProbe} this needs no
 * decoder, so a dedicated server can use it without touching the client audio code.
 */
public final class AudioSignature {
    private AudioSignature() {
    }

    public static boolean matches(Path file, String extension) {
        byte[] head = new byte[12];
        int read;
        try (InputStream input = Files.newInputStream(file)) {
            read = input.readNBytes(head, 0, head.length);
        } catch (IOException exception) {
            return false;
        }
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "ogg" -> startsWith(head, read, "OggS");
            case "flac" -> startsWith(head, read, "fLaC") || startsWith(head, read, "ID3");
            case "wav" -> startsWith(head, read, "RIFF") && read >= 12
                    && head[8] == 'W' && head[9] == 'A' && head[10] == 'V' && head[11] == 'E';
            case "mp3" -> startsWith(head, read, "ID3")
                    || read >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0;
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, int read, String magic) {
        if (read < magic.length()) {
            return false;
        }
        for (int index = 0; index < magic.length(); index++) {
            if (head[index] != (byte) magic.charAt(index)) {
                return false;
            }
        }
        return true;
    }
}
