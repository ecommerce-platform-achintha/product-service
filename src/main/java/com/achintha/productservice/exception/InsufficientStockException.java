package com.achintha.productservice.exception;

import org.springframework.http.HttpStatus;

/**
 * 409 {@code INSUFFICIENT_STOCK}: a reservation or a stock decrease would take a variant's available quantity below
 * zero. Nothing was changed.
 */
public class InsufficientStockException extends ApiException {

    public InsufficientStockException(String variantPublicId, int available, int requested) {
        super(HttpStatus.CONFLICT, ErrorCode.INSUFFICIENT_STOCK, "Insufficient stock for variant " + variantPublicId
                + ": " + available + " available, " + requested + " requested");
    }
}
