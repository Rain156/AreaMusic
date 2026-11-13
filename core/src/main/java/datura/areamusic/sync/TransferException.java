package datura.areamusic.sync;

import java.util.Objects;

/** The received data cannot be the file that was announced. The transfer is over. */
public final class TransferException extends Exception {
    private static final long serialVersionUID = 1L;

    private final Reason reason;

    public TransferException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        OUT_OF_ORDER,
        TOO_LARGE,
        SIZE_MISMATCH,
        HASH_MISMATCH,
        CLOSED
    }
}
