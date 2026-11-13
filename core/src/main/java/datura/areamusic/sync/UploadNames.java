package datura.areamusic.sync;

import datura.areamusic.music.MusicLibrary;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Turns a file name from a player's computer into a MusicID that is safe on every platform. */
public final class UploadNames {
    public static final int MAX_LENGTH = 120;
    private static final String FORBIDDEN = "<>:\"|?*";
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9"
    );

    private UploadNames() {
    }

    /**
     * @return a MusicID in the library root, such as {@code Day Theme.ogg}
     * @throws IllegalArgumentException if the name has no usable base name or is not a supported
     *                                  audio type
     */
    public static String sanitize(String fileName) {
        if (fileName == null) {
            throw new IllegalArgumentException("File name is missing");
        }
        String name = fileName.substring(
                Math.max(fileName.lastIndexOf('/'), fileName.lastIndexOf('\\')) + 1);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            throw new IllegalArgumentException("File has no extension: " + name);
        }
        String extension = name.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (!MusicLibrary.SUPPORTED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("Unsupported audio type: ." + extension);
        }

        StringBuilder cleaned = new StringBuilder();
        name.substring(0, dot).codePoints().forEach(codePoint -> {
            boolean unsafe = Character.isISOControl(codePoint) || FORBIDDEN.indexOf(codePoint) >= 0;
            cleaned.appendCodePoint(unsafe ? '_' : codePoint);
        });
        String base = cleaned.toString().strip();
        while (base.startsWith(".")) {
            base = base.substring(1).strip();
        }
        while (base.endsWith(".")) {
            base = base.substring(0, base.length() - 1).strip();
        }
        if (base.isEmpty()) {
            throw new IllegalArgumentException("File has no name before its extension");
        }
        if (RESERVED.contains(base.toLowerCase(Locale.ROOT))) {
            base = "_" + base;
        }
        int maxBase = MAX_LENGTH - extension.length() - 1;
        if (base.length() > maxBase) {
            int end = maxBase;
            if (Character.isHighSurrogate(base.charAt(end - 1))) {
                end--;
            }
            base = base.substring(0, end).strip();
        }
        return base + "." + extension;
    }

    /** Appends {@code -2}, {@code -3}, ... before the extension until the name is free. */
    public static String unique(String musicId, Predicate<String> taken) {
        Objects.requireNonNull(musicId, "musicId");
        Objects.requireNonNull(taken, "taken");
        if (!taken.test(musicId)) {
            return musicId;
        }
        int dot = musicId.lastIndexOf('.');
        String base = musicId.substring(0, dot);
        String extension = musicId.substring(dot);
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix + extension;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }
}
