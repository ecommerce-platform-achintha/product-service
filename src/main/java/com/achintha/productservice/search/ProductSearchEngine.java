package com.achintha.productservice.search;

import com.achintha.productservice.product.ProductFilter;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Finds visible products for the public search and returns their ids in result order; the caller loads and renders
 * them. The current implementation is plain PostgreSQL ({@link SqlProductSearchEngine}); a search engine (e.g.
 * OpenSearch fed by {@code product-events}) can replace it by implementing this interface and setting
 * {@code app.search.engine}.
 *
 * <p>Contract: only products that are {@code ACTIVE}, whose store is publicly visible and that have at least one
 * active variant; prices are effective prices at {@code now} (see {@code PriceCalculator}).
 */
public interface ProductSearchEngine {

    /** @param pageable page number and size only (ordering comes from {@code sort}) */
    Page<UUID> search(ProductFilter filter, SearchSort sort, Pageable pageable, Instant now);
}
