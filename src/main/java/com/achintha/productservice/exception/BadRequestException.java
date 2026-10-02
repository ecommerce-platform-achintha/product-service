package com.achintha.productservice.exception;

import org.springframework.http.HttpStatus;

/** 400 for input that bean validation can't express, e.g. minPrice > maxPrice or variants that miss an option. */
public class BadRequestException extends ApiException {

    public BadRequestException(ErrorCode code, String message) {
        super(HttpStatus.BAD_REQUEST, code, message);
    }

    public BadRequestException(String message) {
        this(ErrorCode.BAD_REQUEST, message);
    }
}
