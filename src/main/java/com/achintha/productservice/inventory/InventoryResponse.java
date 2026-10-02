package com.achintha.productservice.inventory;

/**
 * A variant's stock as its merchant sees it. Never shown publicly (public responses carry availability flags only).
 *
 * @param version pass it back as {@code expectedVersion} on a stock edit to detect concurrent changes
 */
public record InventoryResponse(String variantPublicId, String sku, int quantityAvailable, int reservedQuantity,
                                long version) {

    public static InventoryResponse of(String variantPublicId, String sku, Inventory inventory) {
        return inventory == null ? new InventoryResponse(variantPublicId, sku, 0, 0, 0)
                : new InventoryResponse(variantPublicId, sku, inventory.getQuantityAvailable(),
                        inventory.getReservedQuantity(), inventory.getVersion());
    }
}
