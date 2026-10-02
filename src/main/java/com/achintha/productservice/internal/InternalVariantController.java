package com.achintha.productservice.internal;

import com.achintha.productservice.discount.PriceCalculator.Price;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.internal.QuoteDtos.QuoteItem;
import com.achintha.productservice.internal.QuoteDtos.QuoteRequest;
import com.achintha.productservice.internal.QuoteDtos.QuoteResponse;
import com.achintha.productservice.ports.ImageStoragePort;
import com.achintha.productservice.product.CatalogData;
import com.achintha.productservice.product.CatalogData.Snapshot;
import com.achintha.productservice.product.Product;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.product.ProductStatus;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.store.StoreVisibility;
import com.achintha.productservice.store.StoreVisibilityService;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Live price and stock of variants for order-service's cart and checkout ({@code ROLE_SERVICE} only; never routed
 * by the gateway; not in the public docs). Inactive or hidden variants are still returned, flagged, so the cart can
 * explain why a line cannot be bought.
 */
@RestController
@RequestMapping("/internal/variants")
@PreAuthorize("hasAuthority('ROLE_SERVICE')")
@RequiredArgsConstructor
public class InternalVariantController {

    private final VariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final StoreVisibilityService storeVisibility;
    private final CatalogData catalogData;
    private final ImageStoragePort imageStorage;

    @PostMapping("/quote")
    @Transactional(readOnly = true)
    public QuoteResponse quote(@Valid @RequestBody QuoteRequest request) {
        List<UUID> ids = request.variantIds() == null ? List.of() : request.variantIds();
        List<String> publicIds = request.variantPublicIds() == null ? List.of() : request.variantPublicIds();
        if (ids.size() + publicIds.size() > 200) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "At most 200 variants per quote");
        }
        Map<UUID, Variant> found = new LinkedHashMap<>();
        if (!ids.isEmpty()) {
            variantRepository.findAllByIdIn(ids).forEach(v -> found.put(v.getId(), v));
        }
        if (!publicIds.isEmpty()) {
            variantRepository.findAllByPublicIdIn(publicIds).forEach(v -> found.put(v.getId(), v));
        }
        List<String> missing = new ArrayList<>();
        Set<String> foundPublicIds = found.values().stream().map(Variant::getPublicId).collect(Collectors.toSet());
        ids.stream().filter(id -> !found.containsKey(id)).map(UUID::toString).forEach(missing::add);
        publicIds.stream().filter(id -> !foundPublicIds.contains(id)).forEach(missing::add);

        List<Variant> variants = new ArrayList<>(found.values());
        Map<UUID, Product> products = productRepository.findAllByIdIn(
                        variants.stream().map(Variant::getProductId).collect(Collectors.toCollection(HashSet::new)))
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        Map<UUID, StoreVisibility> stores = storeVisibility.findAll(
                variants.stream().map(Variant::getStoreId).distinct().toList());
        Snapshot data = catalogData.loadVariants(variants);
        List<QuoteItem> items = variants.stream()
                .map(v -> item(v, products.get(v.getProductId()), stores.get(v.getStoreId()), data))
                .toList();
        return new QuoteResponse(items, missing);
    }

    private QuoteItem item(Variant variant, Product product, StoreVisibility store, Snapshot data) {
        Price price = data.prices().get(variant.getId());
        boolean productActive = product.getStatus() == ProductStatus.ACTIVE;
        boolean storeVisible = store != null && store.isPubliclyVisible();
        List<String> images = !variant.getImageKeys().isEmpty() ? variant.getImageKeys() : product.getImageKeys();
        return new QuoteItem(variant.getId(), variant.getPublicId(), product.getId(), product.getPublicId(),
                product.getStoreId(), store == null ? null : store.getStorePublicId(),
                store == null ? null : store.getStoreName(), product.getName(), variant.label(), variant.getSku(),
                variant.getAttributes(), images.isEmpty() ? null : imageStorage.urlFor(images.getFirst()),
                price.listPrice(), price.discountAmount(), price.unitPrice(),
                price.discount() == null ? null : price.discount().getPublicId(), data.available(variant.getId()),
                product.isCodAllowed(), productActive, variant.isActive(), storeVisible,
                productActive && variant.isActive() && storeVisible);
    }
}
