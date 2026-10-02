package com.achintha.productservice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.achintha.productservice.common.PublicIdGenerator;
import com.achintha.productservice.common.TextSanitizer;
import com.achintha.productservice.exception.ApiException;
import com.achintha.productservice.search.SearchSort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Pure helpers of the catalog: LIKE escaping, sort parsing, public ids, status transitions, text sanitising. */
class ProductSpecificationsTest {

    @Test
    void likeWildcardsAreEscaped() {
        assertThat(ProductSpecifications.containsPattern("  50% OFF_now\\ ")).isEqualTo("%50\\% off\\_now\\\\%");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {"relevance|RELEVANCE", "price|PRICE_ASC", "price,asc|PRICE_ASC",
            "PRICE,DESC|PRICE_DESC", "newest|NEWEST", "rating|RATING"})
    void whitelistedSortsAreParsed(String sort, SearchSort expected) {
        assertThat(SearchSort.parse(sort)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"name", "price,up", "newest,asc", "rating,desc,x", "id"})
    void otherSortsAreRejected(String sort) {
        assertThatThrownBy(() -> SearchSort.parse(sort)).isInstanceOf(ApiException.class)
                .hasMessageContaining("sort must be one of");
    }

    @Test
    void publicIdsUseThePrefixMonthAndCrockfordSuffix() {
        PublicIdGenerator generator = new PublicIdGenerator(
                Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC));
        String id = generator.generate(PublicIdGenerator.ITEM_PREFIX);
        assertThat(id).startsWith("ITM-2610-");
        assertThat(PublicIdGenerator.isValid(id)).isTrue();
        assertThat(id.substring(9)).doesNotContain("I", "L", "O", "U");
    }

    @Test
    void statusTransitions() {
        assertThat(ProductStatus.DRAFT.canMoveTo(ProductStatus.ACTIVE)).isTrue();
        assertThat(ProductStatus.ACTIVE.canMoveTo(ProductStatus.DRAFT)).isTrue();
        assertThat(ProductStatus.ACTIVE.canMoveTo(ProductStatus.ARCHIVED)).isTrue();
        assertThat(ProductStatus.ARCHIVED.canMoveTo(ProductStatus.DRAFT)).isTrue();
        assertThat(ProductStatus.ARCHIVED.canMoveTo(ProductStatus.ACTIVE)).isFalse();
    }

    @Test
    void freeTextIsSanitised() {
        assertThat(TextSanitizer.clean("Nice <b>bold</b> & <img src=x onerror=alert(1)>cheap"))
                .isEqualTo("Nice bold & cheap");
        assertThat(TextSanitizer.cleanLine("  two\n lines ")).isEqualTo("two lines");
        assertThat(TextSanitizer.clean("   ")).isNull();
    }
}
