package datura.areamusic.sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Reads a file as a sequence of chunks. The size is fixed when the transfer starts. */
public final class OutgoingTransfer implements AutoCloseable {
    private final InputStream input;
    private final long size;
    private final int chunkBytes;
    private long offset;
    private boolean finished;

    public OutgoingTransfer(Path file, int chunkBytes) throws IOException {
        Objects.requireNonNull(file, "file");
        if (chunkBytes <= 0) {
            throw new IllegalArgumentException("chunkBytes must be positive");
        }
        this.chunkBytes = chunkBytes;
        size = Files.size(file);
        input = Files.newInputStream(file);
    }

    public long size() {
        return size;
    }

    /** Bytes handed out so far. */
    public long offset() {
        return offset;
    }

    public boolean finished() {
        return finished;
    }

    /**
     * @throws IOException if the file ends before {@link #size()} bytes, meaning it was truncated
     *                     or replaced after the transfer began
     */
    public Chunk next() throws IOException {
        if (finished) {
            throw new IllegalStateException("Transfer already finished");
        }
        int wanted = (int) Math.min(chunkBytes, size - offset);
        byte[] data = input.readNBytes(wanted);
        if (data.length != wanted) {
            throw new IOException("File changed while it was being sent");
        }
        Chunk chunk = new Chunk(offset, data, offset + wanted == size);
        offset += wanted;
        finished = chunk.last();
        return chunk;
    }

    @Override
    public void close() throws IOException {
        input.close();
    }

    public record Chunk(long offset, byte[] data, boolean last) {
    }
}
