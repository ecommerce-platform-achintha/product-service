package com.achintha.productservice.exception;

/** Stable, machine-readable error codes returned in {@link ApiError#code()}. Never rename a published code. */
public enum ErrorCode {
    // Generic
    VALIDATION_FAILED,
    MALFORMED_REQUEST,
    BAD_REQUEST,
    INVALID_SORT,
    NOT_FOUND,
    METHOD_NOT_ALLOWED,
    UNSUPPORTED_MEDIA_TYPE,
    CONCURRENT_MODIFICATION,
    INTERNAL_ERROR,
    SERVICE_UNAVAILABLE,

    // Authentication and authorization
    UNAUTHORIZED,
    ACCESS_DENIED,
    TOKEN_REVOKED,
    ACCOUNT_BANNED,
    MERCHANT_NOT_APPROVED,

    // Categories
    CATEGORY_SLUG_TAKEN,
    CATEGORY_NAME_TAKEN,
    CATEGORY_NOT_AVAILABLE,
    CATEGORY_IN_USE,

    // Products and variants
    SKU_TAKEN,
    INVALID_VARIANTS,
    INVALID_STATUS_TRANSITION,
    PRODUCT_NOT_SELLABLE,

    // Stock and reservations
    INSUFFICIENT_STOCK,
    VARIANT_NOT_AVAILABLE,
    RESERVATION_CONFLICT,
    RESERVATION_ADJUST_NOT_ALLOWED,
    RESERVATION_RELEASED,
    RESERVATION_COMMITTED,

    // Discounts
    INVALID_DISCOUNT,

    // Reviews
    REVIEW_NOT_ELIGIBLE,
    REVIEW_ALREADY_EXISTS,
    REPLY_ALREADY_EXISTS
}
