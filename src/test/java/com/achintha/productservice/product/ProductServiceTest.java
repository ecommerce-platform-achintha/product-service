package com.achintha.productservice.product;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.audit.AuditLogEntry;
import com.achintha.productservice.audit.AuditLogRepository;
import com.achintha.productservice.category.Category;
import com.achintha.productservice.outbox.OutboxMessage;
import com.achintha.productservice.outbox.OutboxRepository;
import com.achintha.productservice.support.IntegrationTest;
import com.achintha.productservice.support.TestUser;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The merchant product aggregate: default variants, options and variants validation, SKU uniqueness per store,
 * retiring variants on update, status transitions, ProductChanged events and audit rows.
 */
class ProductServiceTest extends IntegrationTest {

    @Autowired
    private OutboxRepository outboxRepository;
    @Autowired
    private AuditLogRepository auditLogRepository;
    @Autowired
    private ProductRepository productRepository;

    private Category category;
    private TestUser merchant;
    private String token;

    @BeforeEach
    void setUp() {
        category = category();
        merchant = merchant();
        token = tokenFor(merchant);
    }

    private String shirtJson(String skuPrefix) {
        return """
                {"name":"Linen Shirt","categorySlug":"%s","codAllowed":false,
                 "options":[{"name":"Size","values":["S","M"]},{"name":"Colour","values":["White","Blue"]}],
                 "variants":[
                   {"sku":"%s-S-W","attributes":{"size":"s","Colour":"white"},"listPrice":3500.00,"initialStock":4},
                   {"sku":"%s-M-B","attributes":{"Size":"M","Colour":"Blue"},"listPrice":3750.00}]}
                """.formatted(category.getSlug(), skuPrefix, skuPrefix);
    }

    @Test
    void aSimpleProductGetsOneDefaultVariantAndStartsAsDraft() throws Exception {
        String sku = "MUG-" + next();
        String body = createProduct(merchant, simpleProductJson("Plain Mug <script>x</script>", category, sku,
                "450.00", 12));
        assertThat(read(body, "$.publicId")).matches("ITM-\\d{4}-[0-9A-HJKMNP-TV-Z]{6}");
        assertThat(read(body, "$.status")).isEqualTo("DRAFT");
        assertThat(read(body, "$.name")).isEqualTo("Plain Mug");
        assertThat(read(body, "$.variants.length()")).isEqualTo("1");
        assertThat(read(body, "$.variants[0].publicId")).startsWith("VAR-");
        assertThat(read(body, "$.variants[0].label")).isEqualTo("Default");
        assertThat(read(body, "$.variants[0].stock.quantityAvailable")).isEqualTo("12");
        assertThat(read(body, "$.codAllowed")).isEqualTo("true");
        assertThat(read(body, "$.imageUrls[0]")).isEqualTo("https://images.example.invalid/products/" + sku + ".png");
    }

    @Test
    void variantsMustUseTheOptionsExactlyAndAreStoredWithTheirSpelling() throws Exception {
        String body = createProduct(merchant, shirtJson("SH" + next()));
        assertThat(read(body, "$.codAllowed")).isEqualTo("false");
        assertThat(read(body, "$.variants[0].attributes.Size")).isEqualTo("S");
        assertThat(read(body, "$.variants[0].attributes.Colour")).isEqualTo("White");
        assertThat(read(body, "$.variants[0].label")).isEqualTo("Size: S / Colour: White");

        String base = "{\"name\":\"Bad\",\"categorySlug\":\"" + category.getSlug() + "\","
                + "\"options\":[{\"name\":\"Size\",\"values\":[\"S\",\"M\"]}],\"variants\":[";
        for (String variants : new String[] {
                // missing option value, unknown value, extra attribute, duplicate combination
                "{\"sku\":\"A" + next() + "\",\"attributes\":{},\"listPrice\":1}",
                "{\"sku\":\"A" + next() + "\",\"attributes\":{\"Size\":\"XL\"},\"listPrice\":1}",
                "{\"sku\":\"A" + next() + "\",\"attributes\":{\"Size\":\"S\",\"Fit\":\"Slim\"},\"listPrice\":1}",
                "{\"sku\":\"A" + next() + "\",\"attributes\":{\"Size\":\"S\"},\"listPrice\":1},"
                        + "{\"sku\":\"B" + next() + "\",\"attributes\":{\"Size\":\"s\"},\"listPrice\":1}"}) {
            mockMvc.perform(json(withBearer(post("/api/merchant/products"), token), base + variants + "]}"))
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANTS"));
        }
        // Options without variants, and a product with neither variants nor sku/listPrice
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), token), base.replace(",\"variants\":[", "}")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANTS"));
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), token),
                        "{\"name\":\"Empty\",\"categorySlug\":\"" + category.getSlug() + "\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANTS"));
        // Unknown / inactive category
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), token),
                        simpleProductJson("X", category, "C" + next(), "1.00", 0).replace(category.getSlug(), "nope")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_AVAILABLE"));
    }

    @Test
    void skusAreUniquePerStoreIgnoringCase() throws Exception {
        String sku = "UNIQUE-" + next();
        createProduct(merchant, simpleProductJson("First", category, sku, "10.00", 0));

        mockMvc.perform(json(withBearer(post("/api/merchant/products"), token),
                        simpleProductJson("Second", category, sku.toLowerCase(), "10.00", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_TAKEN"));
        // Twice in one request
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), token), shirtJson("DUP").replace("DUP-M-B",
                        "DUP-S-W")))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANTS"));
        // Another store may use the same SKU
        TestUser other = merchant();
        createProduct(other, simpleProductJson("Other store", category, sku, "10.00", 0));
    }

    @Test
    void updateKeepsListedVariantsAddsNewOnesAndRetiresTheRest() throws Exception {
        String prefix = "UP" + next();
        String body = createProduct(merchant, shirtJson(prefix));
        String product = read(body, "$.publicId");
        String small = read(body, "$.variants[0].publicId");
        String medium = read(body, "$.variants[1].publicId");

        String update = """
                {"name":"Linen Shirt v2","categorySlug":"%s",
                 "options":[{"name":"Size","values":["S","M","L"]},{"name":"Colour","values":["White","Blue"]}],
                 "variants":[
                   {"publicId":"%s","sku":"%s-S-W","attributes":{"Size":"S","Colour":"White"},"listPrice":3400.00},
                   {"sku":"%s-L-W","attributes":{"Size":"L","Colour":"White"},"listPrice":3900.00,"initialStock":2}]}
                """.formatted(category.getSlug(), small, prefix, prefix);
        mockMvc.perform(json(withBearer(put("/api/merchant/products/" + product), token), update))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Linen Shirt v2"))
                .andExpect(jsonPath("$.variants.length()").value(3))
                .andExpect(jsonPath("$.variants[0].publicId").value(small))
                .andExpect(jsonPath("$.variants[0].listPrice").value(3400.00))
                // stock is not changed by an update
                .andExpect(jsonPath("$.variants[0].stock.quantityAvailable").value(4))
                .andExpect(jsonPath("$.variants[1].stock.quantityAvailable").value(2))
                .andExpect(jsonPath("$.variants[2].publicId").value(medium))
                .andExpect(jsonPath("$.variants[2].status").value("INACTIVE"));

        // A variant of another product cannot be hijacked
        String foreign = read(createProduct(merchant, simpleProductJson("Other", category, "OT" + next(), "1.00",
                0)), "$.variants[0].publicId");
        mockMvc.perform(json(withBearer(put("/api/merchant/products/" + product), token),
                        update.replace(small, foreign)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_VARIANTS"));
        // The retired variant still holds its SKU in the store
        mockMvc.perform(json(withBearer(post("/api/merchant/products"), token),
                        simpleProductJson("Clash", category, prefix + "-M-B", "1.00", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SKU_TAKEN"));
    }

    @Test
    void statusTransitionsFollowTheLifecycleAndAreAuditedWithEvents() throws Exception {
        String product = read(createProduct(merchant, simpleProductJson("Lifecycle", category, "LC" + next(),
                "10.00", 0)), "$.publicId");
        String url = "/api/merchant/products/" + product;
        activate(merchant, product);
        // Archive (DELETE), then it cannot be edited or activated directly, only moved back to DRAFT
        mockMvc.perform(withBearer(delete(url), token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ARCHIVED"));
        mockMvc.perform(json(withBearer(put(url), token),
                        simpleProductJson("Lifecycle", category, "LC" + next(), "10.00", 0)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        mockMvc.perform(json(withBearer(post(url + "/status"), token), "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INVALID_STATUS_TRANSITION"));
        mockMvc.perform(json(withBearer(post(url + "/status"), token), "{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(json(withBearer(post(url + "/status"), token), "{\"status\":\"DRAFT\"}"))
                .andExpect(status().isOk()); // no-op

        UUID productId = productRepository.findByPublicId(product).orElseThrow().getId();
        List<OutboxMessage> events = outboxRepository.findAllByAggregateIdOrderByIdAsc(productId);
        assertThat(events).extracting(OutboxMessage::getEventType).containsOnly("ProductChanged");
        assertThat(events).extracting(m -> read(m.getPayload(), "$.change"))
                .containsExactly("CREATED", "STATUS_CHANGED", "STATUS_CHANGED", "STATUS_CHANGED");
        assertThat(events.get(2).getPayload()).contains("\"status\":\"ARCHIVED\"", "\"previousStatus\":\"ACTIVE\"")
                .contains("\"storeId\":\"" + merchant.storeId() + "\"").doesNotContain("quantityAvailable");
        assertThat(events).allSatisfy(m -> assertThat(m.getMessageKey()).isEqualTo(productId.toString()));

        assertThat(auditLogRepository.findAllByTargetTypeAndTargetIdOrderByIdAsc("PRODUCT", product))
                .extracting(AuditLogEntry::getAction)
                .containsExactly("PRODUCT_CREATED", "PRODUCT_STATUS_CHANGED", "PRODUCT_STATUS_CHANGED",
                        "PRODUCT_STATUS_CHANGED");
    }

    @Test
    void merchantListIsPagedAndFiltered() throws Exception {
        TestUser lister = merchant();
        String t = tokenFor(lister);
        for (int i = 0; i < 3; i++) {
            createProduct(lister, simpleProductJson("Listed " + i, category, "LS" + next(), "10.00", i));
        }
        mockMvc.perform(withBearer(get("/api/merchant/products?size=2&sort=name,asc"), t))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].name").value("Listed 0"))
                .andExpect(jsonPath("$.content[0].variantCount").value(1));
        mockMvc.perform(withBearer(get("/api/merchant/products?q=listed 2&status=DRAFT"), t))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].totalAvailable").value(2));
        mockMvc.perform(withBearer(get("/api/merchant/products?sort=price"), t))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_SORT"));
    }
}
