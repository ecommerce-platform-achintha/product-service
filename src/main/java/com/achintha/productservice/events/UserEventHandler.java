package com.achintha.productservice.events;

import com.achintha.productservice.outbox.ProcessedEventStore;
import com.achintha.productservice.security.Role;
import com.achintha.productservice.security.SecurityStateCache;
import com.achintha.productservice.security.UserStatus;
import com.achintha.productservice.store.StoreVisibilityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies one {@code user-events} message: keeps the security-state cache current ({@code UserSecurityChanged},
 * {@code UserStatusChanged}) and records a merchant's status in {@code store_visibility} (a store whose owner is in
 * {@code BAN_GRACE} or {@code BANNED} leaves the public search at once, D4). Idempotent via {@code processed_events}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class UserEventHandler {

    public static final String CONSUMER = "product-service.user-events";

    private final ProcessedEventStore processedEvents;
    private final SecurityStateCache securityStateCache;
    private final StoreVisibilityService storeVisibility;

    /** @return {@code false} for a duplicate or an event this service does not use */
    @Transactional
    public boolean handle(UserEvent event) {
        if (event.eventId() == null || event.userId() == null || event.eventType() == null) {
            log.warn("Skipping user event without eventId/eventType/userId");
            return false;
        }
        UserStatus status = parseStatus(event.status());
        if ("UserSecurityChanged".equals(event.eventType()) || "UserStatusChanged".equals(event.eventType())) {
            // The cache is in memory: refresh it even for an already-processed event (another replica may have
            // recorded it, this one still needs the new version)
            securityStateCache.apply(event.userId(), event.tokenVersion(), status);
        }
        if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
            return false;
        }
        if ("UserStatusChanged".equals(event.eventType()) && Role.ROLE_MERCHANT.name().equals(event.role())
                && event.storeId() != null && status != null) {
            storeVisibility.applyOwnerStatus(event.storeId(), status.name(), event.occurredAt());
        }
        return true;
    }

    private static UserStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return UserStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
