package com.achintha.productservice.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.security.UserStatus;
import com.achintha.productservice.support.IntegrationTest;
import com.achintha.productservice.support.TestUser;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Public search and detail (section 5): only ACTIVE products of visible stores with an active variant; filters on
 * the effective price, stock, rating, category and store; whitelisted sort; LIKE wildcards are literal. Every test
 * works in its own category so other tests' data never interferes.
 */
class SearchVisibilityTest extends IntegrationTest {

    private Category category;

    @BeforeEach
    void setUp() {
        category = category();
    }

    /** Searches within this test's category. @param params name, value, name, value, ... */
    private List<String> search(String... params) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/public/products").param("category", category.getSlug());
        for (int i = 0; i < params.length; i += 2) {
            request = request.param(params[i], params[i + 1]);
        }
        String body = mockMvc.perform(request).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.content[*].publicId");
    }

    private void setStatus(Listing listing, String status) throws Exception {
        mockMvc.perform(json(withBearer(post("/api/merchant/products/" + listing.product() + "/status"),
                tokenFor(listing.merchant())), "{\"status\":\"" + status + "\"}")).andExpect(status().isOk());
    }

    @Test
    void onlyActiveProductsOfVisibleStoresWithAnActiveVariantAreListed() throws Exception {
        Listing visible = listing(category, "Visible Vase", "100.00", 1);
        Listing draft = listing(category, "Draft Vase", "100.00", 1);
        setStatus(draft, "DRAFT");
        Listing archived = listing(category, "Archived Vase", "100.00", 1);
        setStatus(archived, "ARCHIVED");
        Listing hiddenStore = listing(category, "Hidden Store Vase", "100.00", 1);
        storeVisible(hiddenStore.merchant(), false);
        Listing banGrace = listing(category, "Grace Vase", "100.00", 1);
        ownerStatus(banGrace.merchant(), UserStatus.BAN_GRACE);
        Listing banned = listing(category, "Banned Vase", "100.00", 1);
        ownerStatus(banned.merchant(), UserStatus.BANNED);

        // A pending merchant's product: the store was never announced as visible
        TestUser pending = merchant(UserStatus.PENDING_APPROVAL);
        createProduct(pending, simpleProductJson("Pending Vase", category, "PV-" + next(), "100.00", 1));

        assertThat(search()).containsExactly(visible.product());
        for (Listing hidden : List.of(draft, archived, hiddenStore, banGrace, banned)) {
            mockMvc.perform(get("/api/public/products/" + hidden.product())).andExpect(status().isNotFound());
            mockMvc.perform(get("/api/public/products/" + hidden.product() + "/reviews"))
                    .andExpect(status().isNotFound());
        }
        mockMvc.perform(get("/api/public/products/" + visible.product()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.store.publicId").value(visible.store()))
                .andExpect(jsonPath("$.variants[0].inStock").value(true))
                .andExpect(jsonPath("$.variants[0].lowStock").value(true))
                // public views never carry SKUs or stock counts
                .andExpect(jsonPath("$.variants[0].sku").doesNotExist())
                .andExpect(jsonPath("$.variants[0].quantityAvailable").doesNotExist())
                .andExpect(jsonPath("$.variants[0].reservedQuantity").doesNotExist());

        // The owner is reinstated: the listing comes back
        ownerStatus(banGrace.merchant(), UserStatus.ACTIVE);
        assertThat(search()).containsExactlyInAnyOrder(visible.product(), banGrace.product());
    }

    @Test
    void aProductWhoseVariantsAreAllInactiveIsNotListed() throws Exception {
        Listing listing = listing(category, "Retired Jug", "50.00", 1);
        String detail = mockMvc.perform(withBearer(get("/api/merchant/products/" + listing.product()),
                tokenFor(listing.merchant()))).andReturn().getResponse().getContentAsString();
        // Back to draft, deactivate the only variant, then the product cannot be activated again
        setStatus(listing, "DRAFT");
        String body = """
                {"name":"Retired Jug","categorySlug":"%s","variants":[{"publicId":"%s","sku":"%s","listPrice":50.00,
                 "status":"INACTIVE"}]}
                """.formatted(category.getSlug(), listing.variant(), read(detail, "$.variants[0].sku"));
        mockMvc.perform(json(withBearer(put("/api/merchant/products/" + listing.product()),
                tokenFor(listing.merchant())), body)).andExpect(status().isOk());
        mockMvc.perform(json(withBearer(post("/api/merchant/products/" + listing.product() + "/status"),
                        tokenFor(listing.merchant())), "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PRODUCT_NOT_SELLABLE"));
        assertThat(search()).isEmpty();
    }

    @Test
    void filtersUseTheEffectivePriceStockRatingAndStore() throws Exception {
        Listing cheap = listing(category, "Cheap Bowl", "100.00", 0);
        Listing mid = listing(category, "Mid Bowl", "500.00", 3);
        Listing dear = listing(category, "Dear Bowl", "1000.00", 3);
        // 60 % off the dear bowl: effective 400, now cheaper than the mid bowl
        mockMvc.perform(json(withBearer(post("/api/merchant/discounts"), tokenFor(dear.merchant())),
                        "{\"scope\":\"PRODUCT\",\"productPublicId\":\"" + dear.product()
                                + "\",\"type\":\"PERCENT\",\"value\":60}"))
                .andExpect(status().isCreated());

        assertThat(search("sort", "price")).containsExactly(cheap.product(), dear.product(), mid.product());
        assertThat(search("sort", "price,desc")).containsExactly(mid.product(), dear.product(), cheap.product());
        assertThat(search("minPrice", "300", "maxPrice", "450")).containsExactly(dear.product());
        assertThat(search("inStock", "true")).containsExactlyInAnyOrder(mid.product(), dear.product());
        assertThat(search("store", mid.store())).containsExactly(mid.product());
        assertThat(search("minRating", "1")).isEmpty();
        assertThat(search("sort", "newest")).containsExactly(dear.product(), mid.product(), cheap.product());

        mockMvc.perform(get("/api/public/products?category=" + category.getSlug() + "&q=dear"))
                .andExpect(jsonPath("$.content[0].price").value(400.00))
                .andExpect(jsonPath("$.content[0].listPrice").value(1000.00))
                .andExpect(jsonPath("$.content[0].discounted").value(true))
                .andExpect(jsonPath("$.content[0].inStock").value(true));

        mockMvc.perform(get("/api/public/products?sort=cheapest")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SORT"));
        mockMvc.perform(get("/api/public/products?minPrice=10&maxPrice=1")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/public/products?size=500"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.size").value(50));
        assertThat(search("store", "STR-2610-ZZZZZZ")).isEmpty();
        mockMvc.perform(get("/api/public/products?category=no-such-category"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void textSearchIsEscapedAndRankedByRelevance() throws Exception {
        Listing percent = listing(category, "Save 50% Mug", "10.00", 1);
        Listing plain = listing(category, "Save 50 Mug", "10.00", 1);
        Listing prefix = listing(category, "Mug Holder", "10.00", 1);
        Listing middle = listing(category, "Big Mug", "10.00", 1);

        assertThat(search("q", "50%")).containsExactly(percent.product());
        assertThat(search("q", "_")).isEmpty();
        assertThat(search("q", "MUG")).hasSize(4).first().isEqualTo(prefix.product());
        assertThat(search("q", "big")).containsExactly(middle.product());
        assertThat(search("q", "save 50")).containsExactlyInAnyOrder(percent.product(), plain.product());
    }

    @Test
    void subCategoriesAreIncludedInTheirParentsSearch() throws Exception {
        String child = "child-" + next();
        mockMvc.perform(json(withBearer(post("/api/admin/categories"), tokenFor(admin())),
                        "{\"slug\":\"" + child + "\",\"name\":\"Child " + next() + "\",\"parentSlug\":\""
                                + category.getSlug() + "\"}"))
                .andExpect(status().isCreated());
        Category childCategory = categoryRepository.findBySlug(child).orElseThrow();
        Listing inParent = listing(category, "Parent Plate", "10.00", 1);
        Listing inChild = listing(childCategory, "Child Plate", "10.00", 1);

        assertThat(search()).containsExactlyInAnyOrder(inParent.product(), inChild.product());
        mockMvc.perform(get("/api/public/products?category=" + child))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(inChild.product()));
    }

    @Test
    void stockChangesShowUpAsAvailabilityFlags() throws Exception {
        Listing listing = listing(category, "Flag Cup", "10.00", 50);
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].inStock").value(true))
                .andExpect(jsonPath("$.variants[0].lowStock").value(false));
        mockMvc.perform(json(withBearer(patch("/api/merchant/variants/" + listing.variant() + "/stock"),
                        tokenFor(listing.merchant())), "{\"mode\":\"SET\",\"quantity\":0,\"reason\":\"sold out\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/public/products/" + listing.product()))
                .andExpect(jsonPath("$.variants[0].inStock").value(false));
        assertThat(search("inStock", "true")).isEmpty();
    }
}
