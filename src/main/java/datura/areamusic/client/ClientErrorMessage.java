package datura.areamusic.client;

import datura.areamusic.client.audio.AudioFailure;

import java.util.List;
import java.util.Objects;

public record ClientErrorMessage(String translationKey, List<Object> arguments) {
    public ClientErrorMessage {
        translationKey = Objects.requireNonNull(translationKey, "translationKey");
        arguments = List.copyOf(arguments);
    }

    public static ClientErrorMessage from(AudioFailure failure) {
        Objects.requireNonNull(failure, "failure");
        return switch (failure.kind()) {
            case MISSING_FILE -> new ClientErrorMessage(
                    "message.areamusic.missing_file", List.of(failure.musicId())
            );
            case DECODE -> new ClientErrorMessage(
                    "message.areamusic.decode_failed", List.of(failure.musicId())
            );
            case DEVICE -> new ClientErrorMessage("message.areamusic.device_failed", List.of());
            case SCAN -> new ClientErrorMessage("message.areamusic.scan_failed", List.of());
            case THREAD -> new ClientErrorMessage("message.areamusic.thread_failed", List.of());
        };
    }
}
