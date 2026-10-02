package com.achintha.productservice.discount;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Merchant discounts: validation rules and how the best live one shows up in public and internal prices. */
class DiscountApiTest extends IntegrationTest {

    private Listing listing;
    private String token;

    @BeforeEach
    void setUp() throws Exception {
        listing = listing(category(), "Discounted Chair", "2000.00", 5);
        token = tokenFor(listing.merchant());
    }

    private String discountJson(String type, String value, Instant startsAt, Instant endsAt) {
        return "{\"name\":\"Sale\",\"scope\":\"VARIANT\",\"variantPublicId\":\"" + listing.variant()
                + "\",\"type\":\"" + type + "\",\"value\":" + value
                + (startsAt == null ? "" : ",\"startsAt\":\"" + startsAt + "\"")
                + (endsAt == null ? "" : ",\"endsAt\":\"" + endsAt + "\"") + "}";
    }

    @Test
    void invalidDiscountsAreRejected() throws Exception {
        for (String body : new String[] {
                discountJson("PERCENT", "0.5", null, null),
                discountJson("PERCENT", "91", null, null),
                discountJson("FIXED", "2000", null, null),
                discountJson("FIXED", "2500", null, null),
                discountJson("PERCENT", "10", clock.instant().plusSeconds(60), clock.instant()),
                discountJson("PERCENT", "10", null, clock.instant().minusSeconds(60)),
                "{\"scope\":\"PRODUCT\",\"variantPublicId\":\"" + listing.variant()
                        + "\",\"type\":\"PERCENT\",\"value\":10}"}) {
            mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), token), body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_DISCOUNT"));
        }
    }

    @Test
    void theBestLiveDiscountIsAppliedEverywhereAtReadTime() throws Exception {
        Instant now = clock.instant();
        // 10 % now, 30 % starting in an hour, 300 off now (300 > 10 % of 2000)
        mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), token),
                discountJson("PERCENT", "10", null, null))).andExpect(status().isCreated());
        mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), token),
                        discountJson("PERCENT", "30", now.plus(Duration.ofHours(1)), now.plus(Duration.ofHours(3)))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.live").value(false));
        String fixed = mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), token),
                        discountJson("FIXED", "300", null, null)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.live").value(true))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].listPrice").value(2000.00))
                .andExpect(jsonPath("$.variants[0].effectivePrice").value(1700.00))
                .andExpect(jsonPath("$.variants[0].discount.type").value("FIXED"))
                .andExpect(jsonPath("$.variants[0].discount.amount").value(300.00));

        // An hour later the 30 % discount is live and wins
        clock.advance(Duration.ofHours(1).plusMinutes(1));
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].effectivePrice").value(1400.00));
        mockMvc.perform(json(withBearer(post("/internal/variants/quote"), serviceToken()),
                        "{\"variantPublicIds\":[\"" + listing.variant() + "\"]}"))
                .andExpect(jsonPath("$.items[0].listPrice").value(2000.00))
                .andExpect(jsonPath("$.items[0].discountAmount").value(600.00))
                .andExpect(jsonPath("$.items[0].unitPrice").value(1400.00));

        // After it ends, the fixed one is back; deleting it leaves the 10 %
        clock.advance(Duration.ofHours(3));
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].effectivePrice").value(1700.00));
        String fixedId = read(fixed, "$.publicId");
        // (a fresh token: the clock moved past the first one's expiry)
        mockMvc.perform(withBearer(delete("/api/merchant/discounts/" + fixedId), tokenFor(listing.merchant())))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].effectivePrice").value(1800.00));
    }

    @Test
    void deactivatingADiscountRemovesItFromPrices() throws Exception {
        String created = mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), token),
                        discountJson("PERCENT", "50", null, null)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        String id = read(created, "$.publicId");
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].effectivePrice").value(1000.00));
        mockMvc.perform(json(withBearer(put("/api/merchant/discounts/" + id), token),
                        discountJson("PERCENT", "50", null, null).replace("}", ",\"active\":false}")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].effectivePrice").value(2000.00))
                .andExpect(jsonPath("$.variants[0].discount").doesNotExist());
        mockMvc.perform(withBearer(get("/api/merchant/discounts?product=" + listing.product()), token))
                .andExpect(jsonPath("$.totalElements").value(1));
    }
}
