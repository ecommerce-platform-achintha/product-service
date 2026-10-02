package com.achintha.productservice.discount;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DiscountRepository extends JpaRepository<Discount, UUID> {

    boolean existsByPublicId(String publicId);

    /** Candidates for read-time evaluation (window and best-wins are checked by {@link PriceCalculator}). */
    List<Discount> findAllByProductIdInAndActiveTrue(Collection<UUID> productIds);

    Optional<Discount> findByPublicIdAndStoreId(String publicId, UUID storeId);

    Page<Discount> findAllByStoreId(UUID storeId, Pageable pageable);

    Page<Discount> findAllByStoreIdAndProductId(UUID storeId, UUID productId, Pageable pageable);
}
