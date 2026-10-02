package com.achintha.productservice.inventory;

import java.sql.Timestamp;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Stock rows. The reservation operations are single conditional {@code UPDATE}s: PostgreSQL row-locks the row and
 * re-checks the {@code WHERE} after any concurrent update, so two orders for the last unit cannot both succeed
 * (no oversell), without a read-modify-write window. Each one also bumps {@code version}, so a concurrent merchant
 * stock edit based on an older read fails its optimistic lock instead of overwriting the change.
 */
public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    List<Inventory> findAllByVariantIdIn(Collection<UUID> variantIds);

    /** Moves {@code quantity} from available to reserved. @return 0 when not enough is available (nothing changed) */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update inventory set quantity_available = quantity_available - :quantity,
                                 reserved_quantity = reserved_quantity + :quantity,
                                 version = version + 1, updated_at = :now
            where variant_id = :variantId and quantity_available >= :quantity
            """, nativeQuery = true)
    int hold(@Param("variantId") UUID variantId, @Param("quantity") int quantity, @Param("now") Timestamp now);

    /** Returns held units to available (release, or a quote that reduced a line). */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update inventory set quantity_available = quantity_available + :quantity,
                                 reserved_quantity = reserved_quantity - :quantity,
                                 version = version + 1, updated_at = :now
            where variant_id = :variantId and reserved_quantity >= :quantity
            """, nativeQuery = true)
    int unhold(@Param("variantId") UUID variantId, @Param("quantity") int quantity, @Param("now") Timestamp now);

    /** Commit: held units leave the stock for good. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update inventory set reserved_quantity = reserved_quantity - :quantity,
                                 version = version + 1, updated_at = :now
            where variant_id = :variantId and reserved_quantity >= :quantity
            """, nativeQuery = true)
    int consume(@Param("variantId") UUID variantId, @Param("quantity") int quantity, @Param("now") Timestamp now);
}
