package com.achintha.productservice.security;

import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * The authenticated caller, derived only from the validated JWT (never from request bodies or headers).
 *
 * @param storeId merchant/assistant store from the token; used to scope every merchant-side query (BOLA)
 * @param status  the {@code status} claim as issued (a merchant in {@code BAN_GRACE} still sees {@code ACTIVE})
 */
public record Actor(UUID id, String publicId, Role role, UUID storeId, String status) {

    /** Actor id used for event-driven and system actions in audit rows. */
    public static final String SYSTEM = "SYSTEM";

    public static Actor from(Jwt jwt) {
        String storeId = jwt.getClaimAsString(TokenClaims.STORE_ID);
        Role role = Role.valueOf(jwt.getClaimAsString(TokenClaims.ROLE));
        UUID id = role == Role.ROLE_SERVICE ? null : UUID.fromString(jwt.getSubject());
        String publicId = role == Role.ROLE_SERVICE ? jwt.getSubject() : jwt.getClaimAsString(TokenClaims.PUBLIC_ID);
        return new Actor(id, publicId, role, storeId == null ? null : UUID.fromString(storeId),
                jwt.getClaimAsString(TokenClaims.STATUS));
    }

    public static Actor system() {
        return new Actor(null, SYSTEM, Role.ROLE_SERVICE, null, null);
    }

    public boolean isOwner() {
        return role == Role.ROLE_MERCHANT;
    }
}
