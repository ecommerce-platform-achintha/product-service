package com.achintha.productservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.review.ReviewEligibilityRepository;
import com.achintha.productservice.security.Role;
import com.achintha.productservice.security.SecurityState;
import com.achintha.productservice.security.UserStatus;
import com.achintha.productservice.store.StoreVisibility;
import com.achintha.productservice.store.StoreVisibilityRepository;
import com.achintha.productservice.support.IntegrationTest;
import com.achintha.productservice.support.TestUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Consumers are idempotent (processed_events) and robust to out-of-order and duplicate deliveries. */
class EventConsumerTest extends IntegrationTest {

    @Autowired
    private StoreVisibilityRepository storeVisibilityRepository;
    @Autowired
    private OrderEventHandler orderEventHandler;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private VariantRepository variantRepository;

    private StoreVisibility visibility(UUID storeId) {
        return storeVisibilityRepository.findById(storeId).orElseThrow();
    }

    @Test
    void storeVisibilityIgnoresDuplicatesAndLateEvents() {
        UUID storeId = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-10-01T10:00:00Z");
        StoreEvent shown = new StoreEvent(UUID.randomUUID(), "StoreVisibilityChanged", t0.plusSeconds(60), storeId,
                "STR-2610-" + suffix(), "Late Store", true, false, "CREATED");
        assertThat(storeEventHandler.handle(shown)).isTrue();
        assertThat(storeEventHandler.handle(shown)).isFalse(); // same eventId
        // An older event (delivered late) does not hide the store again
        assertThat(storeEventHandler.handle(new StoreEvent(UUID.randomUUID(), "StoreVisibilityChanged", t0, storeId,
                null, null, false, false, "PROFILE_UPDATED"))).isTrue();
        assertThat(visibility(storeId).isVisible()).isTrue();
        assertThat(visibility(storeId).getStoreName()).isEqualTo("Late Store");
        // Other store-events are not for this service
        assertThat(storeEventHandler.handle(new StoreEvent(UUID.randomUUID(), "SettingsChanged", t0, null, null,
                null, null, null, "UPDATED"))).isFalse();
    }

    @Test
    void ownerStatusAndTokenVersionComeFromUserEvents() {
        TestUser merchant = merchant();
        storeVisible(merchant, true);
        UserEvent banGrace = new UserEvent(UUID.randomUUID(), "UserStatusChanged", clock.instant(), merchant.id(),
                merchant.publicId(), Role.ROLE_MERCHANT.name(), "BAN_GRACE", "ACTIVE", merchant.storeId(), 5L, null);
        assertThat(userEventHandler.handle(banGrace)).isTrue();
        assertThat(userEventHandler.handle(banGrace)).isFalse();
        assertThat(visibility(merchant.storeId()).isPubliclyVisible()).isFalse();
        assertThat(securityStateCache.cached(merchant.id())).get().extracting(SecurityState::tokenVersion)
                .isEqualTo(5L);
        // A customer's status change does not touch any store
        TestUser customer = customer();
        assertThat(userEventHandler.handle(new UserEvent(UUID.randomUUID(), "UserStatusChanged", clock.instant(),
                customer.id(), customer.publicId(), Role.ROLE_CUSTOMER.name(), UserStatus.BANNED.name(), "ACTIVE",
                null, 2L, null))).isTrue();
        assertThat(securityStateCache.cached(customer.id())).get().extracting(SecurityState::status)
                .isEqualTo(UserStatus.BANNED);
    }

    @Test
    void orderCompletedMakesEachItemReviewableOnce() throws Exception {
        Listing listing = listing(category(), "Reviewable", "10.00", 5);
        UUID variantId = variantRepository.findByPublicId(listing.variant()).orElseThrow().getId();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String orderPublicId = "ORD-2610-" + suffix();
        List<OrderEvent.Line> lines = List.of(new OrderEvent.Line(null, listing.variant(), 1),
                new OrderEvent.Line(UUID.randomUUID(), null, 1)); // unknown variant: skipped

        assertThat(orderEventHandler.handle(new OrderEvent(UUID.randomUUID(), "OrderCompleted", clock.instant(),
                orderId, orderPublicId, listing.merchant().storeId(), customerId, "COMPLETED", lines))).isTrue();
        // A second OrderCompleted for the same order (new event id) adds nothing
        assertThat(orderEventHandler.handle(new OrderEvent(UUID.randomUUID(), "OrderCompleted", clock.instant(),
                orderId, orderPublicId, listing.merchant().storeId(), customerId, "COMPLETED", lines))).isTrue();
        // Other order events are ignored
        assertThat(orderEventHandler.handle(new OrderEvent(UUID.randomUUID(), "OrderPlaced", clock.instant(),
                orderId, orderPublicId, listing.merchant().storeId(), customerId, "AWAITING_MERCHANT", lines)))
                .isFalse();

        assertThat(eligibilityRepository.findAllByOrderId(orderId)).singleElement()
                .satisfies(e -> {
                    assertThat(e.getVariantId()).isEqualTo(variantId);
                    assertThat(e.getCustomerId()).isEqualTo(customerId);
                    assertThat(e.getStoreId()).isEqualTo(listing.merchant().storeId());
                });
    }
}
