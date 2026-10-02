package com.achintha.productservice.search;

import com.achintha.productservice.category.CategoryResponse;
import com.achintha.productservice.discount.DiscountDtos.AppliedDiscount;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Public views: publicIds only, no SKUs, no stock counts (availability flags only), no internal ids.
 */
public final class PublicProductDtos {

    private PublicProductDtos() {
    }

    public record StoreRef(String publicId, String name) {
    }

    /**
     * One search result.
     *
     * @param price     the cheapest active variant's effective price ("from" price)
     * @param listPrice that variant's list price (equal to {@code price} when no discount applies)
     */
    public record ProductSummary(
            String publicId,
            String name,
            CategoryResponse.Ref category,
            StoreRef store,
            String imageUrl,
            BigDecimal price,
            BigDecimal listPrice,
            boolean discounted,
            boolean inStock,
            BigDecimal ratingAvg,
            int ratingCount) {
    }

    public record ProductDetail(
            String publicId,
            String name,
            String description,
            CategoryResponse.Ref category,
            StoreRef store,
            boolean codAllowed,
            List<String> imageUrls,
            List<Option> options,
            List<VariantView> variants,
            BigDecimal ratingAvg,
            int ratingCount,
            Instant createdAt) {
    }

    public record Option(String name, List<String> values) {
    }

    /**
     * @param effectivePrice list price minus the discount that applies right now
     * @param lowStock       only a few left (threshold {@code app.catalog.low-stock-threshold})
     */
    public record VariantView(
            String publicId,
            String label,
            Map<String, String> attributes,
            BigDecimal listPrice,
            BigDecimal effectivePrice,
            AppliedDiscount discount,
            boolean inStock,
            boolean lowStock,
            List<String> imageUrls) {
    }
}
