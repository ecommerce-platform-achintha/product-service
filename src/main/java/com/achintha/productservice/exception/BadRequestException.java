package com.achintha.productservice.exception;

/** Invalid input that bean validation can't express, e.g. minPrice > maxPrice or an unknown sort field. */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
