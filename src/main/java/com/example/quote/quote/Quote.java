package com.example.quote.quote;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quote(
        String sku,
        BigDecimal price,
        Integer stock,
        Integer shippingDays,
        List<String> degradedFields) {
    @JsonProperty("degraded")
    public boolean degraded() {
        return !degradedFields.isEmpty();
    }
}
