package datura.areamusic.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UploadNamesTest {
    @Test
    void keepsOnlyTheFileNameAndLowercasesTheExtension() {
        assertEquals("Day Theme.ogg", UploadNames.sanitize("C:\\Users\\me\\Music\\Day Theme.OGG"));
        assertEquals("passwd.ogg", UploadNames.sanitize("../../etc/passwd.ogg"));
        assertEquals("day.v2.mp3", UploadNames.sanitize("/home/me/day.v2.MP3"));
    }

    @Test
    void keepsUnicodeNames() {
        assertEquals("村庄.mp3", UploadNames.sanitize("村庄.mp3"));
        assertEquals("夜 🎵.flac", UploadNames.sanitize("夜 🎵.flac"));
    }

    @Test
    void replacesCharactersThatAreUnsafeOnSomePlatforms() {
        assertEquals("a_b_.wav", UploadNames.sanitize("a<b>.wav"));
        assertEquals("a_b.ogg", UploadNames.sanitize("a\tb.ogg"));
        assertEquals("what_.ogg", UploadNames.sanitize("what?.ogg"));
    }

    @Test
    void stripsLeadingDotsTrailingDotsAndEdgeWhitespace() {
        assertEquals("hidden.flac", UploadNames.sanitize(".hidden.flac"));
        assertEquals("hidden.flac", UploadNames.sanitize("..  hidden .flac"));
        assertEquals("track.ogg", UploadNames.sanitize("  track. .ogg"));
    }

    @Test
    void prefixesWindowsReservedNames() {
        assertEquals("_nul.ogg", UploadNames.sanitize("nul.ogg"));
        assertEquals("_COM1.mp3", UploadNames.sanitize("COM1.mp3"));
        assertEquals("null.ogg", UploadNames.sanitize("null.ogg"));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"song.exe", ".ogg", "noext", "", "trailingdot.", "...ogg", " . .ogg", "song.ogg.txt"})
    void rejectsNamesThatCannotBecomeAMusicId(String fileName) {
        assertThrows(IllegalArgumentException.class, () -> UploadNames.sanitize(fileName));
    }

    @Test
    void shortensOverlongNamesButKeepsTheExtension() {
        String sanitized = UploadNames.sanitize("x".repeat(200) + ".flac");
        String emoji = UploadNames.sanitize("🎵".repeat(100) + ".ogg");

        assertEquals(UploadNames.MAX_LENGTH, sanitized.length());
        assertTrue(sanitized.endsWith(".flac"));
        assertTrue(emoji.length() <= UploadNames.MAX_LENGTH);
        assertTrue(emoji.endsWith(".ogg"));
        assertFalse(Character.isHighSurrogate(emoji.charAt(emoji.length() - 5)));
    }

    @Test
    void uniqueAppendsACounterBeforeTheExtension() {
        assertEquals("day.ogg", UploadNames.unique("day.ogg", name -> false));
        assertEquals("day-2.ogg", UploadNames.unique("day.ogg", Set.of("day.ogg")::contains));
        assertEquals("day-4.ogg",
                UploadNames.unique("day.ogg", Set.of("day.ogg", "day-2.ogg", "day-3.ogg")::contains));
    }
}
