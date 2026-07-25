package datura.areamusic.client;

import datura.areamusic.client.audio.AudioFailure;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ClientErrorMessageTest {
    @Test
    void mapsFailuresToLocalizedMessagesWithoutRawExceptionDetails() {
        Throwable sensitive = new IllegalStateException("C:\\Users\\player\\secret.mp3");
        Map<AudioFailure.Kind, String> expectedKeys = Map.of(
                AudioFailure.Kind.MISSING_FILE, "message.areamusic.missing_file",
                AudioFailure.Kind.DECODE, "message.areamusic.decode_failed",
                AudioFailure.Kind.DEVICE, "message.areamusic.device_failed",
                AudioFailure.Kind.SCAN, "message.areamusic.scan_failed",
                AudioFailure.Kind.THREAD, "message.areamusic.thread_failed"
        );

        for (Map.Entry<AudioFailure.Kind, String> entry : expectedKeys.entrySet()) {
            ClientErrorMessage message = ClientErrorMessage.from(
                    new AudioFailure(entry.getKey(), "music/test.mp3", sensitive)
            );

            assertEquals(entry.getValue(), message.translationKey());
            assertFalse(message.arguments().toString().contains(sensitive.getMessage()));
        }
    }
}
