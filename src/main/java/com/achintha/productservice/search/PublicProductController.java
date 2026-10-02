package com.achintha.productservice.search;

import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.Pagination;
import com.achintha.productservice.search.PublicProductDtos.ProductDetail;
import com.achintha.productservice.search.PublicProductDtos.ProductSummary;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Public product search and detail (no token needed). */
@RestController
@RequestMapping("/api/public/products")
@RequiredArgsConstructor
@Tag(name = "Public: products")
public class PublicProductController {

    private final PublicProductService publicProductService;

    @GetMapping
    @Operation(summary = "Search visible products; all filters optional and combined with AND; max page size 50")
    public PageResponse<ProductSummary> search(
            @Parameter(description = "Case-insensitive partial match on the name (wildcards are literal)")
            @RequestParam(required = false) String q,
            @Parameter(description = "Category slug; includes its sub-categories")
            @RequestParam(required = false) String category,
            @Parameter(description = "Store publicId (STR-...)")
            @RequestParam(required = false) String store,
            @Parameter(description = "Lowest effective (after discount) price, LKR")
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "Only products with stock available")
            @RequestParam(defaultValue = "false") boolean inStock,
            @Parameter(description = "Minimum average rating, 0-5")
            @RequestParam(required = false) BigDecimal minRating,
            @Parameter(description = "relevance (default), price / price,desc, newest, rating")
            @RequestParam(defaultValue = "relevance") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return publicProductService.search(q, category, store, minPrice, maxPrice, inStock, minRating,
                SearchSort.parse(sort), Pagination.of(page, size, null, Map.of(), Pagination.MAX_SIZE));
    }

    @GetMapping("/{publicId}")
    @Operation(summary = "A visible product with its active variants, effective prices, discount and availability")
    public ProductDetail get(@PathVariable String publicId) {
        return publicProductService.detail(publicId);
    }
}
