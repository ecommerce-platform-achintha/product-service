package com.achintha.productservice.product;

import com.achintha.productservice.audit.AuditService;
import com.achintha.productservice.category.Category;
import com.achintha.productservice.category.CategoryService;
import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.PublicIdGenerator;
import com.achintha.productservice.common.TextSanitizer;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ConflictException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.inventory.Inventory;
import com.achintha.productservice.inventory.InventoryRepository;
import com.achintha.productservice.outbox.ProductEventPublisher;
import com.achintha.productservice.product.ProductRequest.OptionRequest;
import com.achintha.productservice.product.ProductRequest.VariantRequest;
import com.achintha.productservice.security.Actor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The merchant side of the catalog ({@code PRODUCT_EDIT}): the product, its options and its variants are one
 * aggregate, created and replaced together. Every query is scoped by the caller's store.
 *
 * <p>Rules: every product has at least one variant (a simple product gets a default one); variant attributes must
 * use exactly the product's options and each combination appears once; SKUs are unique within the store
 * (case-insensitive, also enforced by a unique index); variants are never deleted, only made {@code INACTIVE}.
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private static final UUID NO_ID = new UUID(0L, 0L);

    private final ProductRepository productRepository;
    private final ProductOptionRepository optionRepository;
    private final VariantRepository variantRepository;
    private final InventoryRepository inventoryRepository;
    private final CategoryService categoryService;
    private final ProductViews views;
    private final ProductEventPublisher events;
    private final PublicIdGenerator publicIds;
    private final AuditService auditService;
    private final Clock clock;

    @Value("${app.catalog.max-variants-per-product:100}")
    private int maxVariants;

    /** A validated, normalised option. */
    record OptionSpec(String name, List<String> values) {
    }

    /** A validated, normalised variant line of the request. */
    record VariantSpec(String publicId, String sku, Map<String, String> attributes, BigDecimal listPrice,
                       VariantStatus status, List<String> imageKeys, int initialStock) {
    }

    // ------------------------------------------------------------------------------------------------- reads

    @Transactional(readOnly = true)
    public PageResponse<ProductResponse.Summary> list(Actor actor, ProductStatus status, String q,
                                                      Pageable pageable) {
        UUID storeId = MerchantAccess.storeIdOf(actor);
        Page<Product> page = productRepository.findAll(ProductSpecifications.forStore(storeId, status, q), pageable);
        return PageResponse.of(page, views.summaries(page.getContent()));
    }

    @Transactional(readOnly = true)
    public ProductResponse get(Actor actor, String publicId) {
        return views.detail(require(actor, publicId));
    }

    // ------------------------------------------------------------------------------------------------ writes

    @Transactional
    public ProductResponse create(Actor actor, ProductRequest request) {
        MerchantAccess.requireCanEdit(actor);
        UUID storeId = MerchantAccess.storeIdOf(actor);
        Category category = categoryService.requireUsable(request.categorySlug());
        List<OptionSpec> options = normaliseOptions(request.options());
        List<VariantSpec> specs = normaliseVariants(request, options);
        for (VariantSpec spec : specs) {
            if (spec.publicId() != null) {
                throw invalidVariants("A new product cannot reference existing variants (publicId " + spec.publicId()
                        + ")");
            }
        }
        checkSkusFree(storeId, specs, List.of());

        Instant now = clock.instant();
        Product product = new Product();
        product.setId(UUID.randomUUID());
        product.setPublicId(publicIds.generateUnique(PublicIdGenerator.ITEM_PREFIX, productRepository::existsByPublicId));
        product.setStoreId(storeId);
        product.setStatus(ProductStatus.DRAFT);
        product.setCreatedAt(now);
        applyFields(product, request, category, now);
        productRepository.save(product);
        replaceOptions(product.getId(), options);

        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            variants.add(newVariant(product, specs.get(i), i, now));
        }
        flushSkus(storeId, specs);

        events.productChanged(product, variants, "CREATED", null);
        auditService.record(actor, "PRODUCT_CREATED", AuditService.TARGET_PRODUCT, product.getPublicId(), null,
                snapshot(product, variants), null);
        return views.detail(product);
    }

    @Transactional
    public ProductResponse update(Actor actor, String publicId, ProductRequest request) {
        MerchantAccess.requireCanEdit(actor);
        UUID storeId = MerchantAccess.storeIdOf(actor);
        Product product = require(actor, publicId);
        if (product.getStatus() == ProductStatus.ARCHIVED) {
            throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "An archived product cannot be edited; move it back to DRAFT first");
        }
        List<Variant> existing = variantRepository.findAllByProductIdOrderByPositionAsc(product.getId());
        Map<String, Object> before = snapshot(product, existing);
        Category category = categoryService.requireUsable(request.categorySlug());
        List<OptionSpec> options = normaliseOptions(request.options());
        List<VariantSpec> specs = normaliseVariants(request, options);

        Map<String, Variant> byPublicId = existing.stream()
                .collect(Collectors.toMap(Variant::getPublicId, Function.identity()));
        List<UUID> listedIds = new ArrayList<>();
        for (VariantSpec spec : specs) {
            if (spec.publicId() != null) {
                Variant variant = byPublicId.get(spec.publicId());
                if (variant == null) {
                    throw invalidVariants("Variant " + spec.publicId() + " does not belong to this product");
                }
                listedIds.add(variant.getId());
            }
        }
        // A default (simple-product) request keeps the existing default variant instead of creating a second one
        if (specs.size() == 1 && specs.getFirst().publicId() == null && options.isEmpty() && existing.size() == 1
                && existing.getFirst().getAttributes().isEmpty()) {
            VariantSpec only = specs.getFirst();
            specs = List.of(new VariantSpec(existing.getFirst().getPublicId(), only.sku(), only.attributes(),
                    only.listPrice(), only.status(), only.imageKeys(), only.initialStock()));
            listedIds.add(existing.getFirst().getId());
        }
        checkSkusFree(storeId, specs, listedIds);

        Instant now = clock.instant();
        applyFields(product, request, category, now);
        replaceOptions(product.getId(), options);

        Set<UUID> kept = new HashSet<>();
        List<Variant> variants = new ArrayList<>();
        for (int i = 0; i < specs.size(); i++) {
            VariantSpec spec = specs.get(i);
            if (spec.publicId() == null) {
                variants.add(newVariant(product, spec, i, now));
            } else {
                Variant variant = byPublicId.get(spec.publicId());
                applyVariant(variant, spec, i, now);
                kept.add(variant.getId());
                variants.add(variant);
            }
        }
        int position = specs.size();
        for (Variant variant : existing) {
            if (!kept.contains(variant.getId())) {
                // Not listed: retired, never deleted (holds, orders and reviews refer to it)
                variant.setStatus(VariantStatus.INACTIVE);
                variant.setPosition(position++);
                variant.setUpdatedAt(now);
                variants.add(variant);
            }
        }
        if (product.getStatus() == ProductStatus.ACTIVE && variants.stream().noneMatch(Variant::isActive)) {
            throw new BadRequestException(ErrorCode.PRODUCT_NOT_SELLABLE,
                    "An active product needs at least one active variant; move it to DRAFT first");
        }
        product.setUpdatedAt(now);
        flushSkus(storeId, specs);

        events.productChanged(product, variants, "UPDATED", null);
        auditService.record(actor, "PRODUCT_UPDATED", AuditService.TARGET_PRODUCT, product.getPublicId(), before,
                snapshot(product, variants), null);
        return views.detail(product);
    }

    /** DRAFT <-> ACTIVE, DRAFT/ACTIVE -> ARCHIVED, ARCHIVED -> DRAFT. Setting the current status is a no-op. */
    @Transactional
    public ProductResponse changeStatus(Actor actor, String publicId, ProductStatus target) {
        MerchantAccess.requireCanEdit(actor);
        Product product = require(actor, publicId);
        ProductStatus current = product.getStatus();
        if (current == target) {
            return views.detail(product);
        }
        if (!current.canMoveTo(target)) {
            throw new ConflictException(ErrorCode.INVALID_STATUS_TRANSITION,
                    "A product cannot move from " + current + " to " + target);
        }
        List<Variant> variants = variantRepository.findAllByProductIdOrderByPositionAsc(product.getId());
        if (target == ProductStatus.ACTIVE) {
            MerchantAccess.requireApproved(actor);
            if (variants.stream().noneMatch(Variant::isActive)) {
                throw new BadRequestException(ErrorCode.PRODUCT_NOT_SELLABLE,
                        "A product needs at least one active variant to be activated");
            }
        }
        product.setStatus(target);
        product.setUpdatedAt(clock.instant());
        productRepository.saveAndFlush(product);
        events.productChanged(product, variants, "STATUS_CHANGED", current.name());
        auditService.record(actor, "PRODUCT_STATUS_CHANGED", AuditService.TARGET_PRODUCT, product.getPublicId(),
                Map.of("status", current), Map.of("status", target), null);
        return views.detail(product);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private Product require(Actor actor, String publicId) {
        return productRepository.findByPublicIdAndStoreId(publicId, MerchantAccess.storeIdOf(actor))
                .orElseThrow(() -> new NotFoundException("Product not found"));
    }

    private void applyFields(Product product, ProductRequest request, Category category, Instant now) {
        String name = TextSanitizer.cleanLine(request.name());
        if (name == null) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "name must not be blank");
        }
        product.setName(name);
        product.setDescription(TextSanitizer.clean(request.description()));
        product.setCategory(category);
        product.setCodAllowed(request.codAllowed() == null || request.codAllowed());
        product.setImageKeys(distinct(request.imageKeys()));
        product.setUpdatedAt(now);
    }

    private Variant newVariant(Product product, VariantSpec spec, int position, Instant now) {
        Variant variant = new Variant();
        variant.setId(UUID.randomUUID());
        variant.setPublicId(publicIds.generateUnique(PublicIdGenerator.VARIANT_PREFIX,
                variantRepository::existsByPublicId));
        variant.setProductId(product.getId());
        variant.setStoreId(product.getStoreId());
        variant.setCreatedAt(now);
        applyVariant(variant, spec, position, now);
        variantRepository.save(variant);
        inventoryRepository.save(new Inventory(variant.getId(), spec.initialStock(), now));
        return variant;
    }

    private static void applyVariant(Variant variant, VariantSpec spec, int position, Instant now) {
        variant.setSku(spec.sku());
        variant.setAttributes(spec.attributes());
        variant.setListPrice(spec.listPrice());
        variant.setStatus(spec.status());
        variant.setImageKeys(spec.imageKeys());
        variant.setPosition(position);
        variant.setUpdatedAt(now);
    }

    private void replaceOptions(UUID productId, List<OptionSpec> options) {
        optionRepository.deleteAll(optionRepository.findAllByProductIdOrderByPositionAsc(productId));
        for (int i = 0; i < options.size(); i++) {
            ProductOption option = new ProductOption();
            option.setProductId(productId);
            option.setName(options.get(i).name());
            option.setValues(options.get(i).values());
            option.setPosition(i);
            optionRepository.save(option);
        }
    }

    /** Option names unique (case-insensitive), values unique per option, all sanitized. */
    static List<OptionSpec> normaliseOptions(List<OptionRequest> requested) {
        if (requested == null || requested.isEmpty()) {
            return List.of();
        }
        List<OptionSpec> options = new ArrayList<>();
        Set<String> names = new HashSet<>();
        for (OptionRequest option : requested) {
            String name = TextSanitizer.cleanLine(option.name());
            if (name == null || !names.add(name.toLowerCase(Locale.ROOT))) {
                throw invalidVariants("Option names must be non-blank and unique");
            }
            List<String> values = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String raw : option.values()) {
                String value = TextSanitizer.cleanLine(raw);
                if (value == null || !seen.add(value.toLowerCase(Locale.ROOT))) {
                    throw invalidVariants("Values of option '" + name + "' must be non-blank and unique");
                }
                values.add(value);
            }
            options.add(new OptionSpec(name, List.copyOf(values)));
        }
        return options;
    }

    /**
     * Turns the request into variant specs: the explicit list, or one default variant for a simple product.
     * Attributes are matched to the options case-insensitively and stored with the options' spelling and order.
     */
    List<VariantSpec> normaliseVariants(ProductRequest request, List<OptionSpec> options) {
        boolean listed = request.variants() != null && !request.variants().isEmpty();
        boolean shortcut = request.sku() != null || request.listPrice() != null || request.initialStock() != null;
        if (!listed) {
            if (!options.isEmpty()) {
                throw invalidVariants("variants are required when options are given");
            }
            if (request.sku() == null || request.listPrice() == null) {
                throw invalidVariants("Give either variants, or sku and listPrice for a simple product");
            }
            return List.of(new VariantSpec(null, request.sku().trim(), Map.of(), request.listPrice(),
                    VariantStatus.ACTIVE, List.of(), request.initialStock() == null ? 0 : request.initialStock()));
        }
        if (shortcut) {
            throw invalidVariants("Use either variants or the simple-product fields (sku, listPrice, initialStock), "
                    + "not both");
        }
        if (request.variants().size() > maxVariants) {
            throw invalidVariants("At most " + maxVariants + " variants per product");
        }
        if (options.isEmpty() && request.variants().size() > 1) {
            throw invalidVariants("A product without options has exactly one (default) variant");
        }
        List<VariantSpec> specs = new ArrayList<>();
        Set<String> skus = new HashSet<>();
        Set<String> publicIdsSeen = new HashSet<>();
        Set<Map<String, String>> combinations = new HashSet<>();
        for (VariantRequest v : request.variants()) {
            String sku = v.sku().trim();
            if (!skus.add(sku.toLowerCase(Locale.ROOT))) {
                throw invalidVariants("SKU '" + sku + "' appears twice");
            }
            if (v.publicId() != null && !publicIdsSeen.add(v.publicId())) {
                throw invalidVariants("Variant " + v.publicId() + " appears twice");
            }
            Map<String, String> attributes = matchAttributes(v.attributes(), options, sku);
            if (!combinations.add(attributes)) {
                throw invalidVariants("Two variants have the same option values " + attributes);
            }
            specs.add(new VariantSpec(v.publicId(), sku, attributes, v.listPrice(),
                    v.status() == null ? VariantStatus.ACTIVE : v.status(), distinct(v.imageKeys()),
                    v.initialStock() == null ? 0 : v.initialStock()));
        }
        return specs;
    }

    private static Map<String, String> matchAttributes(Map<String, String> raw, List<OptionSpec> options,
                                                       String sku) {
        Map<String, String> given = new HashMap<>();
        if (raw != null) {
            for (Map.Entry<String, String> e : raw.entrySet()) {
                String key = TextSanitizer.cleanLine(e.getKey());
                String value = TextSanitizer.cleanLine(e.getValue());
                if (key == null || value == null) {
                    throw invalidVariants("Variant " + sku + " has a blank attribute");
                }
                given.put(key.toLowerCase(Locale.ROOT), value);
            }
        }
        if (given.size() != options.size()) {
            throw invalidVariants("Variant " + sku + " must have exactly one value for each option "
                    + options.stream().map(OptionSpec::name).toList());
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (OptionSpec option : options) {
            String value = given.get(option.name().toLowerCase(Locale.ROOT));
            if (value == null) {
                throw invalidVariants("Variant " + sku + " has no value for option '" + option.name() + "'");
            }
            String canonical = option.values().stream().filter(v -> v.equalsIgnoreCase(value)).findFirst()
                    .orElseThrow(() -> invalidVariants("Variant " + sku + ": '" + value + "' is not a value of option '"
                            + option.name() + "'"));
            attributes.put(option.name(), canonical);
        }
        return attributes;
    }

    /** 409 when another variant of the store (not one being updated here) already uses one of the SKUs. */
    private void checkSkusFree(UUID storeId, List<VariantSpec> specs, List<UUID> updatedVariantIds) {
        List<String> lower = specs.stream().map(s -> s.sku().toLowerCase(Locale.ROOT)).toList();
        List<UUID> excluded = updatedVariantIds.isEmpty() ? List.of(NO_ID) : updatedVariantIds;
        List<String> clashes = variantRepository.clashingSkus(storeId, lower, excluded);
        if (!clashes.isEmpty()) {
            throw skuTaken(clashes.getFirst());
        }
    }

    /** Flushes so a concurrent request that took one of the SKUs fails here as 409, not as a 500 at commit. */
    private void flushSkus(UUID storeId, List<VariantSpec> specs) {
        try {
            variantRepository.flush();
        } catch (DataIntegrityViolationException e) {
            throw skuTaken(specs.isEmpty() ? "?" : specs.getFirst().sku());
        }
    }

    private static List<String> distinct(List<String> keys) {
        return keys == null ? List.of() : keys.stream().distinct().toList();
    }

    private static Map<String, Object> snapshot(Product product, List<Variant> variants) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("name", product.getName());
        map.put("category", product.getCategory().getSlug());
        map.put("status", product.getStatus());
        map.put("codAllowed", product.isCodAllowed());
        map.put("variants", variants.stream().map(v -> Map.of("publicId", v.getPublicId(), "sku", v.getSku(),
                "listPrice", v.getListPrice(), "status", v.getStatus())).toList());
        return map;
    }

    private static BadRequestException invalidVariants(String message) {
        return new BadRequestException(ErrorCode.INVALID_VARIANTS, message);
    }

    private static ConflictException skuTaken(String sku) {
        return new ConflictException(ErrorCode.SKU_TAKEN, "SKU '" + sku + "' is already used in this store");
    }
}
