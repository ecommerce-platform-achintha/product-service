package com.achintha.productservice.discount;

import com.achintha.productservice.audit.AuditService;
import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.common.PublicIdGenerator;
import com.achintha.productservice.common.TextSanitizer;
import com.achintha.productservice.discount.DiscountDtos.DiscountRequest;
import com.achintha.productservice.discount.DiscountDtos.DiscountResponse;
import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.product.MerchantAccess;
import com.achintha.productservice.product.Product;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.security.Actor;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Merchant discounts ({@code DISCOUNT_MANAGE}), always scoped by the caller's store. Validity windows are not
 * enforced here but at read time; this only rejects discounts that could never be valid.
 */
@Service
@RequiredArgsConstructor
public class DiscountService {

    private static final BigDecimal MIN_PERCENT = BigDecimal.ONE;
    private static final BigDecimal MAX_PERCENT = BigDecimal.valueOf(90);

    private final DiscountRepository discountRepository;
    private final ProductRepository productRepository;
    private final VariantRepository variantRepository;
    private final PublicIdGenerator publicIds;
    private final AuditService auditService;
    private final Clock clock;

    @Transactional(readOnly = true)
    public PageResponse<DiscountResponse> list(Actor actor, String productPublicId, Pageable pageable) {
        UUID storeId = MerchantAccess.storeIdOf(actor);
        Page<Discount> page;
        if (productPublicId != null) {
            Product product = productRepository.findByPublicIdAndStoreId(productPublicId, storeId)
                    .orElseThrow(() -> new NotFoundException("Product not found"));
            page = discountRepository.findAllByStoreIdAndProductId(storeId, product.getId(), pageable);
        } else {
            page = discountRepository.findAllByStoreId(storeId, pageable);
        }
        Map<UUID, String> products = productPublicIds(page.getContent());
        Map<UUID, String> variants = variantPublicIds(page.getContent());
        Instant now = clock.instant();
        return PageResponse.from(page, d -> toResponse(d, products, variants, now));
    }

    @Transactional(readOnly = true)
    public DiscountResponse get(Actor actor, String publicId) {
        return toResponse(require(actor, publicId));
    }

    @Transactional
    public DiscountResponse create(Actor actor, DiscountRequest request) {
        MerchantAccess.requireCanEdit(actor);
        Instant now = clock.instant();
        Discount discount = new Discount();
        discount.setId(UUID.randomUUID());
        discount.setPublicId(publicIds.generateUnique(PublicIdGenerator.DISCOUNT_PREFIX,
                discountRepository::existsByPublicId));
        discount.setStoreId(MerchantAccess.storeIdOf(actor));
        discount.setCreatedAt(now);
        apply(actor, discount, request, now);
        discount = discountRepository.saveAndFlush(discount);
        auditService.record(actor, "DISCOUNT_CREATED", AuditService.TARGET_DISCOUNT, discount.getPublicId(), null,
                snapshot(discount), null);
        return toResponse(discount);
    }

    @Transactional
    public DiscountResponse update(Actor actor, String publicId, DiscountRequest request) {
        MerchantAccess.requireCanEdit(actor);
        Discount discount = require(actor, publicId);
        Map<String, Object> before = snapshot(discount);
        apply(actor, discount, request, clock.instant());
        discount = discountRepository.saveAndFlush(discount);
        auditService.record(actor, "DISCOUNT_UPDATED", AuditService.TARGET_DISCOUNT, discount.getPublicId(), before,
                snapshot(discount), null);
        return toResponse(discount);
    }

    @Transactional
    public void delete(Actor actor, String publicId) {
        MerchantAccess.requireCanEdit(actor);
        Discount discount = require(actor, publicId);
        Map<String, Object> before = snapshot(discount);
        discountRepository.delete(discount);
        discountRepository.flush();
        auditService.record(actor, "DISCOUNT_DELETED", AuditService.TARGET_DISCOUNT, publicId, before, null, null);
    }

    private void apply(Actor actor, Discount discount, DiscountRequest request, Instant now) {
        UUID storeId = MerchantAccess.storeIdOf(actor);
        Product product;
        Variant variant = null;
        if (request.scope() == DiscountScope.VARIANT) {
            if (request.variantPublicId() == null) {
                throw invalid("variantPublicId is required for scope VARIANT");
            }
            variant = variantRepository.findByPublicIdAndStoreId(request.variantPublicId(), storeId)
                    .orElseThrow(() -> new NotFoundException("Variant not found"));
            product = productRepository.findById(variant.getProductId()).orElseThrow();
            if (request.productPublicId() != null && !request.productPublicId().equals(product.getPublicId())) {
                throw invalid("variantPublicId does not belong to productPublicId");
            }
        } else {
            if (request.productPublicId() == null || request.variantPublicId() != null) {
                throw invalid("scope PRODUCT needs productPublicId and no variantPublicId");
            }
            product = productRepository.findByPublicIdAndStoreId(request.productPublicId(), storeId)
                    .orElseThrow(() -> new NotFoundException("Product not found"));
        }
        validateValue(request, product, variant);
        if (request.startsAt() != null && request.endsAt() != null && !request.endsAt().isAfter(request.startsAt())) {
            throw invalid("endsAt must be after startsAt");
        }
        if (request.endsAt() != null && !request.endsAt().isAfter(now)) {
            throw invalid("endsAt must be in the future");
        }
        discount.setName(TextSanitizer.cleanLine(request.name()));
        discount.setScope(request.scope());
        discount.setProductId(product.getId());
        discount.setVariantId(variant == null ? null : variant.getId());
        discount.setType(request.type());
        discount.setValue(request.value());
        discount.setStartsAt(request.startsAt());
        discount.setEndsAt(request.endsAt());
        discount.setActive(request.active() == null || request.active());
        discount.setUpdatedAt(now);
    }

    /** Percent 1-90; a fixed amount must leave every targeted list price above zero. */
    private void validateValue(DiscountRequest request, Product product, Variant variant) {
        if (request.type() == DiscountType.PERCENT) {
            if (request.value().compareTo(MIN_PERCENT) < 0 || request.value().compareTo(MAX_PERCENT) > 0) {
                throw invalid("A percentage discount must be between 1 and 90");
            }
            return;
        }
        List<Variant> targets = variant != null ? List.of(variant)
                : variantRepository.findAllByProductIdOrderByPositionAsc(product.getId());
        BigDecimal lowest = targets.stream().map(Variant::getListPrice).min(BigDecimal::compareTo)
                .orElse(BigDecimal.ZERO);
        if (request.value().compareTo(lowest) >= 0) {
            throw invalid("A fixed discount must leave a price above zero (lowest targeted list price: " + lowest
                    + ")");
        }
    }

    private Discount require(Actor actor, String publicId) {
        return discountRepository.findByPublicIdAndStoreId(publicId, MerchantAccess.storeIdOf(actor))
                .orElseThrow(() -> new NotFoundException("Discount not found"));
    }

    private DiscountResponse toResponse(Discount discount) {
        List<Discount> one = List.of(discount);
        return toResponse(discount, productPublicIds(one), variantPublicIds(one), clock.instant());
    }

    private static DiscountResponse toResponse(Discount d, Map<UUID, String> products, Map<UUID, String> variants,
                                               Instant now) {
        return new DiscountResponse(d.getPublicId(), d.getName(), d.getScope(), products.get(d.getProductId()),
                d.getVariantId() == null ? null : variants.get(d.getVariantId()), d.getType(), d.getValue(),
                d.getStartsAt(), d.getEndsAt(), d.isActive(), PriceCalculator.isLive(d, now), d.getCreatedAt(),
                d.getUpdatedAt());
    }

    private Map<UUID, String> productPublicIds(Collection<Discount> discounts) {
        List<UUID> ids = discounts.stream().map(Discount::getProductId).distinct().toList();
        return ids.isEmpty() ? Map.of() : productRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Product::getPublicId));
    }

    private Map<UUID, String> variantPublicIds(Collection<Discount> discounts) {
        List<UUID> ids = discounts.stream().map(Discount::getVariantId).filter(java.util.Objects::nonNull)
                .distinct().toList();
        return ids.isEmpty() ? Map.of() : variantRepository.findAllByIdIn(ids).stream()
                .collect(Collectors.toMap(Variant::getId, Variant::getPublicId));
    }

    private static Map<String, Object> snapshot(Discount d) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("scope", d.getScope());
        map.put("productId", d.getProductId());
        map.put("variantId", d.getVariantId());
        map.put("type", d.getType());
        map.put("value", d.getValue());
        map.put("startsAt", d.getStartsAt());
        map.put("endsAt", d.getEndsAt());
        map.put("active", d.isActive());
        return map;
    }

    private static BadRequestException invalid(String message) {
        return new BadRequestException(ErrorCode.INVALID_DISCOUNT, message);
    }
}
