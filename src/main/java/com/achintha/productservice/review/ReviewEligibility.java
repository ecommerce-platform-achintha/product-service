package com.achintha.productservice.review;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A completed order item its customer may review (filled from {@code OrderCompleted}); one per (order, variant). */
@Entity
@Table(name = "review_eligibility")
@Getter
@Setter
@NoArgsConstructor
public class ReviewEligibility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Column(name = "order_public_id", nullable = false, length = 20)
    private String orderPublicId;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;
}
