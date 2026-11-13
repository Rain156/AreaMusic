package datura.areamusic.sync;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.Objects;

/**
 * Receives a file into a temporary path and only lets it be moved into place once its size and
 * SHA-256 match what was announced. Closing an unfinished or unmoved transfer deletes the
 * temporary file, so a failed transfer leaves nothing behind.
 */
public final class IncomingTransfer implements AutoCloseable {
    private final Path temporary;
    private final long expectedSize;
    private final String expectedSha256;
    private final MessageDigest digest = Sha256.newDigest();
    private OutputStream output;
    private long received;
    private boolean complete;
    private boolean closed;
    private boolean moved;

    public IncomingTransfer(Path temporary, long expectedSize, String expectedSha256) throws IOException {
        this.temporary = Objects.requireNonNull(temporary, "temporary").toAbsolutePath().normalize();
        this.expectedSha256 = Objects.requireNonNull(expectedSha256, "expectedSha256");
        if (expectedSize < 0) {
            throw new IllegalArgumentException("expectedSize must not be negative");
        }
        this.expectedSize = expectedSize;
        Files.createDirectories(this.temporary.getParent());
        output = Files.newOutputStream(
                this.temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    public void accept(long offset, byte[] data, boolean last) throws TransferException, IOException {
        Objects.requireNonNull(data, "data");
        if (closed || complete) {
            throw new TransferException(TransferException.Reason.CLOSED, "Transfer is already over");
        }
        try {
            if (offset != received) {
                throw new TransferException(
                        TransferException.Reason.OUT_OF_ORDER,
                        "Expected offset " + received + " but got " + offset);
            }
            if (received + data.length > expectedSize) {
                throw new TransferException(
                        TransferException.Reason.TOO_LARGE,
                        "Received more than the announced " + expectedSize + " bytes");
            }
            output.write(data);
            digest.update(data);
            received += data.length;
            if (!last) {
                return;
            }
            if (received != expectedSize) {
                throw new TransferException(
                        TransferException.Reason.SIZE_MISMATCH,
                        "Received " + received + " of " + expectedSize + " bytes");
            }
            if (!Sha256.hex(digest.digest()).equals(expectedSha256)) {
                throw new TransferException(
                        TransferException.Reason.HASH_MISMATCH, "Content does not match its SHA-256");
            }
            output.close();
            output = null;
            complete = true;
        } catch (TransferException | IOException exception) {
            close();
            throw exception;
        }
    }

    public boolean complete() {
        return complete;
    }

    public long received() {
        return received;
    }

    public long expectedSize() {
        return expectedSize;
    }

    /** The file being written; holds the verified content once {@link #complete()} is true. */
    public Path temporary() {
        return temporary;
    }

    /** Moves the verified file into place, replacing any file already there. */
    public Path moveTo(Path destination) throws IOException {
        if (!complete || moved) {
            throw new IllegalStateException("Transfer is not ready to be moved");
        }
        Path target = destination.toAbsolutePath().normalize();
        Files.createDirectories(target.getParent());
        try {
            Files.move(
                    temporary, target,
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
        moved = true;
        return target;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            if (output != null) {
                output.close();
                output = null;
            }
        } finally {
            if (!moved) {
                Files.deleteIfExists(temporary);
            }
        }
    }
}
