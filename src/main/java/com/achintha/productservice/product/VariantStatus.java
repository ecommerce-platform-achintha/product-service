package com.achintha.productservice.product;

/** A variant is sold only when {@code ACTIVE}. Variants are never deleted (orders, reviews and holds refer to them). */
public enum VariantStatus {
    ACTIVE,
    INACTIVE
}
