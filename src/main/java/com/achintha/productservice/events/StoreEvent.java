package com.achintha.productservice.events;

import java.time.Instant;
import java.util.UUID;

/**
 * The fields product-service reads from {@code store-events} messages (published by store-service). Unknown fields
 * are ignored.
 *
 * @param visible {@code published && acceptingOrders}: the store may be listed and may take new orders
 */
public record StoreEvent(
        UUID eventId,
        String eventType,
        Instant occurredAt,
        UUID storeId,
        String storePublicId,
        String storeName,
        Boolean visible,
        Boolean codEnabled,
        String change) {
}
