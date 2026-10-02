package com.achintha.productservice.exception;

import org.springframework.http.HttpStatus;

/** 409: a request that clashes with existing data or state, e.g. a duplicate SKU or category slug. */
public class ConflictException extends ApiException {

    public ConflictException(ErrorCode code, String message) {
        super(HttpStatus.CONFLICT, code, message);
    }
}
