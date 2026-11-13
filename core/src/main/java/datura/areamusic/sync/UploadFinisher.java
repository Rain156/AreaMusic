package datura.areamusic.sync;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Predicate;

/** Last step of an upload: make sure it really is audio, then move it into the music library. */
public final class UploadFinisher {
    /** Rejection code sent to the uploader when the file cannot be decoded. */
    public static final String NOT_AUDIO = "not_audio";

    private UploadFinisher() {
    }

    /**
     * Blocking; call off the game thread.
     *
     * @param musicIdHint the sanitized file name the uploader asked for
     * @param taken       whether a MusicID already exists; called with lowercase names
     * @return the MusicID the file was stored under
     * @throws IllegalArgumentException with {@link #NOT_AUDIO} if the content is not playable
     */
    public static String install(
            IncomingTransfer transfer, String musicIdHint, Path libraryRoot, Predicate<String> taken
    ) throws IOException {
        Objects.requireNonNull(transfer, "transfer");
        Objects.requireNonNull(libraryRoot, "libraryRoot");
        try (transfer) {
            if (!transfer.complete()) {
                throw new IllegalStateException("Upload is not complete");
            }
            if (!AudioProbe.isDecodable(transfer.temporary())) {
                throw new IllegalArgumentException(NOT_AUDIO);
            }
            String musicId = UploadNames.unique(
                    musicIdHint, name -> taken.test(name.toLowerCase(Locale.ROOT)));
            Path root = libraryRoot.toAbsolutePath().normalize();
            Path target = root.resolve(musicId).normalize();
            if (!target.startsWith(root)) {
                throw new IllegalArgumentException("bad_name");
            }
            transfer.moveTo(target);
            return musicId;
        }
    }
}
