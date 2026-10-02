package com.achintha.productservice.common;

import java.text.Normalizer;
import java.util.Locale;

/** URL slugs derived from display names: lower-case ASCII letters and digits separated by single dashes. */
public final class Slugs {

    private static final int MAX_BASE_LENGTH = 100;

    private Slugs() {
    }

    public static String from(String name) {
        String ascii = Normalizer.normalize(name, Normalizer.Form.NFKD).replaceAll("\\p{M}", "");
        String slug = ascii.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
        if (slug.length() > MAX_BASE_LENGTH) {
            slug = slug.substring(0, MAX_BASE_LENGTH).replaceAll("-+$", "");
        }
        return slug.isEmpty() ? "category" : slug;
    }
}
