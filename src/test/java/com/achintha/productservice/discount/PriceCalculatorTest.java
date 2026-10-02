package com.achintha.productservice.discount;

import static org.assertj.core.api.Assertions.assertThat;

import com.achintha.productservice.discount.PriceCalculator.Price;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Discount evaluation at read time: windows, percent / fixed, best single discount wins, no stacking. */
class PriceCalculatorTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-10-01T10:00:00Z");

    private static Discount discount(DiscountScope scope, DiscountType type, String value) {
        Discount d = new Discount();
        d.setId(UUID.randomUUID());
        d.setPublicId("DSC-2610-" + UUID.randomUUID().toString().substring(0, 6));
        d.setScope(scope);
        d.setProductId(PRODUCT);
        d.setVariantId(scope == DiscountScope.VARIANT ? VARIANT : null);
        d.setType(type);
        d.setValue(new BigDecimal(value));
        d.setActive(true);
        d.setCreatedAt(NOW.minusSeconds(3600));
        return d;
    }

    private static Price price(String list, Discount... discounts) {
        return PriceCalculator.price(PRODUCT, VARIANT, new BigDecimal(list), List.of(discounts), NOW);
    }

    @Test
    void noDiscountMeansListPrice() {
        Price price = price("1500");
        assertThat(price.unitPrice()).isEqualByComparingTo("1500.00");
        assertThat(price.discountAmount()).isEqualByComparingTo("0");
        assertThat(price.discounted()).isFalse();
    }

    @Test
    void percentIsRoundedHalfUpToCents() {
        Price price = price("99.99", discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "15"));
        // 99.99 x 15 % = 14.9985 -> 15.00
        assertThat(price.discountAmount()).isEqualByComparingTo("15.00");
        assertThat(price.unitPrice()).isEqualByComparingTo("84.99");
    }

    @Test
    void fixedAmountIsTakenOff() {
        Price price = price("1000", discount(DiscountScope.VARIANT, DiscountType.FIXED, "250"));
        assertThat(price.unitPrice()).isEqualByComparingTo("750.00");
    }

    @Test
    void theSingleBestDiscountWinsWithoutStacking() {
        Discount tenPercent = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "10");
        Discount fixed150 = discount(DiscountScope.VARIANT, DiscountType.FIXED, "150");
        Discount twentyPercent = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "20");
        Price price = price("1000", tenPercent, fixed150, twentyPercent);
        assertThat(price.discount()).isSameAs(twentyPercent);
        assertThat(price.unitPrice()).isEqualByComparingTo("800.00");

        // On a cheaper item the fixed amount is the better one
        Price cheap = price("500", tenPercent, fixed150, twentyPercent);
        assertThat(cheap.discount()).isSameAs(fixed150);
        assertThat(cheap.unitPrice()).isEqualByComparingTo("350.00");
    }

    @Test
    void onATieTheVariantDiscountWins() {
        Discount product = discount(DiscountScope.PRODUCT, DiscountType.FIXED, "100");
        Discount variant = discount(DiscountScope.VARIANT, DiscountType.PERCENT, "10");
        assertThat(price("1000", product, variant).discount()).isSameAs(variant);
    }

    @Test
    void validityWindowIsEvaluatedAtReadTime() {
        Discount future = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "50");
        future.setStartsAt(NOW.plusSeconds(1));
        Discount expired = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "40");
        expired.setEndsAt(NOW); // ends are exclusive
        Discount live = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "5");
        live.setStartsAt(NOW); // starts are inclusive
        live.setEndsAt(NOW.plusSeconds(60));
        Discount inactive = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "30");
        inactive.setActive(false);

        Price price = price("100", future, expired, live, inactive);
        assertThat(price.discount()).isSameAs(live);
        assertThat(price.unitPrice()).isEqualByComparingTo("95.00");
    }

    @Test
    void discountsOfOtherVariantsOrProductsDoNotApply() {
        Discount otherVariant = discount(DiscountScope.VARIANT, DiscountType.PERCENT, "50");
        otherVariant.setVariantId(UUID.randomUUID());
        Discount otherProduct = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "50");
        otherProduct.setProductId(UUID.randomUUID());
        assertThat(price("100", otherVariant, otherProduct).discounted()).isFalse();
    }

    @Test
    void aDiscountThatWouldNotLeaveAPositivePriceIsIgnored() {
        // The list price was lowered after the fixed discount was created
        Discount fixed = discount(DiscountScope.PRODUCT, DiscountType.FIXED, "100");
        Discount small = discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "10");
        Price price = price("100", fixed, small);
        assertThat(price.discount()).isSameAs(small);
        assertThat(price.unitPrice()).isEqualByComparingTo("90.00");
        // 90 % of one cent rounds to the whole cent: ignored as well
        assertThat(price("0.01", discount(DiscountScope.PRODUCT, DiscountType.PERCENT, "90")).discounted()).isFalse();
    }
}
