package com.achintha.productservice.common;

/** Shared Bean Validation patterns, so every DTO validates the same fields the same way. */
public final class Validation {

    /** Opaque keys of the mock image storage. */
    public static final String STORAGE_KEY_REGEX = "^[A-Za-z0-9][A-Za-z0-9._/-]{0,199}$";
    /** Category slugs (admin-chosen short codes): lower-case letters, digits and single dashes. */
    public static final String SLUG_REGEX = "^[a-z0-9]+(-[a-z0-9]+)*$";
    public static final String SLUG_MESSAGE = "must be lower-case letters and digits separated by single dashes";
    /** Merchant SKUs: letters, digits and . _ - / (no spaces). */
    public static final String SKU_REGEX = "^[A-Za-z0-9][A-Za-z0-9._/-]{0,63}$";
    public static final String SKU_MESSAGE = "must be 1-64 letters, digits or . _ - / (starting with a letter or digit)";
    /** order-service's order reference for reservations (its order publicId or UUID). */
    public static final String ORDER_REF_REGEX = "^[A-Za-z0-9][A-Za-z0-9-]{0,63}$";
    public static final String PUBLIC_ID_REGEX = "^[A-Z]{3}-\\d{4}-[0-9A-HJKMNP-TV-Z]{6}$";
    public static final int REASON_MAX = 500;
    public static final int TEXT_MAX = 2000;
    public static final int DESCRIPTION_MAX = 5000;
    public static final int MAX_IMAGES = 10;

    private Validation() {
    }
}
