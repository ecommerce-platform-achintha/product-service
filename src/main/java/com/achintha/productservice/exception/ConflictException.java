package com.achintha.productservice.exception;

/** A request that clashes with existing data, e.g. a duplicate SKU or category name. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
