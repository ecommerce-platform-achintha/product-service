package com.achintha.productservice.inventory;

/** Why a reservation line was released. */
public enum ReleaseReason {
    /** order-service released the order (cancelled, rejected, expired, ...). */
    RELEASED,
    /** A quote removed the line (adjusted to 0). */
    ADJUSTED,
    /** The safety-net scheduler released a hold long past its expiry. */
    EXPIRED
}
