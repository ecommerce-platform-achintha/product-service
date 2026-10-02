package com.achintha.productservice.product;

import com.achintha.productservice.exception.ApiException;
import com.achintha.productservice.exception.ErrorCode;
import com.achintha.productservice.security.Actor;
import com.achintha.productservice.security.UserStatus;
import java.util.UUID;

/**
 * Merchant-side guards. The store always comes from the JWT {@code storeId} (never from the request), so every
 * merchant query is scoped to the caller's store and another store's ids answer 404.
 */
public final class MerchantAccess {

    private MerchantAccess() {
    }

    public static UUID storeIdOf(Actor actor) {
        if (actor.storeId() == null) {
            throw ApiException.forbidden(ErrorCode.ACCESS_DENIED, "The token carries no store");
        }
        return actor.storeId();
    }

    /** A banned merchant keeps read access to its history but cannot change the catalog. */
    public static void requireCanEdit(Actor actor) {
        if (UserStatus.BANNED.name().equals(actor.status())) {
            throw ApiException.forbidden(ErrorCode.ACCOUNT_BANNED, "Banned accounts cannot change the catalog");
        }
    }

    /** A pending (or rejected) merchant may set up drafts but cannot activate products (section 3.1). */
    public static void requireApproved(Actor actor) {
        requireCanEdit(actor);
        if (UserStatus.PENDING_APPROVAL.name().equals(actor.status())
                || UserStatus.REJECTED.name().equals(actor.status())) {
            throw ApiException.forbidden(ErrorCode.MERCHANT_NOT_APPROVED,
                    "Products can be activated once the merchant is approved");
        }
    }
}
