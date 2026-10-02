package com.achintha.productservice.inventory;

import com.achintha.productservice.common.Validation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Merchant stock edit ({@code STOCK_EDIT}) of the units available for sale. Held units are not touched.
 *
 * @param mode            {@code SET}: available becomes {@code quantity}; {@code DELTA}: {@code quantity} is added
 *                        (negative to take stock out; the result may not go below zero)
 * @param reason          why, for the audit log (e.g. "restock", "damaged")
 * @param expectedVersion optional: the {@code version} last read; a concurrent change then answers 409
 */
public record AdjustInventoryRequest(
        @NotNull Mode mode,
        @NotNull @Min(-1_000_000) @Max(1_000_000) Integer quantity,
        @NotBlank @Size(max = Validation.REASON_MAX) String reason,
        Long expectedVersion) {

    public enum Mode {
        SET,
        DELTA
    }
}
