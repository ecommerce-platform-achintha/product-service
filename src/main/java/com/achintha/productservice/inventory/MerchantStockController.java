package com.achintha.productservice.inventory;

import com.achintha.productservice.config.OpenApiConfig;
import com.achintha.productservice.security.Actor;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Stock edits: the merchant, or an assistant with {@code STOCK_EDIT}. */
@RestController
@SecurityRequirement(name = OpenApiConfig.BEARER_AUTH)
@PreAuthorize("hasAuthority('PERM_STOCK_EDIT')")
@RequiredArgsConstructor
@Tag(name = "Merchant: stock")
public class MerchantStockController {

    private final InventoryService inventoryService;

    @PatchMapping("/api/merchant/variants/{publicId}/stock")
    @Operation(summary = "Set (SET) or change (DELTA) a variant's available stock, with a reason (audited); 409 if "
            + "the result would be negative or the stock changed since expectedVersion")
    public InventoryResponse adjust(@AuthenticationPrincipal Jwt jwt, @PathVariable String publicId,
                                    @Valid @RequestBody AdjustInventoryRequest request) {
        return inventoryService.adjust(Actor.from(jwt), publicId, request);
    }
}
