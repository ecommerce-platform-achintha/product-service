package com.achintha.productservice.product;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductRepository extends JpaRepository<Product, UUID>, JpaSpecificationExecutor<Product> {

    boolean existsByPublicId(String publicId);

    boolean existsByCategoryId(UUID categoryId);

    /** Merchant side: the product only if it belongs to the caller's store (a foreign id is "not found"). */
    @EntityGraph(attributePaths = {"category", "category.parent"})
    Optional<Product> findByPublicIdAndStoreId(String publicId, UUID storeId);

    @EntityGraph(attributePaths = {"category", "category.parent"})
    Optional<Product> findByPublicId(String publicId);

    @EntityGraph(attributePaths = {"category", "category.parent"})
    List<Product> findAllByIdIn(Collection<UUID> ids);

    /** Fetches the category in the same query so listing a page doesn't trigger one lookup per category. */
    @Override
    @EntityGraph(attributePaths = {"category", "category.parent"})
    Page<Product> findAll(Specification<Product> spec, Pageable pageable);

    /** Recomputes the rating aggregates from the visible reviews. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            update products p set
                rating_count = r.cnt,
                rating_avg = coalesce(r.avg, 0)
            from (select count(*) as cnt, round(avg(rating)::numeric, 2) as avg
                  from product_reviews where product_id = :id and hidden = false) r
            where p.id = :id
            """, nativeQuery = true)
    void refreshRating(@Param("id") UUID productId);
}
