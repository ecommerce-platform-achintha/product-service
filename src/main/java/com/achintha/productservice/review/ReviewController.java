package com.achintha.productservice.review;

import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.Pagination;
import com.achintha.productservice.common.ReasonRequest;
import com.achintha.productservice.config.OpenApiConfig;
import com.achintha.productservice.review.ReviewDtos.CreateReviewRequest;
import com.achintha.productservice.review.ReviewDtos.ManagedReviewResponse;
import com.achintha.productservice.review.ReviewDtos.ReplyRequest;
import com.achintha.productservice.review.ReviewDtos.ReviewResponse;
import com.achintha.productservice.security.Actor;
import com.achintha.productservice.security.SecurityState;
import com.achintha.productservice.security.SecurityStateFilter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product reviews: public list, customer review, merchant reply ({@code REVIEW_REPLY}), admin moderation. Paths are
 * under the product-service gateway prefixes ({@code /api/merchant/products/**}, {@code /api/admin/reviews/**});
 * store reviews live in store-service.
 */
@RestController
@RequiredArgsConstructor
@Tag(name = "Product reviews")
public class ReviewController {

    static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "rating", "rating");

    private final ReviewService reviewService;

    @GetMapping("/api/public/products/{publicId}/reviews")
    @Operation(summary = "Visible reviews of a visible product, with the store's reply")
    public PageResponse<ReviewResponse> publicReviews(@PathVariable String publicId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return reviewService.publicReviews(publicId, Pagination.of(page, size, sort, SORTABLE, Pagination.MAX_SIZE));
    }

    @PostMapping("/api/customer/products/{publicId}/reviews")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('ROLE_CUSTOMER')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Review an item of one of your completed orders (one review per order item, rating 1-5)")
    public ReviewResponse create(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                 @RequestAttribute(name = SecurityStateFilter.STATE_ATTRIBUTE, required = false)
                                 SecurityState state,
                                 @Valid @RequestBody CreateReviewRequest request) {
        return reviewService.create(Actor.from(jwt), publicId, request, state);
    }

    @GetMapping("/api/merchant/products/reviews")
    @PreAuthorize("hasAuthority('PERM_REVIEW_REPLY')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Reviews of the store's products (hidden ones included), optionally of one product")
    public PageResponse<ManagedReviewResponse> merchantReviews(@AuthenticationPrincipal Jwt jwt,
                                                               @RequestParam(required = false) String product,
                                                               @RequestParam(defaultValue = "0") int page,
                                                               @RequestParam(defaultValue = "20") int size,
                                                               @RequestParam(defaultValue = "createdAt,desc")
                                                               String sort) {
        return reviewService.merchantReviews(Actor.from(jwt), product,
                Pagination.of(page, size, sort, SORTABLE, Pagination.MAX_SIZE));
    }

    @PostMapping("/api/merchant/products/reviews/{reviewPublicId}/reply")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('PERM_REVIEW_REPLY')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Reply to a review of the store's product (one reply per review)")
    public ManagedReviewResponse reply(@AuthenticationPrincipal Jwt jwt, @PathVariable String reviewPublicId,
                                       @Valid @RequestBody ReplyRequest request) {
        return reviewService.reply(Actor.from(jwt), reviewPublicId, request.text());
    }

    @GetMapping("/api/admin/reviews")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "All reviews of a product, hidden ones included; max page size 100")
    public PageResponse<ManagedReviewResponse> adminReviews(@RequestParam String product,
                                                            @RequestParam(defaultValue = "0") int page,
                                                            @RequestParam(defaultValue = "20") int size,
                                                            @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return reviewService.adminReviews(product,
                Pagination.of(page, size, sort, SORTABLE, Pagination.MAX_ADMIN_SIZE));
    }

    @PostMapping("/api/admin/reviews/{reviewPublicId}/hide")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Hide a product review (reason required; removed from the rating)")
    public ManagedReviewResponse hide(@AuthenticationPrincipal Jwt jwt, @PathVariable String reviewPublicId,
                                      @Valid @RequestBody ReasonRequest request) {
        return reviewService.setHidden(Actor.from(jwt), reviewPublicId, true, request.reason());
    }

    @PostMapping("/api/admin/reviews/{reviewPublicId}/unhide")
    @PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
    @SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
    @Operation(summary = "Show a hidden product review again (reason required)")
    public ManagedReviewResponse unhide(@AuthenticationPrincipal Jwt jwt, @PathVariable String reviewPublicId,
                                        @Valid @RequestBody ReasonRequest request) {
        return reviewService.setHidden(Actor.from(jwt), reviewPublicId, false, request.reason());
    }
}
