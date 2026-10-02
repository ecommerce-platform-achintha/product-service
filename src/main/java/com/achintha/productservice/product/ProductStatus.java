package com.achintha.productservice.product;

import java.util.Map;
import java.util.Set;

/** Product lifecycle. Only {@code ACTIVE} products can be public (and only when the store is visible). */
public enum ProductStatus {
    DRAFT,
    ACTIVE,
    ARCHIVED;

    private static final Map<ProductStatus, Set<ProductStatus>> ALLOWED = Map.of(
            DRAFT, Set.of(ACTIVE, ARCHIVED),
            ACTIVE, Set.of(DRAFT, ARCHIVED),
            // Unarchiving goes back to DRAFT first
            ARCHIVED, Set.of(DRAFT));

    public boolean canMoveTo(ProductStatus target) {
        return ALLOWED.get(this).contains(target);
    }
}
