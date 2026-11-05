package com.example.quote.quote;

import com.example.quote.concurrent.BulkheadRegistry;
import com.example.quote.concurrent.SingleFlight;
import com.example.quote.concurrent.Throwables;
import com.example.quote.config.ConcurrencyProperties;
import com.example.quote.downstream.ProductGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static com.example.quote.config.ExecutorConfig.IO_EXECUTOR;

@Service
public class QuoteService {
    private static final Logger log = LoggerFactory.getLogger(QuoteService.class);

    private final ProductGateway gateway;
    private final Executor ioExecutor;
    private final BulkheadRegistry bulkheads;
    private final MeterRegistry metrics;
    private final long callTimeoutMs;
    private final long totalTimeoutMs;
    private final SingleFlight<String, Quote> flights = new SingleFlight<>();

    public QuoteService(ProductGateway gateway,
                        @Qualifier(IO_EXECUTOR) Executor ioExecutor,
                        BulkheadRegistry bulkheads,
                        ConcurrencyProperties props,
                        MeterRegistry metrics) {
        this.gateway = gateway;
        this.ioExecutor = ioExecutor;
        this.bulkheads = bulkheads;
        this.metrics = metrics;
        this.callTimeoutMs = props.quote().callTimeout().toMillis();
        this.totalTimeoutMs = props.quote().totalTimeout().toMillis();
    }

    public CompletableFuture<Quote> getQuote(String sku) {
        return flights.run(sku, () -> load(sku));
    }

    private CompletableFuture<Quote> load(String sku) {
        CompletableFuture<BigDecimal> price = call("price", () -> gateway.price(sku));
        CompletableFuture<Optional<Integer>> stock = optional("stock", call("stock", () -> gateway.stock(sku)));
        CompletableFuture<Optional<Integer>> shipping =
                optional("shippingDays", call("shipping", () -> gateway.shippingDays(sku)));

        return CompletableFuture.allOf(price, stock, shipping)
                .thenApply(ignored -> assemble(sku, price.join(), stock.join(), shipping.join()))
                .orTimeout(totalTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private <T> CompletableFuture<T> call(String bulkheadName, Supplier<T> work) {
        return bulkheads.get(bulkheadName)
                .submit(ioExecutor, work)
                .orTimeout(callTimeoutMs, TimeUnit.MILLISECONDS);
    }

    private <T> CompletableFuture<Optional<T>> optional(String field, CompletableFuture<T> future) {
        return future.handle((value, error) -> {
            if (error == null) {
                return Optional.ofNullable(value);
            }
            log.warn("optional field '{}' unavailable: {}", field, Throwables.unwrap(error).toString());
            metrics.counter("quote.degraded", "field", field).increment();
            return Optional.<T>empty();
        });
    }

    private static Quote assemble(String sku, BigDecimal price, Optional<Integer> stock, Optional<Integer> shipping) {
        List<String> degraded = new ArrayList<>(2);
        if (stock.isEmpty()) {
            degraded.add("stock");
        }
        if (shipping.isEmpty()) {
            degraded.add("shippingDays");
        }
        return new Quote(sku, price, stock.orElse(null), shipping.orElse(null), List.copyOf(degraded));
    }
}
