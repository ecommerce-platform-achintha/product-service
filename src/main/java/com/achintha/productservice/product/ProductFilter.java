package com.achintha.productservice.product;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Public search filters; every non-null field narrows the result and they combine with AND. Only visible products
 * are ever searched (active, visible store, at least one active variant).
 *
 * @param categoryIds a category and its sub-categories
 * @param minPrice    on the effective "from" price (cheapest active variant after its discount)
 * @param inStock     at least one active variant has stock available
 */
public record ProductFilter(
        String q,
        List<UUID> categoryIds,
        UUID storeId,
        BigDecimal minPrice,
        BigDecimal maxPrice,
        boolean inStock,
        BigDecimal minRating) {

    public static ProductFilter none() {
        return new ProductFilter(null, null, null, null, null, false, null);
    }
}
