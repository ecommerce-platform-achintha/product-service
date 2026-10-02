package com.achintha.productservice.review;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewReplyRepository extends JpaRepository<ReviewReply, UUID> {

    boolean existsByReviewId(UUID reviewId);

    List<ReviewReply> findAllByReviewIdIn(Collection<UUID> reviewIds);
}
