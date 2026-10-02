package com.achintha.productservice.category;

/** A category as clients see it; the slug is its id. */
public record CategoryResponse(String slug, String name, String description, String parentSlug, boolean active) {

    public static CategoryResponse from(Category category) {
        return new CategoryResponse(category.getSlug(), category.getName(), category.getDescription(),
                category.getParent() == null ? null : category.getParent().getSlug(), category.isActive());
    }

    /** Short reference embedded in product responses. */
    public record Ref(String slug, String name) {

        public static Ref from(Category category) {
            return new Ref(category.getSlug(), category.getName());
        }
    }
}
