package com.achintha.productservice.inventory;

/** {@code HELD} -> {@code COMMITTED} (stock taken) or {@code RELEASED} (stock returned). */
public enum ReservationStatus {
    HELD,
    COMMITTED,
    RELEASED
}
