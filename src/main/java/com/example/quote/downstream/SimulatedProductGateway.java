package com.example.quote.downstream;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;

@Component
public class SimulatedProductGateway implements ProductGateway {
    private final SimulatedProperties props;

    public SimulatedProductGateway(SimulatedProperties props) {
        this.props = props;
    }

    @Override
    public BigDecimal price(String sku) {
        simulate(sku, props.priceLatency(), "FAIL");
        return BigDecimal.valueOf(1000 + Math.floorMod(sku.hashCode(), 9000), 2);
    }

    @Override
    public int stock(String sku) {
        simulate(sku, props.stockLatency(), "FAIL", "NOSTOCK");
        return Math.floorMod(sku.hashCode(), 500);
    }

    @Override
    public int shippingDays(String sku) {
        simulate(sku, props.shippingLatency(), "FAIL", "NOSHIP");
        return 1 + Math.floorMod(sku.hashCode(), 7);
    }

    private void simulate(String sku, Duration latency, String... failingPrefixes) {
        String normalized = sku.toUpperCase(Locale.ROOT);
        for (String prefix : failingPrefixes) {
            if (normalized.startsWith(prefix)) {
                throw new DownstreamException("simulated failure for " + sku);
            }
        }
        sleep(normalized.startsWith("SLOW") ? props.slowLatency() : latency);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DownstreamException("interrupted while calling downstream", e);
        }
    }
}
