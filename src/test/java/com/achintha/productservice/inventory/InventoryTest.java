package com.achintha.productservice.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.InsufficientStockException;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.product.Product;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The negative-quantity guard, on the entity and through the service. */
class InventoryTest {

    private final UUID productId = UUID.randomUUID();

    private Inventory inventoryWith(int quantity) {
        Product product = new Product();
        product.setId(productId);
        Inventory inventory = new Inventory(product);
        inventory.setQuantityAvailable(quantity);
        return inventory;
    }

    @Test
    void newInventoryStartsEmpty() {
        Inventory inventory = new Inventory(new Product());
        assertThat(inventory.getQuantityAvailable()).isZero();
        assertThat(inventory.getReservedQuantity()).isZero();
    }

    @Test
    void positiveDeltaAddsStock() {
        Inventory inventory = inventoryWith(5);
        inventory.adjust(3);
        assertThat(inventory.getQuantityAvailable()).isEqualTo(8);
    }

    @Test
    void negativeDeltaCanTakeStockToExactlyZero() {
        Inventory inventory = inventoryWith(5);
        inventory.adjust(-5);
        assertThat(inventory.getQuantityAvailable()).isZero();
    }

    @Test
    void deltaThatWouldGoNegativeIsRejectedAndLeavesQuantityUnchanged() {
        Inventory inventory = inventoryWith(5);

        assertThatThrownBy(() -> inventory.adjust(-6))
                .isInstanceOf(InsufficientStockException.class)
                .hasMessageContaining(productId.toString())
                .hasMessageContaining("available 5");
        assertThat(inventory.getQuantityAvailable()).isEqualTo(5);
    }

    @Test
    void hugeNegativeDeltaDoesNotOverflowPastTheGuard() {
        Inventory inventory = inventoryWith(5);

        assertThatThrownBy(() -> inventory.adjust(Integer.MIN_VALUE)).isInstanceOf(InsufficientStockException.class);
        assertThat(inventory.getQuantityAvailable()).isEqualTo(5);
    }

    @Test
    void deltaThatWouldOverflowIsRejected() {
        Inventory inventory = inventoryWith(Integer.MAX_VALUE);

        assertThatThrownBy(() -> inventory.adjust(1)).isInstanceOf(BadRequestException.class);
        assertThat(inventory.getQuantityAvailable()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void serviceAdjustsTheLockedRow() {
        InventoryRepository repository = mock(InventoryRepository.class);
        Inventory inventory = inventoryWith(10);
        when(repository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));

        InventoryResponse response = new InventoryService(repository).adjust(productId, -4);

        assertThat(response.productId()).isEqualTo(productId);
        assertThat(response.quantityAvailable()).isEqualTo(6);
    }

    @Test
    void serviceRejectsNegativeResult() {
        InventoryRepository repository = mock(InventoryRepository.class);
        Inventory inventory = inventoryWith(2);
        when(repository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> new InventoryService(repository).adjust(productId, -3))
                .isInstanceOf(InsufficientStockException.class);
        assertThat(inventory.getQuantityAvailable()).isEqualTo(2);
    }

    @Test
    void serviceReportsUnknownProductAsNotFound() {
        InventoryRepository repository = mock(InventoryRepository.class);
        when(repository.findByProductIdForUpdate(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> new InventoryService(repository).adjust(productId, 1))
                .isInstanceOf(NotFoundException.class);
    }
}
