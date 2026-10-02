package com.achintha.productservice.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.support.IntegrationTest;
import com.achintha.productservice.support.TestUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Object-level authorization (BOLA, section 3.4): merchant B never sees or changes merchant A's catalog (404, not
 * 403); assistants need the route's permission; customers never reach merchant routes; revoked tokens are refused;
 * the old user-callable inventory PATCH is gone.
 */
class OwnershipTest extends IntegrationTest {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private Category category;
    private Listing a;
    private TestUser merchantB;

    @BeforeEach
    void setUp() throws Exception {
        category = category();
        a = listing(category, "Owned Teapot", "2500.00", 10);
        merchantB = merchant();
        storeVisible(merchantB, true);
    }

    @Test
    void anotherMerchantGets404ForEverythingOfStoreA() throws Exception {
        String b = tokenFor(merchantB);
        mockMvc.perform(withBearer(get("/api/merchant/products/" + a.product()), b))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(json(withBearer(put("/api/merchant/products/" + a.product()), b),
                        simpleProductJson("Stolen", category, "B-" + next(), "1.00", 0)))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(withBearer(post("/api/merchant/products/" + a.product() + "/status"), b),
                        "{\"status\":\"ARCHIVED\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(withBearer(delete("/api/merchant/products/" + a.product()), b))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(withBearer(patch("/api/merchant/variants/" + a.variant() + "/stock"), b),
                        "{\"mode\":\"SET\",\"quantity\":0,\"reason\":\"sabotage\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), b),
                        "{\"scope\":\"PRODUCT\",\"productPublicId\":\"" + a.product()
                                + "\",\"type\":\"PERCENT\",\"value\":90}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), b),
                        "{\"scope\":\"VARIANT\",\"variantPublicId\":\"" + a.variant()
                                + "\",\"type\":\"PERCENT\",\"value\":90}"))
                .andExpect(status().isNotFound());
        // B's own list does not contain A's product
        mockMvc.perform(withBearer(get("/api/merchant/products"), b))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
        // A's product is untouched
        mockMvc.perform(withBearer(get("/api/merchant/products/" + a.product()), tokenFor(a.merchant())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.variants[0].stock.quantityAvailable").value(10));
    }

    @Test
    void assistantsNeedTheRoutePermission() throws Exception {
        String stockOnly = tokenFor(assistant(a.merchant(), AssistantPermission.STOCK_EDIT));
        String productOnly = tokenFor(assistant(a.merchant(), AssistantPermission.PRODUCT_EDIT));

        // STOCK_EDIT can read the catalog and edit stock, but not products, discounts or replies
        mockMvc.perform(withBearer(get("/api/merchant/products/" + a.product()), stockOnly))
                .andExpect(status().isOk());
        mockMvc.perform(json(withBearer(patch("/api/merchant/variants/" + a.variant() + "/stock"), stockOnly),
                        "{\"mode\":\"DELTA\",\"quantity\":5,\"reason\":\"restock\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantityAvailable").value(15));
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), stockOnly),
                        simpleProductJson("Nope", category, "N-" + next(), "1.00", 0)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCESS_DENIED"));
        mockMvc.perform(withBearer(get("/api/merchant/discounts"), stockOnly)).andExpect(status().isForbidden());
        mockMvc.perform(withBearer(get("/api/merchant/products/reviews"), stockOnly))
                .andExpect(status().isForbidden());

        // PRODUCT_EDIT cannot edit stock
        mockMvc.perform(json(withBearer(patch("/api/merchant/variants/" + a.variant() + "/stock"), productOnly),
                        "{\"mode\":\"SET\",\"quantity\":1,\"reason\":\"count\"}"))
                .andExpect(status().isForbidden());

        // An assistant of store B with every permission still only sees store B
        String otherAssistant = tokenFor(assistant(merchantB, AssistantPermission.values()));
        mockMvc.perform(withBearer(get("/api/merchant/products/" + a.product()), otherAssistant))
                .andExpect(status().isNotFound());
    }

    @Test
    void customersAndAdminsNeverReachMerchantRoutes() throws Exception {
        for (String token : new String[] {tokenFor(customer()), tokenFor(admin())}) {
            mockMvc.perform(withBearer(get("/api/merchant/products"), token)).andExpect(status().isForbidden());
            mockMvc.perform(json(withBearer(patch("/api/merchant/variants/" + a.variant() + "/stock"), token),
                            "{\"mode\":\"SET\",\"quantity\":0,\"reason\":\"x\"}"))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void theOldInventoryPatchIsGone() throws Exception {
        mockMvc.perform(json(patch("/api/products/" + a.product() + "/inventory"), "{\"delta\":-10}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(withBearer(patch("/api/products/" + a.product() + "/inventory"),
                        tokenFor(a.merchant())), "{\"delta\":-10}"))
                .andExpect(status().isForbidden());
        // Not just refused: no handler is mapped to the old paths at all
        assertThat(handlerMapping.getHandlerMethods().keySet())
                .noneMatch(info -> info.getPatternValues().stream()
                        .anyMatch(p -> p.contains("/inventory") || p.startsWith("/api/products")
                                || p.startsWith("/api/categories")));
    }

    @Test
    void revokedTokensAndBannedMerchantsAreRefused() throws Exception {
        // user-service bumped the token version (e.g. a ban): the old token is revoked
        securityStateCache.apply(a.merchant().id(), 2L, UserStatus.ACTIVE);
        mockMvc.perform(withBearer(get("/api/merchant/products"), tokenFor(a.merchant())))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("TOKEN_REVOKED"));
        mockMvc.perform(withBearer(get("/api/merchant/products"), tokenFor(a.merchant().withTokenVersion(2))))
                .andExpect(status().isOk());

        // A banned merchant may still read its catalog but not change it
        String banned = tokenFor(merchantB, "BANNED");
        mockMvc.perform(withBearer(get("/api/merchant/products"), banned)).andExpect(status().isOk());
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), banned),
                        simpleProductJson("Banned", category, "BN-" + next(), "1.00", 0)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
    }

    @Test
    void pendingMerchantsDraftButCannotActivate() throws Exception {
        TestUser pending = merchant(UserStatus.PENDING_APPROVAL);
        String token = tokenFor(pending, "PENDING_APPROVAL");
        String body = mockMvc.perform(json(withBearer(post("/api/merchant/products"), token),
                        simpleProductJson("Draft", category, "PD-" + next(), "10.00", 1)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT"))
                .andReturn().getResponse().getContentAsString();
        mockMvc.perform(json(withBearer(post("/api/merchant/products/" + read(body, "$.publicId") + "/status"),
                        token), "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("MERCHANT_NOT_APPROVED"));
    }
}
