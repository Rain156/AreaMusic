package datura.areamusic.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioSignatureTest {
    @TempDir
    Path tempDir;

    @ParameterizedTest
    @CsvSource({"test.ogg,ogg", "test.mp3,mp3", "test.flac,flac"})
    void realAudioMatchesItsOwnExtensionOnly(String resource, String extension) throws Exception {
        Path file = tempDir.resolve(resource);
        try (InputStream input = AudioSignatureTest.class.getResourceAsStream(
                "/datura/areamusic/audio/" + resource)) {
            assertNotNull(input);
            Files.copy(input, file);
        }

        assertTrue(AudioSignature.matches(file, extension));
        assertTrue(AudioSignature.matches(file, extension.toUpperCase()));
        assertFalse(AudioSignature.matches(file, "wav"));
        assertFalse(AudioSignature.matches(file, "exe"));
    }

    @Test
    void wavNeedsBothRiffAndWaveMarkers() throws Exception {
        Path wav = tempDir.resolve("a.wav");
        Files.write(wav, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E', 1, 2});
        Path avi = tempDir.resolve("b.wav");
        Files.write(avi, new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'A', 'V', 'I', ' '});

        assertTrue(AudioSignature.matches(wav, "wav"));
        assertFalse(AudioSignature.matches(avi, "wav"));
    }

    @Test
    void textShortAndMissingFilesNeverMatch() throws Exception {
        Path text = tempDir.resolve("fake.ogg");
        Files.writeString(text, "#!/bin/sh\necho not audio\n");
        Path tiny = tempDir.resolve("tiny.mp3");
        Files.write(tiny, new byte[]{(byte) 0xFF});

        for (String extension : new String[]{"ogg", "mp3", "wav", "flac"}) {
            assertFalse(AudioSignature.matches(text, extension));
            assertFalse(AudioSignature.matches(tiny, extension));
            assertFalse(AudioSignature.matches(tempDir.resolve("missing"), extension));
        }
    }

    @Test
    void installRefusesContentThatDoesNotMatchItsName() throws Exception {
        byte[] content = "plain text pretending to be a song".getBytes();
        IncomingTransfer transfer = new IncomingTransfer(
                tempDir.resolve("uploads/.x.ogg"), content.length,
                Sha256.hex(Sha256.newDigest().digest(content)));
        transfer.accept(0, content, true);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> UploadFinisher.install(transfer, "song.ogg", tempDir.resolve("library"), name -> false));

        assertTrue(error.getMessage().equals(UploadFinisher.NOT_AUDIO));
        assertFalse(Files.exists(tempDir.resolve("library/song.ogg")));
        assertFalse(Files.exists(tempDir.resolve("uploads/.x.ogg")));
    }
}
