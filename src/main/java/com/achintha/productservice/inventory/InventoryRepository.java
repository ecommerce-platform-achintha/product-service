package com.achintha.productservice.inventory;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface InventoryRepository extends JpaRepository<Inventory, UUID> {

    Optional<Inventory> findByProductId(UUID productId);

    List<Inventory> findByProductIdIn(Collection<UUID> productIds);

    /**
     * Row-locks (SELECT ... FOR UPDATE) the product's inventory so concurrent adjustments, e.g. two orders for the
     * last item, are applied one after the other and the negative-stock check can't be raced.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Inventory i where i.product.id = :productId")
    Optional<Inventory> findByProductIdForUpdate(@Param("productId") UUID productId);

    @Modifying
    @Query("delete from Inventory i where i.product.id = :productId")
    void deleteByProductId(@Param("productId") UUID productId);
}
