package com.achintha.productservice.discount;

import com.achintha.productservice.common.Validation;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.Instant;

public final class DiscountDtos {

    private DiscountDtos() {
    }

    /**
     * Create (POST) or replace (PUT) a discount.
     *
     * @param productPublicId {@code PRODUCT} scope: the product ({@code ITM-}); optional for {@code VARIANT}
     * @param variantPublicId {@code VARIANT} scope only ({@code VAR-})
     * @param value           {@code PERCENT}: 1-90; {@code FIXED}: LKR, below every targeted list price
     * @param startsAt        inclusive, optional (immediately)
     * @param endsAt          exclusive, optional (open-ended)
     * @param active          defaults to {@code true}
     */
    public record DiscountRequest(
            @Size(max = 100) String name,
            @NotNull DiscountScope scope,
            @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String productPublicId,
            @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String variantPublicId,
            @NotNull DiscountType type,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal value,
            Instant startsAt,
            Instant endsAt,
            Boolean active) {
    }

    /** @param live whether it is active and inside its window right now (it may still lose to a better one) */
    public record DiscountResponse(
            String publicId,
            String name,
            DiscountScope scope,
            String productPublicId,
            String variantPublicId,
            DiscountType type,
            BigDecimal value,
            Instant startsAt,
            Instant endsAt,
            boolean active,
            boolean live,
            Instant createdAt,
            Instant updatedAt) {
    }

    /** The discount applied to a price, as shown to buyers. */
    public record AppliedDiscount(DiscountType type, BigDecimal value, BigDecimal amount, Instant endsAt, String name) {

        public static AppliedDiscount from(PriceCalculator.Price price) {
            Discount d = price.discount();
            return d == null ? null
                    : new AppliedDiscount(d.getType(), d.getValue(), price.discountAmount(), d.getEndsAt(), d.getName());
        }
    }
}
