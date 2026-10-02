package com.achintha.productservice.discount;

import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.Pagination;
import com.achintha.productservice.config.OpenApiConfig;
import com.achintha.productservice.discount.DiscountDtos.DiscountRequest;
import com.achintha.productservice.discount.DiscountDtos.DiscountResponse;
import com.achintha.productservice.security.Actor;
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
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** The store's discounts: the merchant, or an assistant with {@code DISCOUNT_MANAGE}. */
@RestController
@RequestMapping("/api/merchant/discounts")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasAuthority('PERM_DISCOUNT_MANAGE')")
@RequiredArgsConstructor
@Tag(name = "Merchant: discounts")
public class DiscountController {

    static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "startsAt", "startsAt",
            "endsAt", "endsAt");

    private final DiscountService discountService;

    @GetMapping
    @Operation(summary = "The store's discounts (optionally of one product)")
    public PageResponse<DiscountResponse> list(@AuthenticationPrincipal Jwt jwt,
                                               @RequestParam(required = false) String product,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size,
                                               @RequestParam(defaultValue = "createdAt,desc") String sort) {
        return discountService.list(Actor.from(jwt), product,
                Pagination.of(page, size, sort, SORTABLE, Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    public DiscountResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return discountService.get(Actor.from(jwt), publicId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a discount: PERCENT 1-90 or FIXED leaving a price above zero; the best single "
            + "live discount applies at read time (no stacking)")
    public DiscountResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody DiscountRequest request) {
        return discountService.create(Actor.from(jwt), request);
    }

    @PutMapping("/{publicId}")
    @Operation(summary = "Replace a discount")
    public DiscountResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                   @Valid @RequestBody DiscountRequest request) {
        return discountService.update(Actor.from(jwt), publicId, request);
    }

    @DeleteMapping("/{publicId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        discountService.delete(Actor.from(jwt), publicId);
    }
}
