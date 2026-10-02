package com.achintha.productservice.events;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The fields product-service reads from {@code order-events} messages (section 9). Unknown fields are ignored, so
 * order-service may send more.
 *
 * @param lines the order's items (variant UUID and/or publicId, quantity)
 */
public record OrderEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID orderId,
        String orderPublicId,
        UUID storeId,
        UUID customerId,
        String status,
        List<Line> lines) {

    public record Line(UUID variantId, String variantPublicId, Integer quantity) {
    }
}
