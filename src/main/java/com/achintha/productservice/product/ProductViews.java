package com.achintha.productservice.product;

import com.achintha.productservice.category.CategoryResponse;
import com.achintha.productservice.discount.DiscountDtos.AppliedDiscount;
import com.achintha.productservice.discount.PriceCalculator.Price;
import com.achintha.productservice.inventory.Inventory;
import com.achintha.productservice.inventory.InventoryResponse;
import com.achintha.productservice.ports.ImageStoragePort;
import com.achintha.productservice.product.CatalogData.Snapshot;
import com.achintha.productservice.product.ProductResponse.OptionResponse;
import com.achintha.productservice.product.ProductResponse.Summary;
import com.achintha.productservice.product.ProductResponse.VariantResponse;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Builds the merchant / admin views of products. */
@Component
@RequiredArgsConstructor
public class ProductViews {

    private final CatalogData catalogData;
    private final ImageStoragePort imageStorage;

    @Transactional(readOnly = true)
    public ProductResponse detail(Product product) {
        return detail(product, catalogData.load(List.of(product.getId())));
    }

    @Transactional(readOnly = true)
    public List<Summary> summaries(List<Product> products) {
        Snapshot data = catalogData.load(products.stream().map(Product::getId).toList());
        return products.stream().map(p -> summary(p, data)).toList();
    }

    private ProductResponse detail(Product product, Snapshot data) {
        List<OptionResponse> options = data.optionsOf(product.getId()).stream()
                .map(o -> new OptionResponse(o.getName(), o.getValues()))
                .toList();
        List<VariantResponse> variants = data.variantsOf(product.getId()).stream()
                .map(v -> variant(v, data))
                .toList();
        return new ProductResponse(product.getPublicId(), product.getName(), product.getDescription(),
                CategoryResponse.Ref.from(product.getCategory()), product.getStatus(), product.isCodAllowed(),
                product.getImageKeys(), urls(product.getImageKeys()), options, variants, product.getRatingAvg(),
                product.getRatingCount(), product.getCreatedAt(), product.getUpdatedAt(), product.getVersion());
    }

    private VariantResponse variant(Variant variant, Snapshot data) {
        Price price = data.prices().get(variant.getId());
        Inventory inventory = data.stock().get(variant.getId());
        return new VariantResponse(variant.getPublicId(), variant.getSku(), variant.getAttributes(), variant.label(),
                price.listPrice(), price.unitPrice(), AppliedDiscount.from(price), variant.getStatus(),
                variant.getImageKeys(), InventoryResponse.of(variant.getPublicId(), variant.getSku(), inventory));
    }

    private Summary summary(Product product, Snapshot data) {
        List<Variant> variants = data.variantsOf(product.getId());
        int available = 0;
        int reserved = 0;
        for (Variant v : variants) {
            Inventory inventory = data.stock().get(v.getId());
            if (inventory != null) {
                available += inventory.getQuantityAvailable();
                reserved += inventory.getReservedQuantity();
            }
        }
        BigDecimal minPrice = variants.stream().map(Variant::getListPrice).min(BigDecimal::compareTo).orElse(null);
        int active = (int) variants.stream().filter(Variant::isActive).count();
        return new Summary(product.getPublicId(), product.getName(), CategoryResponse.Ref.from(product.getCategory()),
                product.getStatus(), variants.size(), active, minPrice, available, reserved, product.getRatingAvg(),
                product.getRatingCount(), product.getUpdatedAt());
    }

    public List<String> urls(List<String> keys) {
        return keys.stream().map(imageStorage::urlFor).toList();
    }
}
