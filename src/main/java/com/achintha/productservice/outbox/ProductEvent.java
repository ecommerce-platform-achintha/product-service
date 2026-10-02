package com.achintha.productservice.outbox;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * JSON payload of every {@code product-events} message, keyed by {@code aggregateId} (the product's UUID).
 * No stock counts and no prices after discounts (those are evaluated at read time; ask {@code /internal/variants/quote}).
 *
 * @param change {@code CREATED}, {@code UPDATED} or {@code STATUS_CHANGED}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ProductEvent(
        UUID eventId,
        ProductEventType eventType,
        Instant occurredAt,
        UUID aggregateId,
        UUID productId,
        String productPublicId,
        UUID storeId,
        String name,
        String status,
        String previousStatus,
        String categorySlug,
        Boolean codAllowed,
        List<VariantInfo> variants,
        String change) {

    public record VariantInfo(UUID variantId, String variantPublicId, String sku, BigDecimal listPrice,
                              String status) {
    }
}
