package com.example.quote.concurrent;

import com.example.quote.config.ConcurrencyProperties;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.stream.Collectors;

@Component
public class BulkheadRegistry {
    private final Map<String, Bulkhead> bulkheads;

    public BulkheadRegistry(ConcurrencyProperties props, MeterRegistry registry) {
        this.bulkheads = props.bulkheads().entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        e -> new Bulkhead(e.getKey(), e.getValue(), registry)));
    }

    public Bulkhead get(String name) {
        Bulkhead bulkhead = bulkheads.get(name);
        if (bulkhead == null) {
            throw new IllegalArgumentException("No bulkhead configured for '" + name + "'");
        }
        return bulkhead;
    }
}
