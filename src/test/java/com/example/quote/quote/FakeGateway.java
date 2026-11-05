package com.example.quote.quote;

import com.example.quote.downstream.DownstreamException;
import com.example.quote.downstream.ProductGateway;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

final class FakeGateway implements ProductGateway {
    final AtomicInteger priceCalls = new AtomicInteger();
    volatile Duration delay = Duration.ZERO;
    volatile boolean priceFails;
    volatile boolean stockFails;
    volatile CountDownLatch priceGate;

    @Override
    public BigDecimal price(String sku) {
        priceCalls.incrementAndGet();
        if (priceFails) {
            throw new DownstreamException("price down");
        }
        pause();
        awaitGate(priceGate);
        return new BigDecimal("9.99");
    }

    @Override
    public int stock(String sku) {
        if (stockFails) {
            throw new DownstreamException("stock down");
        }
        pause();
        return 5;
    }

    @Override
    public int shippingDays(String sku) {
        pause();
        return 2;
    }

    private void pause() {
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException("interrupted", e);
        }
    }

    private static void awaitGate(CountDownLatch gate) {
        if (gate == null) {
            return;
        }
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException("interrupted", e);
        }
    }
}
