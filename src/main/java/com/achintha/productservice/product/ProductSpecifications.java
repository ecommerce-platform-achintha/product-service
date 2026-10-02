package com.achintha.productservice.product;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/**
 * Builds the merchant and admin product list queries from whichever filters are present, instead of one query per
 * combination. (The public search is a separate engine, see {@code ProductSearchEngine}.)
 */
public final class ProductSpecifications {

    static final char LIKE_ESCAPE = '\\';

    private ProductSpecifications() {
    }

    /** The store's products: the store filter is always applied (ownership scoping). */
    public static Specification<Product> forStore(UUID storeId, ProductStatus status, String q) {
        List<Specification<Product>> specs = new ArrayList<>();
        specs.add(inStore(storeId));
        if (status != null) {
            specs.add(hasStatus(status));
        }
        if (q != null && !q.isBlank()) {
            specs.add(nameContains(q));
        }
        return Specification.allOf(specs);
    }

    static Specification<Product> inStore(UUID storeId) {
        return (root, query, cb) -> cb.equal(root.get("storeId"), storeId);
    }

    static Specification<Product> hasStatus(ProductStatus status) {
        return (root, query, cb) -> cb.equal(root.get("status"), status);
    }

    /** Case-insensitive partial match on the name. */
    static Specification<Product> nameContains(String text) {
        String pattern = containsPattern(text);
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE);
    }

    /** Lower-cases and escapes LIKE wildcards so user input such as "50%" is matched literally. */
    public static String containsPattern(String text) {
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
