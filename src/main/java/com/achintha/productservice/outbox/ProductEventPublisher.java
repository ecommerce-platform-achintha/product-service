package com.achintha.productservice.outbox;

import com.achintha.productservice.product.Product;
import com.achintha.productservice.product.Variant;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * Records {@code product-events} in the outbox, inside the caller's transaction ({@code MANDATORY}): the event is
 * stored if and only if the change commits. {@link OutboxRelay} publishes it to Kafka afterwards (at-least-once).
 * Nothing is ever sent to Kafka directly from business code.
 */
@Component
public class ProductEventPublisher {

    private final OutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final String topic;

    public ProductEventPublisher(OutboxRepository outboxRepository, ObjectMapper objectMapper, Clock clock,
                                 @Value("${app.kafka.topics.product-events}") String topic) {
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.topic = topic;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void productChanged(Product product, List<Variant> variants, String change, String previousStatus) {
        List<ProductEvent.VariantInfo> infos = variants.stream()
                .map(v -> new ProductEvent.VariantInfo(v.getId(), v.getPublicId(), v.getSku(), v.getListPrice(),
                        v.getStatus().name()))
                .toList();
        append(new ProductEvent(UUID.randomUUID(), ProductEventType.ProductChanged, clock.instant(), product.getId(),
                product.getId(), product.getPublicId(), product.getStoreId(), product.getName(),
                product.getStatus().name(), previousStatus, product.getCategory().getSlug(), product.isCodAllowed(),
                infos, change));
    }

    private void append(ProductEvent event) {
        OutboxMessage message = new OutboxMessage();
        message.setEventId(event.eventId());
        message.setAggregateId(event.aggregateId());
        message.setEventType(event.eventType().name());
        message.setTopic(topic);
        message.setMessageKey(event.aggregateId().toString());
        message.setPayload(objectMapper.writeValueAsString(event));
        message.setCreatedAt(event.occurredAt());
        outboxRepository.save(message);
    }
}
