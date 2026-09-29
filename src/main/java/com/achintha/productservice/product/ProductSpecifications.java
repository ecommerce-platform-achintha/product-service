package com.achintha.productservice.product;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.jpa.domain.Specification;

/** Builds the product search query from whichever filters are present, instead of one query per combination. */
public final class ProductSpecifications {

    static final char LIKE_ESCAPE = '\\';

    private ProductSpecifications() {
    }

    public static Specification<Product> fromFilter(ProductFilter filter) {
        List<Specification<Product>> specs = new ArrayList<>();
        if (filter.categoryId() != null) {
            specs.add(inCategory(filter.categoryId()));
        }
        if (filter.minPrice() != null) {
            specs.add(priceAtLeast(filter.minPrice()));
        }
        if (filter.maxPrice() != null) {
            specs.add(priceAtMost(filter.maxPrice()));
        }
        if (filter.q() != null && !filter.q().isBlank()) {
            specs.add(nameContains(filter.q()));
        }
        return Specification.allOf(specs);
    }

    static Specification<Product> inCategory(UUID categoryId) {
        return (root, query, cb) -> cb.equal(root.get("category").get("id"), categoryId);
    }

    static Specification<Product> priceAtLeast(BigDecimal minPrice) {
        return (root, query, cb) -> cb.greaterThanOrEqualTo(root.get("price"), minPrice);
    }

    static Specification<Product> priceAtMost(BigDecimal maxPrice) {
        return (root, query, cb) -> cb.lessThanOrEqualTo(root.get("price"), maxPrice);
    }

    /** Case-insensitive partial match on the name. */
    static Specification<Product> nameContains(String text) {
        String pattern = containsPattern(text);
        return (root, query, cb) -> cb.like(cb.lower(root.get("name")), pattern, LIKE_ESCAPE);
    }

    /** Lower-cases and escapes LIKE wildcards so user input such as "50%" is matched literally. */
    static String containsPattern(String text) {
        String escaped = text.trim().toLowerCase(Locale.ROOT)
                .replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
