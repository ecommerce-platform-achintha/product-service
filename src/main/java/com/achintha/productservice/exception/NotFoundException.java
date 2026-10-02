package com.achintha.productservice.exception;

import org.springframework.http.HttpStatus;

/** 404. Also used for ids that exist but belong to someone else (BOLA rule: never reveal foreign ids with 403). */
public class NotFoundException extends ApiException {

    public NotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, ErrorCode.NOT_FOUND, message);
    }
}
