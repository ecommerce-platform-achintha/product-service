package com.achintha.productservice.inventory;

import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.InsufficientStockException;
import com.achintha.productservice.product.Product;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Stock for one product, kept in its own table: stock changes far more often than product details, so this keeps
 * that write traffic (and its row locks) away from the products table.
 */
@Entity
@Table(name = "inventory")
@Getter
@Setter
@NoArgsConstructor
public class Inventory {

    @Id
    @GeneratedValue
    private UUID id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    @Column(name = "quantity_available", nullable = false)
    private int quantityAvailable;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    public Inventory(Product product) {
        this.product = product;
    }

    /**
     * Applies a stock change (negative to decrement). Rejects changes that would leave a negative quantity, leaving
     * the current quantity untouched.
     */
    public void adjust(int delta) {
        long result = (long) quantityAvailable + delta;
        if (result < 0) {
            throw new InsufficientStockException(product.getId(), quantityAvailable, delta);
        }
        if (result > Integer.MAX_VALUE) {
            throw new BadRequestException("Adjustment would exceed the maximum stock quantity");
        }
        quantityAvailable = (int) result;
    }
}
