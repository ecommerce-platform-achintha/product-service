package com.achintha.productservice.discount;

import com.achintha.productservice.discount.PriceCalculator.Price;
import com.achintha.productservice.product.Variant;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Prices a batch of variants with one discount query (detail pages, search results, quotes). */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final DiscountRepository discountRepository;
    private final Clock clock;

    /** @return variant id to price, evaluated now */
    @Transactional(readOnly = true)
    public Map<UUID, Price> price(Collection<Variant> variants) {
        if (variants.isEmpty()) {
            return Map.of();
        }
        Instant now = clock.instant();
        List<UUID> productIds = variants.stream().map(Variant::getProductId).distinct().toList();
        Map<UUID, List<Discount>> byProduct = discountRepository.findAllByProductIdInAndActiveTrue(productIds)
                .stream().collect(Collectors.groupingBy(Discount::getProductId));
        Map<UUID, Price> prices = new HashMap<>();
        for (Variant variant : variants) {
            prices.put(variant.getId(), PriceCalculator.price(variant.getProductId(), variant.getId(),
                    variant.getListPrice(), byProduct.getOrDefault(variant.getProductId(), List.of()), now));
        }
        return prices;
    }
}
