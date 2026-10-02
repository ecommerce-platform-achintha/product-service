package com.achintha.productservice.inventory;

import com.achintha.productservice.common.Validation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Bodies of the internal reservation API (order-service only). Variants by UUID or by publicId. */
public final class ReservationDtos {

    private ReservationDtos() {
    }

    /** One line: exactly one of {@code variantId} / {@code variantPublicId}. */
    public record LineRequest(
            UUID variantId,
            @Pattern(regexp = Validation.PUBLIC_ID_REGEX) String variantPublicId,
            @NotNull @Min(0) @Max(10_000) Integer quantity) {
    }

    /**
     * Holds stock for an order, all lines or none.
     *
     * @param orderRef  order-service's reference (its order publicId); the idempotency key
     * @param expiresAt when order-service will release the hold at the latest (the merchant deadline)
     */
    public record ReserveRequest(
            @NotBlank @Pattern(regexp = Validation.ORDER_REF_REGEX) String orderRef,
            @NotNull Instant expiresAt,
            @NotEmpty @Size(max = 100) List<@Valid @NotNull LineRequest> lines) {
    }

    /**
     * Quote step: the lines to keep with their new quantities (only reductions; a line left out or set to 0 is
     * released). Optionally moves the expiry (the quote restarts the timer).
     */
    public record AdjustRequest(
            @NotNull @Size(max = 100) List<@Valid @NotNull LineRequest> lines,
            Instant expiresAt) {
    }

    /**
     * @param status {@code HELD} while any line is held, else {@code COMMITTED} if any line was committed, else
     *               {@code RELEASED}
     */
    public record ReservationResponse(String orderRef, ReservationStatus status, Instant expiresAt,
                                      List<LineResponse> lines) {
    }

    public record LineResponse(UUID variantId, String variantPublicId, int quantity, int requestedQuantity,
                               ReservationStatus status, ReleaseReason releaseReason) {
    }
}
