package com.achintha.productservice.search;

import com.achintha.productservice.exception.ApiException;
import com.achintha.productservice.exception.ErrorCode;
import java.util.Locale;

/** Whitelisted public sort orders. {@code relevance} without {@code q} means newest first. */
public enum SearchSort {
    RELEVANCE,
    PRICE_ASC,
    PRICE_DESC,
    NEWEST,
    RATING;

    public static final String ALLOWED = "relevance, price (,asc|,desc), newest, rating";

    /** Parses {@code relevance}, {@code price}, {@code price,asc}, {@code price,desc}, {@code newest}, {@code rating}. */
    public static SearchSort parse(String sort) {
        if (sort == null || sort.isBlank()) {
            return RELEVANCE;
        }
        String[] parts = sort.trim().toLowerCase(Locale.ROOT).split(",");
        String direction = parts.length > 1 ? parts[1].trim() : "";
        SearchSort parsed = switch (parts[0].trim()) {
            case "relevance" -> direction.isEmpty() ? RELEVANCE : null;
            case "price" -> switch (direction) {
                case "", "asc" -> PRICE_ASC;
                case "desc" -> PRICE_DESC;
                default -> null;
            };
            case "newest" -> direction.isEmpty() ? NEWEST : null;
            case "rating" -> direction.isEmpty() ? RATING : null;
            default -> null;
        };
        if (parsed == null || parts.length > 2) {
            throw ApiException.badRequest(ErrorCode.INVALID_SORT, "sort must be one of: " + ALLOWED);
        }
        return parsed;
    }
}
