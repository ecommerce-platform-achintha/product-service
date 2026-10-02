package com.achintha.productservice.internal;

import com.achintha.productservice.common.Validation;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Price-and-stock quote for order-service (internal APIs may carry UUIDs, section 1). */
public final class QuoteDtos {

    private QuoteDtos() {
    }

    /** Variants by UUID and/or publicId; at most 200 in total. */
    public record QuoteRequest(
            @Size(max = 200) List<@NotNull UUID> variantIds,
            @Size(max = 200) List<@NotNull @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String> variantPublicIds) {
    }

    /**
     * @param items   one per known variant
     * @param missing requested ids / publicIds that do not exist
     */
    public record QuoteResponse(List<QuoteItem> items, List<String> missing) {
    }

    /**
     * Live data for one variant, priced now.
     *
     * @param unitPrice         {@code listPrice - discountAmount}
     * @param availableQuantity units that can be reserved now (held units excluded)
     * @param storeVisible      the store's listings are public and it takes new orders
     * @param purchasable       {@code productActive && variantActive && storeVisible}
     */
    public record QuoteItem(
            UUID variantId,
            String variantPublicId,
            UUID productId,
            String productPublicId,
            UUID storeId,
            String storePublicId,
            String storeName,
            String productName,
            String variantName,
            String sku,
            Map<String, String> attributes,
            String imageUrl,
            BigDecimal listPrice,
            BigDecimal discountAmount,
            BigDecimal unitPrice,
            String discountPublicId,
            int availableQuantity,
            boolean codAllowed,
            boolean productActive,
            boolean variantActive,
            boolean storeVisible,
            boolean purchasable) {
    }
}
