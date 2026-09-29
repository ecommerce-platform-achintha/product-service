package com.achintha.productservice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.achintha.productservice.category.CategoryService;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.inventory.InventoryRepository;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/** Input checks the search applies before building the query. */
class ProductServiceTest {

    private final ProductRepository productRepository = mock(ProductRepository.class);
    private final ProductService service = new ProductService(productRepository, mock(InventoryRepository.class),
            mock(CategoryService.class));

    @Test
    void rejectsMinPriceAboveMaxPrice() {
        ProductFilter filter = new ProductFilter(null, new BigDecimal("50"), new BigDecimal("10"), null);

        assertThatThrownBy(() -> service.search(filter, PageRequest.of(0, 20)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minPrice");
        verifyNoInteractions(productRepository);
    }

    @Test
    void rejectsUnknownSortField() {
        Pageable pageable = PageRequest.of(0, 20, Sort.by("category.secretField"));

        assertThatThrownBy(() -> service.search(ProductFilter.none(), pageable))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("category.secretField");
        verifyNoInteractions(productRepository);
    }

    @Test
    void keepsRequestedSortAndAddsIdTieBreaker() {
        Pageable result = ProductService.withStableSort(PageRequest.of(2, 10, Sort.by(Sort.Direction.ASC, "price")));

        assertThat(result.getPageNumber()).isEqualTo(2);
        assertThat(result.getPageSize()).isEqualTo(10);
        assertThat(result.getSort()).containsExactly(Sort.Order.asc("price"), Sort.Order.asc("id"));
    }
}
