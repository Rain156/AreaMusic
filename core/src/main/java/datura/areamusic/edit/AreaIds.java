package datura.areamusic.edit;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Generates area IDs so that nobody has to type one. */
public final class AreaIds {
    public static final String FALLBACK = "area";
    private static final int MAX_BASE_LENGTH = 48;

    private AreaIds() {
    }

    /**
     * @param hint  usually a MusicID such as {@code village/Day Theme.ogg}; may be null
     * @param taken tells whether an ID is already in use
     */
    public static String generate(String hint, Predicate<String> taken) {
        Objects.requireNonNull(taken, "taken");
        String base = slug(hint);
        if (!taken.test(base)) {
            return base;
        }
        for (int suffix = 2; ; suffix++) {
            String candidate = base + "-" + suffix;
            if (!taken.test(candidate)) {
                return candidate;
            }
        }
    }

    static String slug(String hint) {
        if (hint == null) {
            return FALLBACK;
        }
        String name = hint.substring(hint.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        if (dot >= 0) {
            name = name.substring(0, dot);
        }
        StringBuilder slug = new StringBuilder();
        boolean pendingSeparator = false;
        for (char character : name.toLowerCase(Locale.ROOT).toCharArray()) {
            boolean allowed = character >= 'a' && character <= 'z'
                    || character >= '0' && character <= '9'
                    || character == '_' || character == '-';
            if (!allowed) {
                pendingSeparator = true;
                continue;
            }
            if (pendingSeparator && slug.length() > 0) {
                slug.append('-');
            }
            pendingSeparator = false;
            slug.append(character);
            if (slug.length() >= MAX_BASE_LENGTH) {
                break;
            }
        }
        int start = 0;
        int end = Math.min(slug.length(), MAX_BASE_LENGTH);
        while (start < end && isEdgeSeparator(slug.charAt(start))) {
            start++;
        }
        while (end > start && isEdgeSeparator(slug.charAt(end - 1))) {
            end--;
        }
        return start == end ? FALLBACK : slug.substring(start, end);
    }

    private static boolean isEdgeSeparator(char character) {
        return character == '-' || character == '_';
    }
}
