package com.achintha.productservice.product;

import com.achintha.productservice.common.StringListConverter;
import com.achintha.productservice.common.StringMapConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What is actually sold ({@code VAR-} public id): the merchant's SKU (unique per store), the option values, the list
 * price and a status. Stock is in {@code Inventory}, keyed by the variant id.
 */
@Entity
@Table(name = "variants")
@Getter
@Setter
@NoArgsConstructor
public class Variant {

    @Id
    private UUID id;

    @Column(name = "public_id", nullable = false, unique = true, length = 20)
    private String publicId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "store_id", nullable = false)
    private UUID storeId;

    @Column(nullable = false, length = 64)
    private String sku;

    @Convert(converter = StringMapConverter.class)
    @Column(nullable = false, columnDefinition = "text")
    private Map<String, String> attributes = new LinkedHashMap<>();

    @Column(name = "list_price", nullable = false, precision = 12, scale = 2)
    private BigDecimal listPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private VariantStatus status;

    @Convert(converter = StringListConverter.class)
    @Column(name = "image_keys", nullable = false, columnDefinition = "text")
    private List<String> imageKeys = new ArrayList<>();

    @Column(nullable = false)
    private int position;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    public boolean isActive() {
        return status == VariantStatus.ACTIVE;
    }

    /** Display label from the option values, e.g. "Size: M / Colour: Red"; "Default" for a simple product. */
    public String label() {
        if (attributes == null || attributes.isEmpty()) {
            return "Default";
        }
        return attributes.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue())
                .collect(Collectors.joining(" / "));
    }
}
