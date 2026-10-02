package com.achintha.productservice.events;

import com.achintha.productservice.outbox.ProcessedEventStore;
import com.achintha.productservice.store.StoreVisibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@code StoreVisibilityChanged} from {@code store-events} to {@code store_visibility}. Idempotent via
 * {@code processed_events}; a late event never overwrites a newer state (event time guard).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StoreEventHandler {

    public static final String CONSUMER = "product-service.store-events";

    private final ProcessedEventStore processedEvents;
    private final StoreVisibilityService storeVisibility;

    /** @return {@code false} for a duplicate or an event this service does not use */
    @Transactional
    public boolean handle(StoreEvent event) {
        if (!"StoreVisibilityChanged".equals(event.eventType())) {
            return false;
        }
        if (event.eventId() == null || event.storeId() == null || event.visible() == null) {
            log.warn("Skipping StoreVisibilityChanged without eventId/storeId/visible");
            return false;
        }
        if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
            return false;
        }
        storeVisibility.applyStoreEvent(event.storeId(), event.storePublicId(), event.storeName(), event.visible(),
                event.codEnabled(), event.occurredAt());
        return true;
    }
}
