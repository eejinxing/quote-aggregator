package com.example.quote.concurrent;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SingleFlightTest {
    @Test
    void concurrentCallersShareOneLoadAndTheNextCallStartsAFreshOne() {
        SingleFlight<String, String> flights = new SingleFlight<>();
        AtomicInteger loads = new AtomicInteger();
        CompletableFuture<String> source = new CompletableFuture<>();

        List<CompletableFuture<String>> callers = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            callers.add(flights.run("k", () -> {
                loads.incrementAndGet();
                return source;
            }));
        }
        assertThat(flights.inFlight()).isEqualTo(1);

        source.complete("v");

        assertThat(callers).allSatisfy(f -> assertThat(f.join()).isEqualTo("v"));
        assertThat(loads).hasValue(1);
        assertThat(flights.inFlight()).isZero();

        String fresh = flights.run("k", () -> {
            loads.incrementAndGet();
            return CompletableFuture.completedFuture("v2");
        }).join();
        assertThat(fresh).isEqualTo("v2");
        assertThat(loads).hasValue(2);
    }

    @Test
    void failureIsSharedAndDoesNotPoisonTheKey() {
        SingleFlight<String, String> flights = new SingleFlight<>();
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletableFuture<String> a = flights.run("k", () -> source);
        CompletableFuture<String> b = flights.run("k", () -> source);
        source.completeExceptionally(new IllegalStateException("boom"));

        assertThatThrownBy(a::join).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThatThrownBy(b::join).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(flights.inFlight()).isZero();
        assertThat(flights.run("k", () -> CompletableFuture.completedFuture("ok")).join()).isEqualTo("ok");
    }

    @Test
    void loaderThatThrowsSynchronouslyLeavesNoStaleEntry() {
        SingleFlight<String, String> flights = new SingleFlight<>();

        CompletableFuture<String> failed = flights.run("k", () -> {
            throw new IllegalStateException("sync failure");
        });

        assertThatThrownBy(failed::join).hasRootCauseInstanceOf(IllegalStateException.class);
        assertThat(flights.inFlight()).isZero();
    }

    @Test
    void oneCallerCancellingDoesNotCancelTheOthers() {
        SingleFlight<String, String> flights = new SingleFlight<>();
        CompletableFuture<String> source = new CompletableFuture<>();

        CompletableFuture<String> first = flights.run("k", () -> source);
        CompletableFuture<String> second = flights.run("k", () -> source);
        first.cancel(true);
        source.complete("v");

        assertThat(first).isCancelled();
        assertThat(second.join()).isEqualTo("v");
    }

    @Test
    void survivesHeavyContention() throws Exception {
        SingleFlight<Integer, Integer> flights = new SingleFlight<>();
        int threads = 8;
        int iterations = 5_000;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                tasks.add(pool.submit(() -> {
                    for (int i = 0; i < iterations; i++) {
                        int key = i % 7;
                        Integer result = flights.run(key, () -> CompletableFuture.supplyAsync(() -> key)).join();
                        assertThat(result).isEqualTo(key);
                    }
                }));
            }
            for (Future<?> task : tasks) {
                task.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(flights.inFlight()).isZero();
    }
}
