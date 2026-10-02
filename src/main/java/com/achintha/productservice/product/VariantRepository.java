package com.achintha.productservice.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VariantRepository extends JpaRepository<Variant, UUID> {

    boolean existsByPublicId(String publicId);

    List<Variant> findAllByProductIdOrderByPositionAsc(UUID productId);

    List<Variant> findAllByProductIdInOrderByPositionAsc(Collection<UUID> productIds);

    List<Variant> findAllByIdIn(Collection<UUID> ids);

    List<Variant> findAllByPublicIdIn(Collection<String> publicIds);

    Optional<Variant> findByPublicId(String publicId);

    /** Merchant side: the variant only if it belongs to the caller's store. */
    Optional<Variant> findByPublicIdAndStoreId(String publicId, UUID storeId);

    /** SKUs of the store that clash (case-insensitively) with the given ones, ignoring the given variants. */
    @Query("""
            select v.sku from Variant v
            where v.storeId = :storeId and lower(v.sku) in :skus and v.id not in :excluded
            """)
    List<String> clashingSkus(@Param("storeId") UUID storeId, @Param("skus") Collection<String> lowerSkus,
                              @Param("excluded") Collection<UUID> excludedVariantIds);
}
