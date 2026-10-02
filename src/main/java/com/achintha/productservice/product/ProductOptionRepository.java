package com.achintha.productservice.product;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductOptionRepository extends JpaRepository<ProductOption, Long> {

    List<ProductOption> findAllByProductIdOrderByPositionAsc(UUID productId);

    List<ProductOption> findAllByProductIdInOrderByPositionAsc(Collection<UUID> productIds);
}
