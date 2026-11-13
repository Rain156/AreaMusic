package datura.areamusic.network;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AreaMusicChannelRegistrationTest {
    private static final String DESCRIPTION = "AreaMusic network channel";

    @Test
    void successfulRegistrationBuildsOnceAndReusesTheChannel() {
        Object channel = new Object();
        AtomicInteger factoryCalls = new AtomicInteger();
        AreaMusicChannelRegistration<Object> registration = newRegistration(() -> {
            factoryCalls.incrementAndGet();
            return channel;
        });

        assertSame(channel, registration.register());
        assertSame(channel, registration.register());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void registrationFactoryRunsSynchronouslyOnTheCallingThread() {
        AtomicReference<Thread> factoryThread = new AtomicReference<>();
        AreaMusicChannelRegistration<Object> registration = newRegistration(() -> {
            factoryThread.set(Thread.currentThread());
            return new Object();
        });

        registration.register();

        assertSame(Thread.currentThread(), factoryThread.get());
    }

    @Test
    void runtimeFailureAfterSideEffectIsPermanentAndRetainsOriginalCause() {
        RuntimeException original = new RuntimeException("Forge registry already mutated");
        assertPermanentFailure(original);
    }

    @Test
    void linkageFailureAfterSideEffectIsPermanentAndRetainsOriginalCause() {
        LinkageError original = new LinkageError("Forge networking linkage failed");
        assertPermanentFailure(original);
    }

    @Test
    void concurrentSuccessfulCallsLinearizeAroundOneFactoryExecution() throws Exception {
        Object channel = new Object();
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AreaMusicChannelRegistration<Object> registration = newRegistration(() -> {
            factoryCalls.incrementAndGet();
            factoryEntered.countDown();
            await(releaseFactory);
            return channel;
        });
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Object> first = executor.submit(registration::register);
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS), "factory did not start");
            Future<Object> second = executor.submit(registration::register);
            releaseFactory.countDown();

            assertSame(channel, first.get(5, TimeUnit.SECONDS));
            assertSame(channel, second.get(5, TimeUnit.SECONDS));
            assertEquals(1, factoryCalls.get());
        } finally {
            releaseFactory.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private static void assertPermanentFailure(Throwable original) {
        AtomicInteger sideEffects = new AtomicInteger();
        AreaMusicChannelRegistration<Object> registration = newRegistration(() -> {
            sideEffects.incrementAndGet();
            if (original instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (LinkageError) original;
        });

        IllegalStateException first = assertThrows(
                IllegalStateException.class,
                registration::register
        );
        IllegalStateException second = assertThrows(
                IllegalStateException.class,
                registration::register
        );

        assertTrue(first.getMessage().contains("failed permanently"));
        assertTrue(second.getMessage().contains("failed permanently"));
        assertSame(original, first.getCause());
        assertSame(original, second.getCause());
        assertEquals(1, sideEffects.get(), "failed registration retried its dirty factory");
    }

    private static AreaMusicChannelRegistration<Object> newRegistration(
            Supplier<Object> factory
    ) {
        return new AreaMusicChannelRegistration<>(DESCRIPTION, factory);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting for registration test latch");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError("registration test was interrupted", interrupted);
        }
    }
}
