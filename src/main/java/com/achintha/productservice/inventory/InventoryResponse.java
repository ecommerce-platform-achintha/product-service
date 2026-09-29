package com.achintha.productservice.inventory;

import java.util.UUID;

public record InventoryResponse(UUID productId, int quantityAvailable, int reservedQuantity) {

    public static InventoryResponse from(UUID productId, Inventory inventory) {
        return new InventoryResponse(productId, inventory.getQuantityAvailable(), inventory.getReservedQuantity());
    }
}
