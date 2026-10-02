package com.achintha.productservice.events;

import com.achintha.productservice.outbox.ProcessedEventStore;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.review.ReviewService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies {@code OrderCompleted} from {@code order-events}: every item of the order becomes reviewable by its
 * customer ({@code review_eligibility}). Idempotent twice over: {@code processed_events}, and one row per
 * (order, variant).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventHandler {

    public static final String CONSUMER = "product-service.order-events";

    private final ProcessedEventStore processedEvents;
    private final ReviewService reviewService;
    private final VariantRepository variantRepository;

    /** @return {@code false} for a duplicate or an event this service does not use */
    @Transactional
    public boolean handle(OrderEvent event) {
        if (!"OrderCompleted".equals(event.eventType())) {
            return false;
        }
        if (event.eventId() == null || event.orderId() == null || event.orderPublicId() == null
                || event.customerId() == null) {
            log.warn("Skipping OrderCompleted without eventId/orderId/orderPublicId/customerId");
            return false;
        }
        if (!processedEvents.markProcessed(event.eventId(), CONSUMER)) {
            return false;
        }
        List<UUID> variantIds = variantIds(event.lines());
        if (variantIds.isEmpty()) {
            log.warn("OrderCompleted {} has no lines: nothing becomes reviewable", event.eventId());
            return true;
        }
        reviewService.recordEligibility(event.orderId(), event.orderPublicId(), event.customerId(), variantIds,
                event.occurredAt() != null ? event.occurredAt() : Instant.now());
        return true;
    }

    private List<UUID> variantIds(List<OrderEvent.Line> lines) {
        if (lines == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        List<String> publicIds = new ArrayList<>();
        for (OrderEvent.Line line : lines) {
            if (line.variantId() != null) {
                ids.add(line.variantId());
            } else if (line.variantPublicId() != null) {
                publicIds.add(line.variantPublicId());
            }
        }
        if (!publicIds.isEmpty()) {
            variantRepository.findAllByPublicIdIn(publicIds).stream().map(Variant::getId).forEach(ids::add);
        }
        return ids.stream().filter(Objects::nonNull).distinct().toList();
    }
}
