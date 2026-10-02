package com.achintha.productservice.product;

import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.Pagination;
import com.achintha.productservice.config.OpenApiConfig;
import com.achintha.productservice.product.ProductRequest.StatusRequest;
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

/**
 * The caller's store catalog: the merchant, or an assistant with {@code PRODUCT_EDIT} (reads also with
 * {@code STOCK_EDIT} or {@code DISCOUNT_MANAGE}, which need to see the products they work on).
 */
@RestController
@RequestMapping("/api/merchant/products")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@RequiredArgsConstructor
@Tag(name = "Merchant: products")
public class ProductController {

    static final String READ = "hasAnyAuthority('PERM_PRODUCT_EDIT','PERM_STOCK_EDIT','PERM_DISCOUNT_MANAGE')";
    static final String EDIT = "hasAuthority('PERM_PRODUCT_EDIT')";
    static final Map<String, String> SORTABLE = Map.of("createdAt", "createdAt", "updatedAt", "updatedAt",
            "name", "name");

    private final ProductService productService;

    @GetMapping
    @PreAuthorize(READ)
    @Operation(summary = "The store's products (any status), optionally filtered by status and name")
    public PageResponse<ProductResponse.Summary> list(@AuthenticationPrincipal Jwt jwt,
                                                      @RequestParam(required = false) ProductStatus status,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(defaultValue = "updatedAt,desc") String sort) {
        return productService.list(Actor.from(jwt), status, q,
                Pagination.of(page, size, sort, SORTABLE, Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    @PreAuthorize(READ)
    @Operation(summary = "One product with options, variants, stock and current prices")
    public ProductResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return productService.get(Actor.from(jwt), publicId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDIT)
    @Operation(summary = "Create a product (DRAFT) with its options and variants; a simple product (sku + listPrice) "
            + "gets a default variant")
    public ProductResponse create(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProductRequest request) {
        return productService.create(Actor.from(jwt), request);
    }

    @PutMapping("/{publicId}")
    @PreAuthorize(EDIT)
    @Operation(summary = "Replace the product aggregate; listed variants are updated (publicId) or added, the "
            + "others become INACTIVE")
    public ProductResponse update(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                  @Valid @RequestBody ProductRequest request) {
        return productService.update(Actor.from(jwt), publicId, request);
    }

    @PostMapping("/{publicId}/status")
    @PreAuthorize(EDIT)
    @Operation(summary = "Change the status: DRAFT <-> ACTIVE (needs an active variant and an approved merchant), "
            + "-> ARCHIVED, ARCHIVED -> DRAFT")
    public ProductResponse changeStatus(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                        @Valid @RequestBody StatusRequest request) {
        return productService.changeStatus(Actor.from(jwt), publicId, request.status());
    }

    @DeleteMapping("/{publicId}")
    @PreAuthorize(EDIT)
    @Operation(summary = "Archive the product (products are never deleted)")
    public ProductResponse archive(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId) {
        return productService.changeStatus(Actor.from(jwt), publicId, ProductStatus.ARCHIVED);
    }
}
