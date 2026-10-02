package com.achintha.productservice.review;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductReviewRepository extends JpaRepository<ProductReview, UUID> {

    boolean existsByPublicId(String publicId);

    boolean existsByOrderIdAndVariantId(UUID orderId, UUID variantId);

    Optional<ProductReview> findByPublicId(String publicId);

    /** Merchant side: only reviews of the caller's store. */
    Optional<ProductReview> findByPublicIdAndStoreId(String publicId, UUID storeId);

    Page<ProductReview> findAllByProductIdAndHiddenFalse(UUID productId, Pageable pageable);

    Page<ProductReview> findAllByProductId(UUID productId, Pageable pageable);

    Page<ProductReview> findAllByStoreId(UUID storeId, Pageable pageable);

    Page<ProductReview> findAllByStoreIdAndProductId(UUID storeId, UUID productId, Pageable pageable);
}
