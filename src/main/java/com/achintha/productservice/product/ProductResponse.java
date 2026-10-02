package com.achintha.productservice.product;

import com.achintha.productservice.category.CategoryResponse;
import com.achintha.productservice.discount.DiscountDtos.AppliedDiscount;
import com.achintha.productservice.inventory.InventoryResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The full product aggregate as its merchant (or an admin) sees it: every variant with stock and the price after
 * the discount that applies right now. No store visibility here, so a store in its silent ban grace period looks
 * unchanged to its merchant (D4).
 */
public record ProductResponse(
        String publicId,
        String name,
        String description,
        CategoryResponse.Ref category,
        ProductStatus status,
        boolean codAllowed,
        List<String> imageKeys,
        List<String> imageUrls,
        List<OptionResponse> options,
        List<VariantResponse> variants,
        BigDecimal ratingAvg,
        int ratingCount,
        Instant createdAt,
        Instant updatedAt,
        long version) {

    public record OptionResponse(String name, List<String> values) {
    }

    public record VariantResponse(
            String publicId,
            String sku,
            Map<String, String> attributes,
            String label,
            BigDecimal listPrice,
            BigDecimal effectivePrice,
            AppliedDiscount discount,
            VariantStatus status,
            List<String> imageKeys,
            InventoryResponse stock) {
    }

    /** One row of the merchant / admin product lists. */
    public record Summary(
            String publicId,
            String name,
            CategoryResponse.Ref category,
            ProductStatus status,
            int variantCount,
            int activeVariantCount,
            BigDecimal minListPrice,
            int totalAvailable,
            int totalReserved,
            BigDecimal ratingAvg,
            int ratingCount,
            Instant updatedAt) {
    }
}
