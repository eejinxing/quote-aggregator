package com.example.quote.downstream;

import java.math.BigDecimal;

public interface ProductGateway {
    BigDecimal price(String sku);

    int stock(String sku);

    int shippingDays(String sku);
}
