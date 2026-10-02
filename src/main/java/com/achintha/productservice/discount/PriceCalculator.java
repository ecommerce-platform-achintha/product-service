package com.achintha.productservice.discount;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.Optional;
import java.util.UUID;

/**
 * Effective price rules (section 5), evaluated at read time:
 *
 * <ul>
 *   <li>a discount applies to a variant when it is active, {@code startsAt <= now < endsAt} (open ends allowed), and
 *       it targets the variant ({@code VARIANT}) or its product ({@code PRODUCT});</li>
 *   <li>amount: {@code PERCENT} = list price x value / 100, rounded half-up to cents; {@code FIXED} = value;</li>
 *   <li>a discount that would not leave a price above zero is ignored;</li>
 *   <li>only the single best (largest amount) discount applies; no stacking. Ties go to the variant-specific one,
 *       then the oldest.</li>
 * </ul>
 *
 * The public search ({@code SqlProductSearchEngine}) repeats the same arithmetic in SQL for its price filter and
 * sort; keep both in step.
 */
public final class PriceCalculator {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private PriceCalculator() {
    }

    /** The outcome for one variant. {@code discount} is {@code null} when none applies. */
    public record Price(BigDecimal listPrice, BigDecimal discountAmount, BigDecimal unitPrice, Discount discount) {

        public boolean discounted() {
            return discount != null;
        }
    }

    public static Price price(UUID productId, UUID variantId, BigDecimal listPrice, Collection<Discount> candidates,
                              Instant now) {
        BigDecimal list = listPrice.setScale(2, RoundingMode.HALF_UP);
        Optional<Discount> best = candidates.stream()
                .filter(d -> targets(d, productId, variantId))
                .filter(d -> isLive(d, now))
                .filter(d -> leavesPositivePrice(amount(d, list), list))
                .max(Comparator.<Discount, BigDecimal>comparing(d -> amount(d, list))
                        .thenComparing(d -> d.getScope() == DiscountScope.VARIANT)
                        .thenComparing(Discount::getCreatedAt, Comparator.reverseOrder()));
        if (best.isEmpty()) {
            return new Price(list, BigDecimal.ZERO.setScale(2), list, null);
        }
        BigDecimal amount = amount(best.get(), list);
        return new Price(list, amount, list.subtract(amount), best.get());
    }

    /** Active and inside its window at {@code now}. */
    public static boolean isLive(Discount discount, Instant now) {
        return discount.isActive()
                && (discount.getStartsAt() == null || !discount.getStartsAt().isAfter(now))
                && (discount.getEndsAt() == null || discount.getEndsAt().isAfter(now));
    }

    public static BigDecimal amount(Discount discount, BigDecimal listPrice) {
        BigDecimal amount = discount.getType() == DiscountType.PERCENT
                ? listPrice.multiply(discount.getValue()).divide(HUNDRED, 2, RoundingMode.HALF_UP)
                : discount.getValue().setScale(2, RoundingMode.HALF_UP);
        return amount;
    }

    public static boolean leavesPositivePrice(BigDecimal amount, BigDecimal listPrice) {
        return amount.signum() > 0 && amount.compareTo(listPrice) < 0;
    }

    private static boolean targets(Discount discount, UUID productId, UUID variantId) {
        return switch (discount.getScope()) {
            case PRODUCT -> discount.getProductId().equals(productId);
            case VARIANT -> discount.getVariantId() != null && discount.getVariantId().equals(variantId);
        };
    }
}
