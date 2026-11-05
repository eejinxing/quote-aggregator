package com.example.quote.concurrent;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Supplier;

public final class SingleFlight<K, V> {
    private final ConcurrentMap<K, CompletableFuture<V>> inFlight = new ConcurrentHashMap<>();

    public CompletableFuture<V> run(K key, Supplier<CompletableFuture<V>> loader) {
        CompletableFuture<V> promise = new CompletableFuture<>();
        CompletableFuture<V> existing = inFlight.putIfAbsent(key, promise);
        if (existing != null) {
            return existing.copy();
        }

        try {
            loader.get().whenComplete((value, error) -> {
                inFlight.remove(key, promise);
                if (error != null) {
                    promise.completeExceptionally(error);
                } else {
                    promise.complete(value);
                }
            });
        } catch (Throwable t) {
            inFlight.remove(key, promise);
            promise.completeExceptionally(t);
        }
        return promise.copy();
    }

    public int inFlight() {
        return inFlight.size();
    }
}
