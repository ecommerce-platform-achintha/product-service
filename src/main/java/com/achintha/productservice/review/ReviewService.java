package com.achintha.productservice.review;

import com.achintha.productservice.audit.AuditService;
import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.PublicIdGenerator;
import com.achintha.productservice.common.TextSanitizer;
import com.achintha.productservice.exception.ApiException;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ConflictException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.ports.ImageStoragePort;
import com.achintha.productservice.ports.NotificationPort;
import com.achintha.productservice.product.MerchantAccess;
import com.achintha.productservice.product.Product;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.review.ReviewDtos.CreateReviewRequest;
import com.achintha.productservice.review.ReviewDtos.ManagedReviewResponse;
import com.achintha.productservice.review.ReviewDtos.ReplyResponse;
import com.achintha.productservice.review.ReviewDtos.ReviewResponse;
import com.achintha.productservice.search.PublicProductService;
import com.achintha.productservice.security.Actor;
import com.achintha.productservice.security.SecurityState;
import com.achintha.productservice.security.UserStatus;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product reviews (D15): a customer may review each item of a completed order once (eligibility from
 * {@code OrderCompleted}); the store may reply once ({@code REVIEW_REPLY}); admins hide and unhide with a reason.
 * The product's rating aggregates count visible reviews only.
 */
@Service
@RequiredArgsConstructor
public class ReviewService {

    private final ProductReviewRepository reviewRepository;
    private final ReviewReplyRepository replyRepository;
    private final ReviewEligibilityRepository eligibilityRepository;
    private final ProductRepository productRepository;
    private final VariantRepository variantRepository;
    private final PublicProductService publicProducts;
    private final ImageStoragePort imageStorage;
    private final NotificationPort notifications;
    private final PublicIdGenerator publicIds;
    private final AuditService auditService;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    // ------------------------------------------------------------------------------------------ eligibility

    /**
     * One row per completed order item. Idempotent: a second {@code OrderCompleted} for the same order changes
     * nothing. Items of variants this service does not know are skipped.
     *
     * @return rows inserted
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int recordEligibility(UUID orderId, String orderPublicId, UUID customerId, List<UUID> variantIds,
                                 Instant completedAt) {
        Map<UUID, Variant> variants = variantRepository.findAllByIdIn(variantIds).stream()
                .collect(Collectors.toMap(Variant::getId, Function.identity()));
        int inserted = 0;
        for (UUID variantId : variantIds.stream().distinct().toList()) {
            Variant variant = variants.get(variantId);
            if (variant == null) {
                continue;
            }
            inserted += jdbcTemplate.update("""
                    insert into review_eligibility (order_id, order_public_id, variant_id, product_id, store_id,
                                                    customer_id, completed_at)
                    values (?, ?, ?, ?, ?, ?, ?) on conflict (order_id, variant_id) do nothing
                    """, orderId, orderPublicId, variantId, variant.getProductId(), variant.getStoreId(), customerId,
                    Timestamp.from(completedAt));
        }
        return inserted;
    }

    // --------------------------------------------------------------------------------------------- customer

    @Transactional
    public ReviewResponse create(Actor actor, String productPublicId, CreateReviewRequest request,
                                 SecurityState customerState) {
        if (customerState != null && customerState.status() == UserStatus.BANNED) {
            throw ApiException.forbidden(ErrorCode.ACCOUNT_BANNED, "Banned accounts cannot write reviews");
        }
        Product product = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        List<ReviewEligibility> items = eligibilityRepository.findAllByOrderPublicIdAndCustomerIdAndProductId(
                request.orderPublicId(), actor.id(), product.getId());
        if (request.variantPublicId() != null) {
            UUID variantId = variantRepository.findByPublicId(request.variantPublicId()).map(Variant::getId)
                    .orElse(null);
            items = items.stream().filter(e -> e.getVariantId().equals(variantId)).toList();
        }
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ErrorCode.REVIEW_NOT_ELIGIBLE,
                    "No completed order of yours has this item");
        }
        if (items.size() > 1) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "The order has several variants of this product: give variantPublicId");
        }
        ReviewEligibility item = items.getFirst();
        if (reviewRepository.existsByOrderIdAndVariantId(item.getOrderId(), item.getVariantId())) {
            throw alreadyReviewed();
        }
        List<String> keys = request.attachmentKeys() == null ? List.of()
                : request.attachmentKeys().stream().distinct().toList();
        for (String key : keys) {
            if (!imageStorage.exists(key)) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                        "attachmentKeys must refer to uploaded images");
            }
        }
        Instant now = clock.instant();
        ProductReview review = new ProductReview();
        review.setId(UUID.randomUUID());
        review.setPublicId(publicIds.generateUnique(PublicIdGenerator.REVIEW_PREFIX, reviewRepository::existsByPublicId));
        review.setProductId(product.getId());
        review.setVariantId(item.getVariantId());
        review.setStoreId(item.getStoreId());
        review.setOrderId(item.getOrderId());
        review.setOrderPublicId(item.getOrderPublicId());
        review.setCustomerId(actor.id());
        review.setRating(request.rating().shortValue());
        review.setBody(TextSanitizer.clean(request.text()));
        review.setAttachmentKeys(keys);
        review.setCreatedAt(now);
        review.setUpdatedAt(now);
        try {
            review = reviewRepository.saveAndFlush(review);
        } catch (DataIntegrityViolationException e) {
            throw alreadyReviewed();
        }
        productRepository.refreshRating(product.getId());
        notifications.notifyStore(item.getStoreId(), "PRODUCT_REVIEW_RECEIVED", product.getPublicId());
        return toPublic(review, null, labels(List.of(review)));
    }

    // ----------------------------------------------------------------------------------------------- public

    @Transactional(readOnly = true)
    public PageResponse<ReviewResponse> publicReviews(String productPublicId, Pageable pageable) {
        Product product = publicProducts.requireVisible(productPublicId);
        Page<ProductReview> page = reviewRepository.findAllByProductIdAndHiddenFalse(product.getId(), pageable);
        Map<UUID, ReviewReply> replies = repliesOf(page);
        Map<UUID, String> labels = labels(page.getContent());
        return PageResponse.from(page, review -> toPublic(review, replies.get(review.getId()), labels));
    }

    // ---------------------------------------------------------------------------------------------- merchant

    @Transactional(readOnly = true)
    public PageResponse<ManagedReviewResponse> merchantReviews(Actor actor, String productPublicId,
                                                               Pageable pageable) {
        UUID storeId = MerchantAccess.storeIdOf(actor);
        Page<ProductReview> page;
        if (productPublicId != null) {
            Product product = productRepository.findByPublicIdAndStoreId(productPublicId, storeId)
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            page = reviewRepository.findAllByStoreIdAndProductId(storeId, product.getId(), pageable);
        } else {
            page = reviewRepository.findAllByStoreId(storeId, pageable);
        }
        return managedPage(page);
    }

    @Transactional
    public ManagedReviewResponse reply(Actor actor, String reviewPublicId, String rawText) {
        MerchantAccess.requireCanEdit(actor);
        ProductReview review = reviewRepository.findByPublicIdAndStoreId(reviewPublicId, MerchantAccess.storeIdOf(actor))
                .orElseThrow(() -> new NotFoundException("Review not found"));
        if (replyRepository.existsByReviewId(review.getId())) {
            throw replyExists();
        }
        String text = TextSanitizer.clean(rawText);
        if (text == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "text must not be blank");
        }
        ReviewReply reply = new ReviewReply();
        reply.setId(UUID.randomUUID());
        reply.setReviewId(review.getId());
        reply.setStoreId(review.getStoreId());
        reply.setBody(text);
        reply.setAuthorUserId(actor.id());
        reply.setAuthorPublicId(actor.publicId());
        reply.setAuthorRole(actor.role());
        reply.setCreatedAt(clock.instant());
        try {
            reply = replyRepository.saveAndFlush(reply);
        } catch (DataIntegrityViolationException e) {
            throw replyExists();
        }
        auditService.record(actor, "REVIEW_REPLIED", AuditService.TARGET_REVIEW, review.getPublicId(), null,
                Map.of("reply", text), null);
        return toManaged(review, reply, publicIdsOf(List.of(review)));
    }

    // ------------------------------------------------------------------------------------------------- admin

    @Transactional(readOnly = true)
    public PageResponse<ManagedReviewResponse> adminReviews(String productPublicId, Pageable pageable) {
        Product product = productRepository.findByPublicId(productPublicId)
                .orElseThrow(() -> new NotFoundException("Product not found"));
        return managedPage(reviewRepository.findAllByProductId(product.getId(), pageable));
    }

    @Transactional
    public ManagedReviewResponse setHidden(Actor actor, String reviewPublicId, boolean hidden, String rawReason) {
        ProductReview review = reviewRepository.findByPublicId(reviewPublicId)
                .orElseThrow(() -> new NotFoundException("Review not found"));
        String reason = TextSanitizer.cleanLine(rawReason);
        if (reason == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "reason must not be blank");
        }
        boolean before = review.isHidden();
        Instant now = clock.instant();
        review.setHidden(hidden);
        review.setHiddenReason(hidden ? reason : null);
        review.setHiddenBy(hidden ? actor.publicId() : null);
        review.setHiddenAt(hidden ? now : null);
        review.setUpdatedAt(now);
        reviewRepository.saveAndFlush(review);
        if (before != hidden) {
            productRepository.refreshRating(review.getProductId());
        }
        auditService.record(actor, hidden ? "REVIEW_HIDDEN" : "REVIEW_UNHIDDEN", AuditService.TARGET_REVIEW,
                review.getPublicId(), Map.of("hidden", before), Map.of("hidden", hidden), reason);
        ReviewReply reply = replyRepository.findAllByReviewIdIn(List.of(review.getId())).stream().findFirst()
                .orElse(null);
        return toManaged(review, reply, publicIdsOf(List.of(review)));
    }

    // ------------------------------------------------------------------------------------------------- helpers

    private PageResponse<ManagedReviewResponse> managedPage(Page<ProductReview> page) {
        Map<UUID, ReviewReply> replies = repliesOf(page);
        Map<UUID, String> ids = publicIdsOf(page.getContent());
        return PageResponse.from(page, review -> toManaged(review, replies.get(review.getId()), ids));
    }

    private Map<UUID, ReviewReply> repliesOf(Page<ProductReview> page) {
        List<UUID> ids = page.getContent().stream().map(ProductReview::getId).toList();
        return ids.isEmpty() ? Map.of() : replyRepository.findAllByReviewIdIn(ids).stream()
                .collect(Collectors.toMap(ReviewReply::getReviewId, Function.identity()));
    }

    /** Variant id to its label (public view). */
    private Map<UUID, String> labels(List<ProductReview> reviews) {
        List<UUID> ids = reviews.stream().map(ProductReview::getVariantId).distinct().toList();
        return ids.isEmpty() ? Map.of() : variantRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Variant::getId, Variant::label));
    }

    /** Product and variant ids to their public ids (managed view). */
    private Map<UUID, String> publicIdsOf(List<ProductReview> reviews) {
        Map<UUID, String> ids = new HashMap<>();
        List<UUID> variantIds = reviews.stream().map(ProductReview::getVariantId).distinct().toList();
        List<UUID> productIds = reviews.stream().map(ProductReview::getProductId).distinct().toList();
        if (!variantIds.isEmpty()) {
            variantRepository.findAllByIdIn(variantIds).forEach(v -> ids.put(v.getId(), v.getPublicId()));
            productRepository.findAllById(productIds).forEach(p -> ids.put(p.getId(), p.getPublicId()));
        }
        return ids;
    }

    private ReviewResponse toPublic(ProductReview review, ReviewReply reply, Map<UUID, String> labels) {
        return new ReviewResponse(review.getPublicId(), labels.get(review.getVariantId()), review.getRating(),
                review.getBody(), urls(review), review.getCreatedAt(), replyOf(reply));
    }

    private ManagedReviewResponse toManaged(ProductReview review, ReviewReply reply, Map<UUID, String> ids) {
        return new ManagedReviewResponse(review.getPublicId(), ids.get(review.getProductId()),
                ids.get(review.getVariantId()), review.getOrderPublicId(), review.getRating(), review.getBody(),
                urls(review), review.isHidden(), review.getHiddenReason(), review.getHiddenAt(), review.getCreatedAt(),
                replyOf(reply));
    }

    private static ReplyResponse replyOf(ReviewReply reply) {
        return reply == null ? null : new ReplyResponse(reply.getBody(), reply.getCreatedAt());
    }

    private List<String> urls(ProductReview review) {
        return review.getAttachmentKeys().stream().map(imageStorage::urlFor).filter(Objects::nonNull).toList();
    }

    private static ConflictException alreadyReviewed() {
        return new ConflictException(ErrorCode.REVIEW_ALREADY_EXISTS, "This order item has already been reviewed");
    }

    private static ConflictException replyExists() {
        return new ConflictException(ErrorCode.REPLY_ALREADY_EXISTS, "This review already has a reply");
    }
}
