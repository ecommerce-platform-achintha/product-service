package com.achintha.productservice.product;

import java.math.BigDecimal;
import java.util.UUID;

/** Optional list filters; every non-null field narrows the result and they combine with AND. */
public record ProductFilter(UUID categoryId, BigDecimal minPrice, BigDecimal maxPrice, String q) {

    public static ProductFilter none() {
        return new ProductFilter(null, null, null, null);
    }
}
