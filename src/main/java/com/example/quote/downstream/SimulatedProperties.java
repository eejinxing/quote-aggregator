package com.example.quote.downstream;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "app.simulated-downstream")
public record SimulatedProperties(
        Duration priceLatency,
        Duration stockLatency,
        Duration shippingLatency,
        Duration slowLatency) {
    public SimulatedProperties {
        priceLatency = priceLatency != null ? priceLatency : Duration.ofMillis(80);
        stockLatency = stockLatency != null ? stockLatency : Duration.ofMillis(60);
        shippingLatency = shippingLatency != null ? shippingLatency : Duration.ofMillis(100);
        slowLatency = slowLatency != null ? slowLatency : Duration.ofSeconds(5);
    }
}
