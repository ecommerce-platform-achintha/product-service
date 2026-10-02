package com.achintha.productservice.product;

import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.Pagination;
import com.achintha.productservice.config.OpenApiConfig;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.store.StoreVisibility;
import com.achintha.productservice.store.StoreVisibilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Moderation views of any store's catalog (ROLE_ADMIN or ROLE_SUPER_ADMIN); read-only. */
@RestController
@RequestMapping("/api/admin/products")
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasAnyAuthority('ROLE_ADMIN','ROLE_SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Admin: products")
public class AdminProductController {

    private final ProductRepository productRepository;
    private final StoreVisibilityService storeVisibility;
    private final ProductViews views;

    @GetMapping
    @Transactional(readOnly = true)
    @Operation(summary = "A store's products (any status) for moderation; max page size 100")
    public PageResponse<ProductResponse.Summary> list(@RequestParam String store,
                                                      @RequestParam(required = false) ProductStatus status,
                                                      @RequestParam(required = false) String q,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(defaultValue = "updatedAt,desc") String sort) {
        StoreVisibility known = storeVisibility.findByPublicId(store)
                .orElseThrow(() -> new NotFoundException("Store not found"));
        Page<Product> result = productRepository.findAll(
                ProductSpecifications.forStore(known.getStoreId(), status, q),
                Pagination.of(page, size, sort, ProductController.SORTABLE, Pagination.MAX_ADMIN_SIZE));
        return PageResponse.of(result, views.summaries(result.getContent()));
    }

    @GetMapping("/{publicId}")
    @Transactional(readOnly = true)
    @Operation(summary = "Any product with its variants, stock and prices")
    public ProductResponse get(@PathVariable String publicId) {
        return views.detail(productRepository.findByPublicId(publicId)
                .orElseThrow(() -> new NotFoundException("Product not found")));
    }
}
