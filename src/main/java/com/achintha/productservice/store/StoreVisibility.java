package com.achintha.productservice.store;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What this service knows about a store, fed by events only: {@code StoreVisibilityChanged} (store-events) and the
 * owner's {@code UserStatusChanged} (user-events). A store without a row is not visible.
 */
@Entity
@Table(name = "store_visibility")
@Getter
@Setter
@NoArgsConstructor
public class StoreVisibility {

    /** Owner statuses under which the store's listings stay public. {@code null}: not reported yet. */
    public static final String OWNER_ACTIVE = "ACTIVE";

    @Id
    @Column(name = "store_id")
    private UUID storeId;

    @Column(name = "store_public_id", length = 20)
    private String storePublicId;

    @Column(name = "store_name", length = 100)
    private String storeName;

    /** {@code published && acceptingOrders} as reported by store-service. */
    @Column(nullable = false)
    private boolean visible;

    @Column(name = "cod_enabled", nullable = false)
    private boolean codEnabled;

    @Column(name = "owner_status", length = 32)
    private String ownerStatus;

    @Column(name = "store_event_at")
    private Instant storeEventAt;

    @Column(name = "owner_event_at")
    private Instant ownerEventAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Listings are public: store-service says the store is visible, and the owner is not banned (a merchant in
     * {@code BAN_GRACE} silently leaves search, D4).
     */
    public boolean isPubliclyVisible() {
        return visible && (ownerStatus == null || OWNER_ACTIVE.equals(ownerStatus));
    }
}
