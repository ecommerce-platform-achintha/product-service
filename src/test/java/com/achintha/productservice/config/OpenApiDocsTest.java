package com.achintha.productservice.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.support.IntegrationTest;
import org.junit.jupiter.api.Test;

/** The public API document is open, lists the public routes and never the internal ones. */
class OpenApiDocsTest extends IntegrationTest {

    @Test
    void apiDocsListPublicRoutesButNoInternalOnes() throws Exception {
        String docs = mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(docs).contains("/api/public/products", "/api/merchant/products", "/api/merchant/variants/",
                        "/api/merchant/discounts", "/api/admin/categories", "/api/customer/products/", "bearerAuth")
                .doesNotContain("/internal/", "X-User-Id");
    }
}
