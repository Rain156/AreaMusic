package datura.areamusic.client.audio;

import javax.sound.sampled.AudioInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public final class AudioStreamPreparer implements AutoCloseable {
    private static final int FRAME_SIZE = AudioStreamFactory.MIX_FORMAT.getFrameSize();
    private static final int MAX_CONSECUTIVE_ZERO_READS = 64;
    private static final int DISCARD_BUFFER_BYTES = 8192;
    private static final AtomicInteger POOL_IDS = new AtomicInteger();

    private final Object lifecycleLock = new Object();
    private final AudioStreamFactory streamFactory;
    private final ExecutorService executor;
    private final Set<Preparation> pending = ConcurrentHashMap.newKeySet();
    private boolean closed;

    public AudioStreamPreparer(AudioStreamFactory streamFactory, int workerCount) {
        this.streamFactory = Objects.requireNonNull(streamFactory, "streamFactory");
        if (workerCount < 1) {
            throw new IllegalArgumentException("workerCount must be at least one");
        }
        int poolId = POOL_IDS.incrementAndGet();
        AtomicInteger workerIds = new AtomicInteger();
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "AreaMusic stream preparer " + poolId + "-" + workerIds.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
        executor = Executors.newFixedThreadPool(workerCount, threadFactory);
    }

    public CompletableFuture<AudioInputStream> prepare(Path path, long frameOffset) {
        Objects.requireNonNull(path, "path");
        if (frameOffset < 0L) {
            throw new IllegalArgumentException("frameOffset must not be negative");
        }
        long offsetBytes = Math.multiplyExact(frameOffset, (long) FRAME_SIZE);
        Preparation preparation = new Preparation(path, offsetBytes);
        preparation.future.whenComplete((ignored, failure) -> pending.remove(preparation));

        synchronized (lifecycleLock) {
            if (closed) {
                throw new RejectedExecutionException("Audio stream preparer is closed");
            }
            pending.add(preparation);
            try {
                preparation.setTask(executor.submit(() -> runPreparation(preparation)));
            } catch (RejectedExecutionException exception) {
                pending.remove(preparation);
                throw exception;
            }
        }
        return preparation.future;
    }

    @Override
    public void close() {
        List<Preparation> toCancel;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            toCancel = new ArrayList<>(pending);
        }
        for (Preparation preparation : toCancel) {
            preparation.future.cancel(true);
        }
        executor.shutdownNow();
    }

    private void runPreparation(Preparation preparation) {
        try {
            if (preparation.future.isCancelled()) {
                return;
            }
            AudioInputStream stream = streamFactory.open(preparation.path);
            if (!preparation.registerOpenedStream(stream)) {
                return;
            }
            skipExactly(stream, preparation.offsetBytes, preparation.future);
            if (preparation.future.complete(stream)) {
                preparation.transferOwnership(stream);
            }
        } catch (Throwable failure) {
            if (!preparation.future.isCancelled()) {
                preparation.future.completeExceptionally(failure);
            }
        } finally {
            preparation.closeOpenedStream();
        }
    }

    private static void skipExactly(
            AudioInputStream stream,
            long offsetBytes,
            CompletableFuture<AudioInputStream> future
    ) throws IOException {
        long remaining = offsetBytes;
        byte[] discard = new byte[DISCARD_BUFFER_BYTES];
        int consecutiveZeroReads = 0;
        while (remaining > 0L) {
            if (future.isCancelled() || Thread.currentThread().isInterrupted()) {
                throw new IOException("Interrupted while preparing audio stream offset");
            }

            long skipped = stream.skip(remaining);
            if (skipped < 0L || skipped > remaining) {
                throw new IOException("Decoder returned invalid skip count " + skipped);
            }
            if (skipped > 0L) {
                requireFrameAligned(skipped, "skip");
                remaining -= skipped;
                consecutiveZeroReads = 0;
                continue;
            }

            int requested = (int) Math.min(remaining, (long) discard.length);
            int read = stream.read(discard, 0, requested);
            if (read < 0) {
                throw new EOFException(
                        "Reached end of decoded stream before requested frame offset"
                );
            }
            if (read == 0) {
                consecutiveZeroReads++;
                if (consecutiveZeroReads > MAX_CONSECUTIVE_ZERO_READS) {
                    throw new IOException(
                            "Decoder returned zero bytes " + consecutiveZeroReads
                                    + " consecutive times while preparing offset"
                    );
                }
                continue;
            }
            if (read > requested) {
                throw new IOException("Decoder returned invalid read count " + read);
            }
            requireFrameAligned(read, "read");
            remaining -= read;
            consecutiveZeroReads = 0;
        }
    }

    private static void requireFrameAligned(long bytes, String operation) throws IOException {
        if (bytes % FRAME_SIZE != 0L) {
            throw new IOException(
                    "Decoder " + operation + " count " + bytes
                            + " is not aligned to PCM frame size " + FRAME_SIZE
            );
        }
    }

    private static void closeQuietly(AudioInputStream stream) {
        if (stream == null) {
            return;
        }
        try {
            stream.close();
        } catch (IOException ignored) {
        }
    }

    private final class Preparation {
        private final Path path;
        private final long offsetBytes;
        private final AtomicReference<Future<?>> task = new AtomicReference<>();
        private final AtomicReference<AudioInputStream> openedStream = new AtomicReference<>();
        private final AtomicBoolean interruptRequested = new AtomicBoolean();
        private final PreparationFuture future = new PreparationFuture(this);

        private Preparation(Path path, long offsetBytes) {
            this.path = path;
            this.offsetBytes = offsetBytes;
        }

        private void setTask(Future<?> submitted) {
            task.set(submitted);
            if (future.isCancelled()) {
                submitted.cancel(interruptRequested.get());
            }
        }

        private void cancelTask(boolean mayInterruptIfRunning) {
            if (mayInterruptIfRunning) {
                interruptRequested.set(true);
            }
            closeOpenedStream();
            Future<?> submitted = task.get();
            if (submitted != null) {
                submitted.cancel(mayInterruptIfRunning);
            }
        }

        private boolean registerOpenedStream(AudioInputStream stream) {
            openedStream.set(Objects.requireNonNull(stream, "streamFactory result"));
            if (future.isCancelled()) {
                closeOpenedStream();
                return false;
            }
            return true;
        }

        private void transferOwnership(AudioInputStream stream) {
            openedStream.compareAndSet(stream, null);
        }

        private void closeOpenedStream() {
            closeQuietly(openedStream.getAndSet(null));
        }
    }

    private static final class PreparationFuture extends CompletableFuture<AudioInputStream> {
        private final Preparation preparation;

        private PreparationFuture(Preparation preparation) {
            this.preparation = preparation;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean cancelled = super.cancel(mayInterruptIfRunning);
            if (cancelled) {
                preparation.cancelTask(mayInterruptIfRunning);
            }
            return cancelled;
        }
    }
}
