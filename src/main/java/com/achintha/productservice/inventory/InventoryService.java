package com.achintha.productservice.inventory;

import com.achintha.productservice.audit.AuditService;
import com.achintha.productservice.common.TextSanitizer;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ConflictException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.InsufficientStockException;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.inventory.AdjustInventoryRequest.Mode;
import com.achintha.productservice.ports.NotificationPort;
import com.achintha.productservice.product.MerchantAccess;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.security.Actor;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The merchant stock edit, the only user-facing way to change inventory (the old user-callable inventory PATCH is
 * gone). Optimistic locking ({@code @Version}): if a reservation or another edit changed the row since it was read,
 * the edit fails with 409 {@code CONCURRENT_MODIFICATION} instead of overwriting it. Every edit is audited.
 */
@Service
@RequiredArgsConstructor
public class InventoryService {

    private final InventoryRepository inventoryRepository;
    private final VariantRepository variantRepository;
    private final AuditService auditService;
    private final NotificationPort notifications;
    private final Clock clock;

    @Value("${app.catalog.low-stock-threshold:5}")
    private int lowStockThreshold;

    @Transactional
    public InventoryResponse adjust(Actor actor, String variantPublicId, AdjustInventoryRequest request) {
        MerchantAccess.requireCanEdit(actor);
        Variant variant = variantRepository.findByPublicIdAndStoreId(variantPublicId, MerchantAccess.storeIdOf(actor))
                .orElseThrow(() -> new NotFoundException("Variant not found"));
        String reason = TextSanitizer.cleanLine(request.reason());
        if (reason == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "reason must not be blank");
        }
        Inventory inventory = inventoryRepository.findById(variant.getId())
                .orElseGet(() -> new Inventory(variant.getId(), 0, clock.instant()));
        if (request.expectedVersion() != null && request.expectedVersion() != inventory.getVersion()) {
            throw new ConflictException(ErrorCode.CONCURRENT_MODIFICATION,
                    "The stock was changed since version " + request.expectedVersion() + "; reload and try again");
        }
        int before = inventory.getQuantityAvailable();
        long target = request.mode() == Mode.SET ? request.quantity() : (long) before + request.quantity();
        if (request.mode() == Mode.SET && request.quantity() < 0) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "quantity must be 0 or more for SET");
        }
        if (target < 0) {
            throw new InsufficientStockException(variant.getPublicId(), before, -request.quantity());
        }
        if (target > Integer.MAX_VALUE) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "The stock would exceed the maximum quantity");
        }
        Instant now = clock.instant();
        inventory.setQuantityAvailable((int) target);
        inventory.setUpdatedAt(now);
        // saveAndFlush: the version check happens here, so a lost race answers 409 (ObjectOptimisticLockingFailure)
        inventory = inventoryRepository.saveAndFlush(inventory);

        auditService.record(actor, request.mode() == Mode.SET ? "STOCK_SET" : "STOCK_ADJUSTED",
                AuditService.TARGET_VARIANT, variant.getPublicId(), stock(before, inventory.getReservedQuantity()),
                stock(inventory.getQuantityAvailable(), inventory.getReservedQuantity()), reason);
        if (inventory.getQuantityAvailable() <= lowStockThreshold && before > lowStockThreshold) {
            notifications.notifyStore(variant.getStoreId(), "LOW_STOCK", variant.getSku());
        }
        return InventoryResponse.of(variant.getPublicId(), variant.getSku(), inventory);
    }

    private static Map<String, Object> stock(int available, int reserved) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("quantityAvailable", available);
        map.put("reservedQuantity", reserved);
        return map;
    }
}
