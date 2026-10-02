package com.achintha.productservice.inventory;

import com.achintha.productservice.exception.BadRequestException;
import com.achintha.productservice.exception.ConflictException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.exception.InsufficientStockException;
import com.achintha.productservice.exception.NotFoundException;
import com.achintha.productservice.inventory.ReservationDtos.AdjustRequest;
import com.achintha.productservice.inventory.ReservationDtos.LineRequest;
import com.achintha.productservice.inventory.ReservationDtos.LineResponse;
import com.achintha.productservice.inventory.ReservationDtos.ReservationResponse;
import com.achintha.productservice.inventory.ReservationDtos.ReserveRequest;
import com.achintha.productservice.product.Product;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.product.ProductStatus;
import com.achintha.productservice.product.Variant;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.store.StoreVisibilityService;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stock holds for order-service (section 5). Every operation is idempotent per {@code orderRef} and serialised per
 * order by a transaction-scoped PostgreSQL advisory lock, so two concurrent calls for the same order (a retry and
 * the original) cannot both apply.
 *
 * <ul>
 *   <li><b>reserve</b>: all lines or none. Each line is one conditional update ({@code available >= qty}), so
 *       concurrent orders never oversell; variants are locked in id order so two orders cannot deadlock. A replay
 *       with the same lines returns the existing hold; different lines answer 409 {@code RESERVATION_CONFLICT}.</li>
 *   <li><b>adjust</b> (quote): only reduces or removes held lines, may move the expiry.</li>
 *   <li><b>commit</b>: held units leave the stock for good. <b>release</b>: held units return to available.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ReservationService {

    private final StockReservationRepository reservationRepository;
    private final InventoryRepository inventoryRepository;
    private final VariantRepository variantRepository;
    private final ProductRepository productRepository;
    private final StoreVisibilityService storeVisibility;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    /** @param created {@code false} when an identical earlier request was replayed */
    public record ReserveResult(ReservationResponse reservation, boolean created) {
    }

    @Transactional
    public ReserveResult reserve(ReserveRequest request) {
        lockOrder(request.orderRef());
        Instant now = clock.instant();
        Map<UUID, Integer> wanted = resolveLines(request.lines(), false);
        List<StockReservation> existing = reservationRepository.findAllByOrderRefOrderByIdAsc(request.orderRef());
        if (!existing.isEmpty()) {
            Map<UUID, Integer> earlier = existing.stream().collect(
                    Collectors.toMap(StockReservation::getVariantId, StockReservation::getRequestedQuantity));
            if (!earlier.equals(wanted)) {
                throw new ConflictException(ErrorCode.RESERVATION_CONFLICT,
                        "Order " + request.orderRef() + " already holds different lines");
            }
            return new ReserveResult(toResponse(request.orderRef(), existing), false);
        }
        if (!request.expiresAt().isAfter(now)) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "expiresAt must be in the future");
        }
        Map<UUID, Variant> variants = requireSellable(wanted.keySet());

        Timestamp at = Timestamp.from(now);
        List<UUID> lockOrder = wanted.keySet().stream().sorted(Comparator.comparing(UUID::toString)).toList();
        for (UUID variantId : lockOrder) {
            int quantity = wanted.get(variantId);
            if (inventoryRepository.hold(variantId, quantity, at) == 0) {
                // The exception rolls back the lines held so far: all or nothing
                int available = inventoryRepository.findById(variantId).map(Inventory::getQuantityAvailable).orElse(0);
                throw new InsufficientStockException(variants.get(variantId).getPublicId(), available, quantity);
            }
        }
        List<StockReservation> lines = new ArrayList<>();
        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            StockReservation reservation = new StockReservation();
            reservation.setOrderRef(request.orderRef());
            reservation.setVariantId(line.getKey());
            reservation.setQuantity(line.getValue());
            reservation.setRequestedQuantity(line.getValue());
            reservation.setStatus(ReservationStatus.HELD);
            reservation.setExpiresAt(request.expiresAt());
            reservation.setCreatedAt(now);
            reservation.setUpdatedAt(now);
            lines.add(reservation);
        }
        reservationRepository.saveAllAndFlush(lines);
        return new ReserveResult(toResponse(request.orderRef(), lines), true);
    }

    @Transactional
    public ReservationResponse adjust(String orderRef, AdjustRequest request) {
        lockOrder(orderRef);
        List<StockReservation> lines = require(orderRef);
        requireNotCommitted(lines);
        Map<UUID, StockReservation> held = lines.stream().filter(l -> l.getStatus() == ReservationStatus.HELD)
                .collect(Collectors.toMap(StockReservation::getVariantId, Function.identity()));
        if (held.isEmpty()) {
            throw released(orderRef);
        }
        Map<UUID, Integer> wanted = resolveLines(request.lines(), true);
        for (Map.Entry<UUID, Integer> line : wanted.entrySet()) {
            StockReservation current = held.get(line.getKey());
            if (current == null) {
                throw new BadRequestException(ErrorCode.RESERVATION_ADJUST_NOT_ALLOWED,
                        "A quote cannot add lines to the hold");
            }
            if (line.getValue() > current.getQuantity()) {
                throw new BadRequestException(ErrorCode.RESERVATION_ADJUST_NOT_ALLOWED,
                        "A quote can only reduce quantities (held " + current.getQuantity() + ", asked "
                                + line.getValue() + ")");
            }
        }
        Instant now = clock.instant();
        if (request.expiresAt() != null && !request.expiresAt().isAfter(now)) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "expiresAt must be in the future");
        }
        Timestamp at = Timestamp.from(now);
        for (StockReservation line : held.values()) {
            int keep = wanted.getOrDefault(line.getVariantId(), 0);
            int returned = line.getQuantity() - keep;
            if (returned > 0) {
                unhold(line.getVariantId(), returned, at);
                line.setQuantity(keep);
            }
            if (keep == 0) {
                line.setStatus(ReservationStatus.RELEASED);
                line.setReleaseReason(ReleaseReason.ADJUSTED);
            } else if (request.expiresAt() != null) {
                line.setExpiresAt(request.expiresAt());
            }
            line.setUpdatedAt(now);
        }
        reservationRepository.saveAllAndFlush(lines);
        return toResponse(orderRef, lines);
    }

    /** Idempotent: an already committed hold is returned as is. */
    @Transactional
    public ReservationResponse commit(String orderRef) {
        lockOrder(orderRef);
        List<StockReservation> lines = require(orderRef);
        List<StockReservation> held = lines.stream().filter(l -> l.getStatus() == ReservationStatus.HELD).toList();
        if (held.isEmpty()) {
            if (lines.stream().anyMatch(l -> l.getStatus() == ReservationStatus.COMMITTED)) {
                return toResponse(orderRef, lines);
            }
            throw released(orderRef);
        }
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        for (StockReservation line : held) {
            if (inventoryRepository.consume(line.getVariantId(), line.getQuantity(), at) == 0) {
                throw new IllegalStateException("Inventory of variant " + line.getVariantId()
                        + " holds fewer units than reservation " + line.getId());
            }
            line.setStatus(ReservationStatus.COMMITTED);
            line.setUpdatedAt(now);
        }
        reservationRepository.saveAllAndFlush(lines);
        return toResponse(orderRef, lines);
    }

    /** Idempotent: an already released hold is returned as is. A committed one cannot be released (409). */
    @Transactional
    public ReservationResponse release(String orderRef) {
        lockOrder(orderRef);
        List<StockReservation> lines = require(orderRef);
        requireNotCommitted(lines);
        releaseHeld(lines, ReleaseReason.RELEASED, null);
        return toResponse(orderRef, lines);
    }

    @Transactional(readOnly = true)
    public ReservationResponse get(String orderRef) {
        return toResponse(orderRef, require(orderRef));
    }

    /** Safety net: releases this order's lines still held before {@code cutoff}. @return lines released */
    @Transactional
    public int releaseExpired(String orderRef, Instant cutoff) {
        lockOrder(orderRef);
        return releaseHeld(reservationRepository.findAllByOrderRefOrderByIdAsc(orderRef), ReleaseReason.EXPIRED,
                cutoff);
    }

    // ------------------------------------------------------------------------------------------------ helpers

    private int releaseHeld(List<StockReservation> lines, ReleaseReason reason, Instant heldBefore) {
        Instant now = clock.instant();
        Timestamp at = Timestamp.from(now);
        int count = 0;
        for (StockReservation line : lines) {
            if (line.getStatus() != ReservationStatus.HELD
                    || (heldBefore != null && !line.getExpiresAt().isBefore(heldBefore))) {
                continue;
            }
            unhold(line.getVariantId(), line.getQuantity(), at);
            line.setStatus(ReservationStatus.RELEASED);
            line.setReleaseReason(reason);
            line.setUpdatedAt(now);
            count++;
        }
        reservationRepository.saveAllAndFlush(lines);
        return count;
    }

    private void unhold(UUID variantId, int quantity, Timestamp at) {
        if (quantity > 0 && inventoryRepository.unhold(variantId, quantity, at) == 0) {
            throw new IllegalStateException("Inventory of variant " + variantId + " holds fewer than " + quantity
                    + " units");
        }
    }

    /** Serialises all operations on one order (transaction-scoped, released at commit/rollback). */
    private void lockOrder(String orderRef) {
        jdbcTemplate.queryForObject("select 1 from (select pg_advisory_xact_lock(hashtextextended(?, 0))) l",
                Integer.class, "reservation:" + orderRef);
    }

    private List<StockReservation> require(String orderRef) {
        List<StockReservation> lines = reservationRepository.findAllByOrderRefOrderByIdAsc(orderRef);
        if (lines.isEmpty()) {
            throw new NotFoundException("No reservation for order " + orderRef);
        }
        return lines;
    }

    private static void requireNotCommitted(List<StockReservation> lines) {
        if (lines.stream().anyMatch(l -> l.getStatus() == ReservationStatus.COMMITTED)) {
            throw new ConflictException(ErrorCode.RESERVATION_COMMITTED,
                    "The reservation is already committed; stock has left the inventory");
        }
    }

    private static ConflictException released(String orderRef) {
        return new ConflictException(ErrorCode.RESERVATION_RELEASED,
                "The reservation of order " + orderRef + " has been released");
    }

    /**
     * Variant id to quantity; each line names its variant by id or publicId (not both), each variant once.
     * {@code allowZero}: adjust lines may be 0 (remove).
     */
    private Map<UUID, Integer> resolveLines(List<LineRequest> lines, boolean allowZero) {
        List<String> publicIds = new ArrayList<>();
        for (LineRequest line : lines) {
            if ((line.variantId() == null) == (line.variantPublicId() == null)) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                        "Each line needs exactly one of variantId or variantPublicId");
            }
            if (!allowZero && line.quantity() < 1) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "quantity must be at least 1");
            }
            if (line.variantPublicId() != null) {
                publicIds.add(line.variantPublicId());
            }
        }
        Map<String, UUID> byPublicId = publicIds.isEmpty() ? Map.of()
                : variantRepository.findAllByPublicIdIn(publicIds).stream()
                        .collect(Collectors.toMap(Variant::getPublicId, Variant::getId));
        Map<UUID, Integer> resolved = new LinkedHashMap<>();
        for (LineRequest line : lines) {
            UUID id = line.variantId() != null ? line.variantId() : byPublicId.get(line.variantPublicId());
            if (id == null) {
                throw new ConflictException(ErrorCode.VARIANT_NOT_AVAILABLE,
                        "Variant " + line.variantPublicId() + " does not exist");
            }
            if (resolved.put(id, line.quantity()) != null) {
                throw new BadRequestException(ErrorCode.VALIDATION_FAILED, "A variant appears in two lines");
            }
        }
        return resolved;
    }

    /** All variants exist, are active, belong to active products of one visible store. */
    private Map<UUID, Variant> requireSellable(java.util.Set<UUID> variantIds) {
        Map<UUID, Variant> variants = variantRepository.findAllByIdIn(variantIds).stream()
                .collect(Collectors.toMap(Variant::getId, Function.identity()));
        for (UUID id : variantIds) {
            if (!variants.containsKey(id)) {
                throw new ConflictException(ErrorCode.VARIANT_NOT_AVAILABLE, "Variant " + id + " does not exist");
            }
        }
        if (variants.values().stream().map(Variant::getStoreId).distinct().count() > 1) {
            throw new BadRequestException(ErrorCode.VALIDATION_FAILED,
                    "All lines of one reservation must belong to one store");
        }
        Map<UUID, Product> products = productRepository.findAllById(
                        variants.values().stream().map(Variant::getProductId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));
        UUID storeId = variants.values().iterator().next().getStoreId();
        boolean storeVisible = storeVisibility.isPubliclyVisible(storeId);
        for (Variant variant : variants.values()) {
            Product product = products.get(variant.getProductId());
            if (!storeVisible || !variant.isActive() || product.getStatus() != ProductStatus.ACTIVE) {
                throw new ConflictException(ErrorCode.VARIANT_NOT_AVAILABLE,
                        "Variant " + variant.getPublicId() + " is not available for sale");
            }
        }
        return variants;
    }

    private ReservationResponse toResponse(String orderRef, List<StockReservation> lines) {
        Map<UUID, String> publicIds = new HashMap<>();
        variantRepository.findAllByIdIn(lines.stream().map(StockReservation::getVariantId).toList())
                .forEach(v -> publicIds.put(v.getId(), v.getPublicId()));
        List<LineResponse> lineResponses = lines.stream()
                .map(l -> new LineResponse(l.getVariantId(), publicIds.get(l.getVariantId()), l.getQuantity(),
                        l.getRequestedQuantity(), l.getStatus(), l.getReleaseReason()))
                .toList();
        ReservationStatus status = lines.stream().anyMatch(l -> l.getStatus() == ReservationStatus.HELD)
                ? ReservationStatus.HELD
                : lines.stream().anyMatch(l -> l.getStatus() == ReservationStatus.COMMITTED)
                        ? ReservationStatus.COMMITTED : ReservationStatus.RELEASED;
        Instant expiresAt = lines.stream().filter(l -> l.getStatus() == ReservationStatus.HELD)
                .map(StockReservation::getExpiresAt).max(Comparator.naturalOrder())
                .orElse(lines.getFirst().getExpiresAt());
        return new ReservationResponse(orderRef, status, expiresAt, lineResponses);
    }
}
