package com.example.quote;

import com.example.quote.config.ConcurrencyProperties;

import java.time.Duration;
import java.util.Map;

public final class TestFixtures {
    private TestFixtures() {
    }

    public static ConcurrencyProperties props(int price, int stock, int shipping,
                                              Duration callTimeout, Duration totalTimeout) {
        return new ConcurrencyProperties(
                new ConcurrencyProperties.Pool(8, 8, 0, Duration.ofSeconds(30), Duration.ofSeconds(5)),
                new ConcurrencyProperties.QuoteTimeouts(callTimeout, totalTimeout),
                Map.of("price", price, "stock", stock, "shipping", shipping));
    }
}
