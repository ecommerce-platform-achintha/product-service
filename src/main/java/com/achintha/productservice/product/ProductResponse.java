package com.achintha.productservice.product;

import com.achintha.productservice.category.CategoryResponse;
import com.achintha.productservice.inventory.Inventory;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record ProductResponse(
        UUID id,
        String name,
        String description,
        BigDecimal price,
        String sku,
        CategoryResponse category,
        Stock stock,
        Instant createdAt,
        Instant updatedAt) {

    public record Stock(int quantityAvailable, int reservedQuantity) {

        static Stock from(Inventory inventory) {
            return inventory == null ? new Stock(0, 0)
                    : new Stock(inventory.getQuantityAvailable(), inventory.getReservedQuantity());
        }
    }

    public static ProductResponse from(Product product, Inventory inventory) {
        return new ProductResponse(product.getId(), product.getName(), product.getDescription(), product.getPrice(),
                product.getSku(), CategoryResponse.from(product.getCategory()), Stock.from(inventory),
                product.getCreatedAt(), product.getUpdatedAt());
    }
}
