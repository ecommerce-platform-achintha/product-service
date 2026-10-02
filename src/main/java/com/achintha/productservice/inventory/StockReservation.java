package com.achintha.productservice.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One line of an order's stock hold: unique per (orderRef, variant). */
@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor
public class StockReservation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_ref", nullable = false, length = 64)
    private String orderRef;

    @Column(name = "variant_id", nullable = false)
    private UUID variantId;

    /** Units currently held (HELD) or taken (COMMITTED); 0 once a quote removed the line. */
    @Column(nullable = false)
    private int quantity;

    /** Units asked for when the hold was placed (idempotent replays are compared against it). */
    @Column(name = "requested_quantity", nullable = false)
    private int requestedQuantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ReservationStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "release_reason", length = 16)
    private ReleaseReason releaseReason;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;
}
