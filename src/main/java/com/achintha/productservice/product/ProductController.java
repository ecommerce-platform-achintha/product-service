package com.achintha.productservice.product;

import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.inventory.AdjustInventoryRequest;
import com.achintha.productservice.inventory.InventoryResponse;
import com.achintha.productservice.inventory.InventoryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

// Writes require the Gateway's X-User-Id header (see GatewayUserHeaderFilter); reads are public.
@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService productService;
    private final InventoryService inventoryService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a product; its inventory starts at 0")
    public ProductResponse create(@Valid @RequestBody ProductRequest request) {
        return productService.create(request);
    }

    @GetMapping("/{id}")
    public ProductResponse get(@PathVariable UUID id) {
        return productService.get(id);
    }

    @PutMapping("/{id}")
    public ProductResponse update(@PathVariable UUID id, @Valid @RequestBody ProductRequest request) {
        return productService.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        productService.delete(id);
    }

    @GetMapping
    @Operation(summary = "Search products; all filters are optional and combine with AND")
    public PageResponse<ProductResponse> list(
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) BigDecimal minPrice,
            @RequestParam(required = false) BigDecimal maxPrice,
            @Parameter(description = "Case-insensitive partial match on the name")
            @RequestParam(required = false) String q,
            @ParameterObject @PageableDefault(size = 20, sort = "name") Pageable pageable) {
        return productService.search(new ProductFilter(categoryId, minPrice, maxPrice, q), pageable);
    }

    @PatchMapping("/{id}/inventory")
    @Operation(summary = "Add delta to the available quantity; 409 if the result would be negative")
    public InventoryResponse adjustInventory(@PathVariable UUID id,
                                             @Valid @RequestBody AdjustInventoryRequest request) {
        return inventoryService.adjust(id, request.delta());
    }
}
