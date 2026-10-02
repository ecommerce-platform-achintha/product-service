package com.achintha.productservice.product;

import com.achintha.productservice.common.Validation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * The product aggregate for create (POST) and full update (PUT): product fields, options and variants together.
 * Status is not set here (new products are {@code DRAFT}; see the status endpoint).
 *
 * <p>Either list {@code variants} (required when {@code options} are given), or, for a simple product, give
 * {@code sku} + {@code listPrice} (+ {@code initialStock}) and a default variant is created.
 *
 * @param codAllowed defaults to {@code true}
 */
public record ProductRequest(
        @NotBlank @Size(max = 200) String name,
        @Size(max = Validation.DESCRIPTION_MAX) String description,
        @NotBlank @Size(max = 80) String categorySlug,
        Boolean codAllowed,
        @Size(max = Validation.MAX_IMAGES)
        List<@NotBlank @Pattern(regexp = Validation.STORAGE_KEY_REGEX) String> imageKeys,
        @Size(max = 5) List<@Valid @NotNull OptionRequest> options,
        @Size(max = 100) List<@Valid @NotNull VariantRequest> variants,
        @Pattern(regexp = Validation.SKU_REGEX, message = Validation.SKU_MESSAGE) String sku,
        @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal listPrice,
        @PositiveOrZero @Max(1_000_000) Integer initialStock) {

    /** An option such as Size with its allowed values. */
    public record OptionRequest(
            @NotBlank @Size(max = 50) String name,
            @NotEmpty @Size(max = 50) List<@NotBlank @Size(max = 50) String> values) {
    }

    /**
     * @param publicId     PUT only: the existing variant to update; omit for a new variant. Existing variants that
     *                     are not listed become {@code INACTIVE} (variants are never deleted)
     * @param attributes   option name to value; must cover every option exactly; empty for a simple product
     * @param status       defaults to {@code ACTIVE}
     * @param initialStock new variants only (later changes go through the stock endpoint)
     */
    public record VariantRequest(
            @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String publicId,
            @NotBlank @Pattern(regexp = Validation.SKU_REGEX, message = Validation.SKU_MESSAGE) String sku,
            @Size(max = 5) Map<@NotBlank @Size(max = 50) String, @NotBlank @Size(max = 50) String> attributes,
            @NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal listPrice,
            VariantStatus status,
            @Size(max = Validation.MAX_IMAGES)
            List<@NotBlank @Pattern(regexp = Validation.STORAGE_KEY_REGEX) String> imageKeys,
            @PositiveOrZero @Max(1_000_000) Integer initialStock) {
    }

    /** Body of the status endpoint. */
    public record StatusRequest(@NotNull ProductStatus status) {
    }
}
