package com.achintha.productservice.inventory;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock of one variant, in its own table: stock changes far more often than catalog data, so this keeps that write
 * traffic (and its row locks) away from the products and variants tables.
 *
 * <ul>
 *   <li>{@code quantityAvailable}: units that can still be sold (held units are not included);</li>
 *   <li>{@code reservedQuantity}: units held for orders that are not committed yet.</li>
 * </ul>
 *
 * Changed only by the merchant stock edit (optimistic locking with {@code @Version}) and by the reservation API
 * (conditional updates that also bump the version, see {@link InventoryRepository}).
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @Column(name = "variant_id")
    private UUID variantId;

    @Column(name = "quantity_available", nullable = false)
    private int quantityAvailable;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public Inventory(UUID variantId, int quantityAvailable, Instant now) {
        this.variantId = variantId;
        this.quantityAvailable = quantityAvailable;
        this.updatedAt = now;
    }
}
