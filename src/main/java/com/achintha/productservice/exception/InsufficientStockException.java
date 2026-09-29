package com.achintha.productservice.exception;

import java.util.UUID;

/** Thrown when an inventory adjustment would take the available quantity below zero. */
public class InsufficientStockException extends RuntimeException {

    public InsufficientStockException(UUID productId, int available, int delta) {
        super("Insufficient stock for product " + productId + ": available " + available + ", requested change "
                + delta);
    }
}
