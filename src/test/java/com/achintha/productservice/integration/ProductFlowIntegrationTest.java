package com.achintha.productservice.integration;

import static com.achintha.productservice.security.GatewayUserHeaderFilter.USER_ID_HEADER;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** End-to-end flow against a real PostgreSQL started by Testcontainers. */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
@AutoConfigureMockMvc
@Testcontainers
class ProductFlowIntegrationTest {

    /** Writes must carry the header the API Gateway sets after validating the JWT. */
    private static final String GATEWAY_USER = "integration-test-user";

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createListFilterAndAdjustInventory() throws Exception {
        String electronics = createCategory("Electronics");
        String books = createCategory("Books");

        // Create -> inventory starts at 0
        String laptopId = createProduct("Gaming Laptop", "999.99", "LAP-001", electronics)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.sku").value("LAP-001"))
                .andExpect(jsonPath("$.category.name").value("Electronics"))
                .andExpect(jsonPath("$.stock.quantityAvailable").value(0))
                .andExpect(jsonPath("$.stock.reservedQuantity").value(0))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        laptopId = JsonPath.read(laptopId, "$.id");
        createProduct("Office Laptop", "499.00", "LAP-002", electronics);
        createProduct("Laptop Repair Handbook", "25.50", "BOOK-001", books);

        // Category filter
        mockMvc.perform(get("/api/products").param("categoryId", electronics).param("sort", "price,asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content", hasSize(2)))
                .andExpect(jsonPath("$.content[0].sku").value("LAP-002"))
                .andExpect(jsonPath("$.content[1].sku").value("LAP-001"))
                .andExpect(jsonPath("$.content[1].stock.quantityAvailable").value(0));

        // Combined filters: name search (case-insensitive) + price range
        mockMvc.perform(get("/api/products").param("q", "LAPTOP").param("maxPrice", "500"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[*].sku", containsInAnyOrder("LAP-002", "BOOK-001")));
        mockMvc.perform(get("/api/products").param("q", "laptop").param("minPrice", "100")
                        .param("categoryId", electronics).param("size", "1").param("sort", "price,desc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.content[0].sku").value("LAP-001"));

        // Adjust inventory: restock, then take some out
        adjust(laptopId, 10).andExpect(status().isOk()).andExpect(jsonPath("$.quantityAvailable").value(10));
        adjust(laptopId, -3).andExpect(status().isOk()).andExpect(jsonPath("$.quantityAvailable").value(7));

        // Would go negative -> 409, quantity unchanged
        adjust(laptopId, -8)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.message").isNotEmpty());

        mockMvc.perform(get("/api/products/{id}", laptopId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Gaming Laptop"))
                .andExpect(jsonPath("$.category.id").value(electronics))
                .andExpect(jsonPath("$.stock.quantityAvailable").value(7));
    }

    @Test
    void updateAndDeleteProduct() throws Exception {
        String toys = createCategory("Toys");
        String games = createCategory("Games");
        String id = JsonPath.read(createProduct("Puzzle", "12.00", "TOY-001", toys)
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(), "$.id");

        mockMvc.perform(put("/api/products/{id}", id).header(USER_ID_HEADER, GATEWAY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(productJson("Jigsaw Puzzle", "14.50", "TOY-001", games)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Jigsaw Puzzle"))
                .andExpect(jsonPath("$.price").value(14.5))
                .andExpect(jsonPath("$.category.name").value("Games"));

        mockMvc.perform(delete("/api/products/{id}", id).header(USER_ID_HEADER, GATEWAY_USER))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/products/{id}", id)).andExpect(status().isNotFound());
        adjust(id, 1).andExpect(status().isNotFound());
    }

    @Test
    void errorsUseConsistentJsonBodies() throws Exception {
        String garden = createCategory("Garden");
        createProduct("Hose", "20.00", "GARDEN-001", garden).andExpect(status().isCreated());

        // 400: validation
        mockMvc.perform(post("/api/products").header(USER_ID_HEADER, GATEWAY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"name":"","price":-1,"sku":"X"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'name')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'price')]").exists())
                .andExpect(jsonPath("$.fieldErrors[?(@.field == 'categoryId')]").exists());
        mockMvc.perform(patch("/api/products/{id}/inventory", UUID.randomUUID()).header(USER_ID_HEADER, GATEWAY_USER)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.fieldErrors[0].field").value("delta"));
        mockMvc.perform(get("/api/products").param("sort", "password,asc"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/products/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        // 404
        mockMvc.perform(get("/api/products/{id}", UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.path").isNotEmpty());
        createProduct("Rake", "9.00", "GARDEN-002", UUID.randomUUID().toString())
                .andExpect(status().isNotFound());

        // 409: duplicates
        createProduct("Another Hose", "21.00", "GARDEN-001", garden).andExpect(status().isConflict());
        mockMvc.perform(post("/api/categories").header(USER_ID_HEADER, GATEWAY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Garden\"}"))
                .andExpect(status().isConflict());
    }

    private String createCategory(String name) throws Exception {
        String body = mockMvc.perform(post("/api/categories").header(USER_ID_HEADER, GATEWAY_USER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"description\":\"" + name + " items\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.name").value(name))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private ResultActions createProduct(String name, String price, String sku, String categoryId) throws Exception {
        return mockMvc.perform(post("/api/products").header(USER_ID_HEADER, GATEWAY_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content(productJson(name, price, sku, categoryId)));
    }

    private static String productJson(String name, String price, String sku, String categoryId) {
        return """
                {"name":"%s","description":"%s description","price":%s,"sku":"%s","categoryId":"%s"}
                """.formatted(name, name, price, sku, categoryId);
    }

    private ResultActions adjust(String productId, int delta) throws Exception {
        return mockMvc.perform(patch("/api/products/{id}/inventory", productId).header(USER_ID_HEADER, GATEWAY_USER)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"delta\":" + delta + "}"));
    }
}
