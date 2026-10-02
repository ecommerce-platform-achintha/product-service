package com.achintha.productservice.category;

import com.achintha.productservice.common.Validation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Body for create (POST) and full update (PUT). On update, {@code slug} is the (possibly new) slug.
 *
 * @param parentSlug optional; the parent must be a top-level category
 * @param active     defaults to {@code true}
 */
public record CategoryRequest(
        @NotBlank @Size(max = 80) @Pattern(regexp = Validation.SLUG_REGEX, message = Validation.SLUG_MESSAGE)
        String slug,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 1000) String description,
        @Size(max = 80) @Pattern(regexp = Validation.SLUG_REGEX, message = Validation.SLUG_MESSAGE) String parentSlug,
        Boolean active) {
}
