package datura.areamusic.sync;

public final class TransferLimits {
    /** Vanilla caps a client-to-server custom payload at 32767 bytes; leave room for the header. */
    public static final int UPLOAD_CHUNK_BYTES = 30_000;
    public static final int DOWNLOAD_CHUNK_BYTES = 32_768;
    public static final long DEFAULT_MAX_UPLOAD_BYTES = 32L << 20;

    private TransferLimits() {
    }
}
