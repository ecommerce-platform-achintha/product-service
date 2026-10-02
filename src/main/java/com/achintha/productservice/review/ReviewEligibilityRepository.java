package com.achintha.productservice.review;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewEligibilityRepository extends JpaRepository<ReviewEligibility, Long> {

    /** The customer's completed items of this product in this order (customer scoping by the JWT subject). */
    List<ReviewEligibility> findAllByOrderPublicIdAndCustomerIdAndProductId(String orderPublicId, UUID customerId,
                                                                             UUID productId);

    List<ReviewEligibility> findAllByOrderId(UUID orderId);
}
