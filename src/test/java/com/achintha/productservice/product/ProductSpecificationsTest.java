package com.achintha.productservice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Checks which predicates are built for each filter combination, without a database. */
@SuppressWarnings("unchecked")
class ProductSpecificationsTest {

    private final Root<Product> root = mock(Root.class);
    private final CriteriaQuery<?> query = mock(CriteriaQuery.class);
    private final CriteriaBuilder cb = mock(CriteriaBuilder.class);

    private final Path<Object> categoryPath = mock(Path.class);
    private final Path<Object> categoryIdPath = mock(Path.class);
    private final Path<BigDecimal> pricePath = mock(Path.class);
    private final Path<String> namePath = mock(Path.class);
    private final Expression<String> lowerName = mock(Expression.class);

    private final Predicate categoryPredicate = mock(Predicate.class);
    private final Predicate minPricePredicate = mock(Predicate.class);
    private final Predicate maxPricePredicate = mock(Predicate.class);
    private final Predicate namePredicate = mock(Predicate.class);

    private final UUID categoryId = UUID.randomUUID();
    private final BigDecimal min = new BigDecimal("10.00");
    private final BigDecimal max = new BigDecimal("99.99");

    @BeforeEach
    void setUp() {
        doReturn(categoryPath).when(root).get("category");
        doReturn(categoryIdPath).when(categoryPath).get("id");
        doReturn(pricePath).when(root).get("price");
        doReturn(namePath).when(root).get("name");
        when(cb.lower(namePath)).thenReturn(lowerName);

        when(cb.equal(categoryIdPath, categoryId)).thenReturn(categoryPredicate);
        when(cb.greaterThanOrEqualTo(pricePath, min)).thenReturn(minPricePredicate);
        when(cb.lessThanOrEqualTo(pricePath, max)).thenReturn(maxPricePredicate);
        when(cb.like(any(Expression.class), any(String.class), any(Character.class))).thenReturn(namePredicate);
        when(cb.and(any(Expression.class), any(Expression.class))).thenAnswer(inv -> mock(Predicate.class));
    }

    private Predicate predicateFor(ProductFilter filter) {
        return ProductSpecifications.fromFilter(filter).toPredicate(root, query, cb);
    }

    @Test
    void noFiltersMeansNoRestriction() {
        assertThat(predicateFor(ProductFilter.none())).isNull();
        verifyNoInteractions(cb);
    }

    @Test
    void blankSearchTextIsIgnored() {
        assertThat(predicateFor(new ProductFilter(null, null, null, "   "))).isNull();
        verifyNoInteractions(cb);
    }

    @Test
    void categoryOnlyFiltersOnCategoryId() {
        assertThat(predicateFor(new ProductFilter(categoryId, null, null, null))).isSameAs(categoryPredicate);
        verify(cb).equal(categoryIdPath, categoryId);
        verify(cb, never()).and(any(Expression.class), any(Expression.class));
    }

    @Test
    void minPriceOnlyIsInclusiveLowerBound() {
        assertThat(predicateFor(new ProductFilter(null, min, null, null))).isSameAs(minPricePredicate);
        verify(cb, never()).lessThanOrEqualTo(any(Expression.class), any(BigDecimal.class));
    }

    @Test
    void maxPriceOnlyIsInclusiveUpperBound() {
        assertThat(predicateFor(new ProductFilter(null, null, max, null))).isSameAs(maxPricePredicate);
        verify(cb, never()).greaterThanOrEqualTo(any(Expression.class), any(BigDecimal.class));
    }

    @Test
    void searchTextIsCaseInsensitivePartialMatchOnName() {
        assertThat(predicateFor(new ProductFilter(null, null, null, "  LapTop "))).isSameAs(namePredicate);
        verify(cb).lower(namePath);
        verify(cb).like(lowerName, "%laptop%", '\\');
    }

    @Test
    void allFiltersAreCombinedWithAnd() {
        Predicate result = predicateFor(new ProductFilter(categoryId, min, max, "phone"));

        assertThat(result).isNotNull();
        verify(cb).equal(categoryIdPath, categoryId);
        verify(cb).greaterThanOrEqualTo(pricePath, min);
        verify(cb).lessThanOrEqualTo(pricePath, max);
        verify(cb).like(lowerName, "%phone%", '\\');
        // four predicates -> three ANDs
        verify(cb, times(3)).and(any(Expression.class), any(Expression.class));
    }

    @Test
    void likeWildcardsInSearchTextAreEscaped() {
        assertThat(ProductSpecifications.containsPattern("50%_off\\"))
                .isEqualTo("%50\\%\\_off\\\\%");
    }
}
