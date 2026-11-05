package com.example.quote.concurrent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.function.Supplier;

public final class Bulkhead {
    private final String name;
    private final int maxConcurrent;
    private final Semaphore permits;
    private final Counter rejected;

    public Bulkhead(String name, int maxConcurrent, MeterRegistry registry) {
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("maxConcurrent must be >= 1");
        }
        this.name = name;
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent);
        this.rejected = Counter.builder("bulkhead.rejected").tag("name", name).register(registry);
        Gauge.builder("bulkhead.available", permits, Semaphore::availablePermits)
                .tag("name", name)
                .register(registry);
    }

    public <T> CompletableFuture<T> submit(Executor executor, Supplier<T> task) {
        if (!permits.tryAcquire()) {
            rejected.increment();
            return CompletableFuture.failedFuture(new BulkheadFullException(name));
        }
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return task.get();
                } finally {
                    permits.release();
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            permits.release();
            rejected.increment();
            return CompletableFuture.failedFuture(new BulkheadFullException(name, e));
        }
    }

    public String name() {
        return name;
    }

    public int maxConcurrent() {
        return maxConcurrent;
    }

    public int availablePermits() {
        return permits.availablePermits();
    }
}
