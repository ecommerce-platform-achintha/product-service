package com.achintha.productservice.inventory;

import com.achintha.productservice.exception.NotFoundException;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;

    @Transactional
    public InventoryResponse adjust(UUID productId, int delta) {
        // Every product gets its inventory row on creation, so a missing row means a missing product
        Inventory inventory = inventoryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new NotFoundException("Product not found: " + productId));
        inventory.adjust(delta);
        return InventoryResponse.from(productId, inventory);
    }
}
