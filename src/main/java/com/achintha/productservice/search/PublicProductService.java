package com.achintha.productservice.search;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.category.CategoryRepository;
import com.achintha.productservice.category.CategoryResponse;
import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.discount.DiscountDtos.AppliedDiscount;
import com.achintha.productservice.discount.PriceCalculator.Price;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.ports.ImageStoragePort;
import com.achintha.productservice.product.CatalogData;
import com.achintha.productservice.product.CatalogData.Snapshot;
import com.achintha.productservice.product.Product;
import com.achintha.productservice.product.ProductFilter;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.product.ProductStatus;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.search.PublicProductDtos.Option;
import com.achintha.productservice.search.PublicProductDtos.ProductDetail;
import com.achintha.productservice.search.PublicProductDtos.ProductSummary;
import com.achintha.productservice.search.PublicProductDtos.StoreRef;
import com.achintha.productservice.search.PublicProductDtos.VariantView;
import com.achintha.productservice.store.StoreVisibility;
import com.achintha.productservice.store.StoreVisibilityService;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public catalog (D16): search and product detail. A product is public only when it is {@code ACTIVE}, its store is
 * visible (store-service says so and the owner is not banned or in the ban grace period) and it has at least one
 * active variant; anything else answers as if it did not exist.
 */
@Service
@RequiredArgsConstructor
public class PublicProductService {

    private final ProductSearchEngine searchEngine;
    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final StoreVisibilityService storeVisibility;
    private final CatalogData catalogData;
    private final ImageStoragePort imageStorage;
    private final Clock clock;

    @Value("${app.catalog.low-stock-threshold:5}")
    private int lowStockThreshold;

    /**
     * @param categorySlug a category (its sub-categories included); unknown: empty result
     * @param storePublicId a store ({@code STR-}); unknown: empty result
     */
    @Transactional(readOnly = true)
    public PageResponse<ProductSummary> search(String q, String categorySlug, String storePublicId,
                                               BigDecimal minPrice, BigDecimal maxPrice, boolean inStock,
                                               BigDecimal minRating, SearchSort sort, Pageable pageable) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "minPrice must not be greater than maxPrice");
        }
        List<UUID> categoryIds = null;
        if (categorySlug != null && !categorySlug.isBlank()) {
            Optional<Category> category = categoryRepository.findBySlug(categorySlug.trim());
            if (category.isEmpty()) {
                return PageResponse.from(Page.empty(pageable));
            }
            categoryIds = categoryRepository.selfAndChildren(category.get().getId());
        }
        UUID storeId = null;
        if (storePublicId != null && !storePublicId.isBlank()) {
            Optional<StoreVisibility> store = storeVisibility.findByPublicId(storePublicId.trim());
            if (store.isEmpty()) {
                return PageResponse.from(Page.empty(pageable));
            }
            storeId = store.get().getStoreId();
        }
        ProductFilter filter = new ProductFilter(q, categoryIds, storeId, minPrice, maxPrice, inStock, minRating);
        Page<UUID> ids = searchEngine.search(filter, sort, pageable, clock.instant());
        if (ids.isEmpty()) {
            return PageResponse.of(ids, List.of());
        }
        Map<UUID, Product> products = productRepository.findAllByIdIn(ids.getContent()).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));
        Snapshot data = catalogData.load(ids.getContent());
        Map<UUID, StoreVisibility> stores = storeVisibility.findAll(
                products.values().stream().map(Product::getStoreId).distinct().toList());
        List<ProductSummary> rows = ids.getContent().stream()
                .map(products::get)
                .map(p -> summary(p, data, stores.get(p.getStoreId())))
                .toList();
        return PageResponse.of(ids, rows);
    }

    @Transactional(readOnly = true)
    public ProductDetail detail(String publicId) {
        Product product = requireVisible(publicId);
        Snapshot data = catalogData.load(List.of(product.getId()));
        List<Variant> active = data.variantsOf(product.getId()).stream().filter(Variant::isActive).toList();
        if (active.isEmpty()) {
            throw notFound();
        }
        StoreVisibility store = storeVisibility.find(product.getStoreId()).orElseThrow(PublicProductService::notFound);
        List<Option> options = data.optionsOf(product.getId()).stream()
                .map(o -> new Option(o.getName(), o.getValues())).toList();
        List<VariantView> variants = active.stream().map(v -> variant(v, data)).toList();
        return new ProductDetail(product.getPublicId(), product.getName(), product.getDescription(),
                CategoryResponse.Ref.from(product.getCategory()), storeRef(store), product.isCodAllowed(),
                urls(product.getImageKeys()), options, variants, product.getRatingAvg(), product.getRatingCount(),
                product.getCreatedAt());
    }

    /** The product if it is public right now; 404 otherwise (also used by the public reviews). */
    @Transactional(readOnly = true)
    public Product requireVisible(String publicId) {
        Product product = productRepository.findByPublicId(publicId).orElseThrow(PublicProductService::notFound);
        if (product.getStatus() != ProductStatus.ACTIVE || !storeVisibility.isPubliclyVisible(product.getStoreId())) {
            throw notFound();
        }
        return product;
    }

    private ProductSummary summary(Product product, Snapshot data, StoreVisibility store) {
        List<Variant> active = data.variantsOf(product.getId()).stream().filter(Variant::isActive).toList();
        Price cheapest = active.stream().map(v -> data.prices().get(v.getId()))
                .min(Comparator.comparing(Price::unitPrice)).orElse(null);
        boolean inStock = active.stream().anyMatch(v -> data.available(v.getId()) > 0);
        String image = product.getImageKeys().isEmpty() ? null : imageStorage.urlFor(product.getImageKeys().getFirst());
        return new ProductSummary(product.getPublicId(), product.getName(),
                CategoryResponse.Ref.from(product.getCategory()), storeRef(store), image,
                cheapest == null ? null : cheapest.unitPrice(), cheapest == null ? null : cheapest.listPrice(),
                cheapest != null && cheapest.discounted(), inStock, product.getRatingAvg(), product.getRatingCount());
    }

    private VariantView variant(Variant variant, Snapshot data) {
        Price price = data.prices().get(variant.getId());
        int available = data.available(variant.getId());
        return new VariantView(variant.getPublicId(), variant.label(), variant.getAttributes(), price.listPrice(),
                price.unitPrice(), AppliedDiscount.from(price), available > 0,
                available > 0 && available <= lowStockThreshold, urls(variant.getImageKeys()));
    }

    private static StoreRef storeRef(StoreVisibility store) {
        return store == null ? null : new StoreRef(store.getStorePublicId(), store.getStoreName());
    }

    private List<String> urls(List<String> keys) {
        return keys.stream().map(imageStorage::urlFor).toList();
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Product not found");
    }
}
