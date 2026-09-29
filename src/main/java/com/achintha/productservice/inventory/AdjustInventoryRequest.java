package com.achintha.productservice.inventory;

import jakarta.validation.constraints.NotNull;

/** {@code delta} is added to the available quantity: negative to take stock out, positive to restock. */
public record AdjustInventoryRequest(@NotNull Integer delta) {
}
