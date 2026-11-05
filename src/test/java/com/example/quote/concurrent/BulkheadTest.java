package com.example.quote.concurrent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

class BulkheadTest {
    private final ExecutorService pool = Executors.newFixedThreadPool(4);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private static String awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        return "done";
    }

    @Test
    void rejectsExtraCallsImmediatelyAndFreesPermitsWhenTasksFinish() throws Exception {
        Bulkhead bulkhead = new Bulkhead("test", 2, metrics);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> a = bulkhead.submit(pool, () -> awaitLatch(release));
        CompletableFuture<String> b = bulkhead.submit(pool, () -> awaitLatch(release));
        CompletableFuture<String> c = bulkhead.submit(pool, () -> "never runs");

        assertThat(bulkhead.availablePermits()).isZero();
        assertThat(c).isCompletedExceptionally();
        assertThatThrownBy(() -> c.get(1, SECONDS)).hasRootCauseInstanceOf(BulkheadFullException.class);
        assertThat(metrics.get("bulkhead.rejected").tag("name", "test").counter().count()).isEqualTo(1.0);

        release.countDown();
        a.get(1, SECONDS);
        b.get(1, SECONDS);
        assertThat(bulkhead.availablePermits()).isEqualTo(2);
    }

    @Test
    void releasesThePermitWhenTheTaskFails() {
        Bulkhead bulkhead = new Bulkhead("test", 1, metrics);

        CompletableFuture<String> failing = bulkhead.submit(pool, () -> {
            throw new IllegalStateException("boom");
        });

        assertThatThrownBy(() -> failing.get(1, SECONDS)).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(bulkhead.availablePermits()).isEqualTo(1);
    }

    @Test
    void returnsThePermitWhenTheExecutorItselfRejectsTheTask() {
        ThreadPoolExecutor tiny = new ThreadPoolExecutor(
                1, 1, 0, TimeUnit.SECONDS, new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
        CountDownLatch release = new CountDownLatch(1);
        try {
            Bulkhead bulkhead = new Bulkhead("test", 5, metrics);
            CompletableFuture<String> running = bulkhead.submit(tiny, () -> awaitLatch(release));
            CompletableFuture<String> rejected = bulkhead.submit(tiny, () -> "never runs");

            assertThat(running).isNotDone();
            assertThatThrownBy(() -> rejected.get(1, SECONDS)).hasCauseInstanceOf(BulkheadFullException.class);

            assertThat(bulkhead.availablePermits()).isEqualTo(4);
        } finally {
            release.countDown();
            tiny.shutdownNow();
        }
    }

    @Test
    void aCallerTimeoutDoesNotFreeThePermitBecauseTheThreadIsStillBusy() {
        Bulkhead bulkhead = new Bulkhead("test", 1, metrics);
        CountDownLatch release = new CountDownLatch(1);

        CompletableFuture<String> call = bulkhead.submit(pool, () -> awaitLatch(release))
                .orTimeout(100, TimeUnit.MILLISECONDS);

        assertThatThrownBy(() -> call.get(2, SECONDS)).hasRootCauseInstanceOf(TimeoutException.class);
        assertThat(bulkhead.availablePermits()).isZero();

        release.countDown();
        await().atMost(2, SECONDS).untilAsserted(() -> assertThat(bulkhead.availablePermits()).isEqualTo(1));
    }
}
