package com.achintha.productservice.product;

import com.achintha.productservice.discount.PriceCalculator.Price;
import com.achintha.productservice.discount.PricingService;
import com.achintha.productservice.inventory.Inventory;
import com.achintha.productservice.inventory.InventoryRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads what belongs to a batch of products in a fixed number of queries (options, variants, stock, prices),
 * instead of one query per product. Used by every product view: merchant, admin, public and internal.
 */
@Component
@RequiredArgsConstructor
public class CatalogData {

    private final ProductOptionRepository optionRepository;
    private final VariantRepository variantRepository;
    private final InventoryRepository inventoryRepository;
    private final PricingService pricingService;

    /** Everything about the given products; maps are keyed by product id (variants, options) or variant id. */
    public record Snapshot(
            Map<UUID, List<ProductOption>> options,
            Map<UUID, List<Variant>> variants,
            Map<UUID, Inventory> stock,
            Map<UUID, Price> prices) {

        public List<Variant> variantsOf(UUID productId) {
            return variants.getOrDefault(productId, List.of());
        }

        public List<ProductOption> optionsOf(UUID productId) {
            return options.getOrDefault(productId, List.of());
        }

        public int available(UUID variantId) {
            Inventory inventory = stock.get(variantId);
            return inventory == null ? 0 : inventory.getQuantityAvailable();
        }
    }

    @Transactional(readOnly = true)
    public Snapshot load(Collection<UUID> productIds) {
        if (productIds.isEmpty()) {
            return new Snapshot(Map.of(), Map.of(), Map.of(), Map.of());
        }
        Map<UUID, List<ProductOption>> options = optionRepository.findAllByProductIdInOrderByPositionAsc(productIds)
                .stream().collect(Collectors.groupingBy(ProductOption::getProductId));
        List<Variant> variants = variantRepository.findAllByProductIdInOrderByPositionAsc(productIds);
        return snapshot(options, variants);
    }

    /** For a set of variants only (internal quote): no options. */
    @Transactional(readOnly = true)
    public Snapshot loadVariants(List<Variant> variants) {
        return snapshot(Map.of(), variants);
    }

    private Snapshot snapshot(Map<UUID, List<ProductOption>> options, List<Variant> variants) {
        Map<UUID, List<Variant>> byProduct = variants.stream()
                .collect(Collectors.groupingBy(Variant::getProductId));
        List<UUID> variantIds = variants.stream().map(Variant::getId).toList();
        Map<UUID, Inventory> stock = variantIds.isEmpty() ? Map.of()
                : inventoryRepository.findAllByVariantIdIn(variantIds).stream()
                        .collect(Collectors.toMap(Inventory::getVariantId, Function.identity()));
        return new Snapshot(options, byProduct, stock, pricingService.price(variants));
    }
}
