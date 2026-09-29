package com.achintha.productservice.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.category.CategoryController;
import com.achintha.productservice.category.CategoryRequest;
import com.achintha.productservice.category.CategoryService;
import com.achintha.productservice.common.PageResponse;
import com.achintha.productservice.inventory.InventoryService;
import com.achintha.productservice.product.ProductController;
import com.achintha.productservice.product.ProductFilter;
import com.achintha.productservice.product.ProductRequest;
import com.achintha.productservice.product.ProductService;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Writes need the Gateway's X-User-Id header; reads do not. Services are mocked, so no DB is needed. */
@WebMvcTest(controllers = {ProductController.class, CategoryController.class}, properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false"
})
class GatewayUserHeaderFilterTest {

    private static final UUID ID = UUID.randomUUID();
    private static final String PRODUCT_JSON = """
            {"name":"Mouse","price":24.99,"sku":"MOUSE-001","categoryId":"%s"}
            """.formatted(UUID.randomUUID());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;
    @MockitoBean
    private InventoryService inventoryService;
    @MockitoBean
    private CategoryService categoryService;

    static Stream<MockHttpServletRequestBuilder> writeRequests() {
        return Stream.of(
                post("/api/products").contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON),
                put("/api/products/{id}", ID).contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON),
                delete("/api/products/{id}", ID),
                patch("/api/products/{id}/inventory", ID).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"delta\":5}"),
                post("/api/categories").contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Books\"}"));
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void writeWithoutUserIdIsRejectedBeforeReachingTheHandler(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request)
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().json("{\"error\":\"Authentication required\"}", true));

        verifyNoInteractions(productService, inventoryService, categoryService);
    }

    @ParameterizedTest
    @MethodSource("writeRequests")
    void writeWithBlankUserIdIsRejected(MockHttpServletRequestBuilder request) throws Exception {
        mockMvc.perform(request.header(GatewayUserHeaderFilter.USER_ID_HEADER, "   "))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(productService, inventoryService, categoryService);
    }

    @Test
    void createProductWithUserIdReachesTheHandler() throws Exception {
        mockMvc.perform(post("/api/products").header(GatewayUserHeaderFilter.USER_ID_HEADER, "user-42")
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isCreated());

        verify(productService).create(any(ProductRequest.class));
    }

    @Test
    void updateProductWithUserIdReachesTheHandler() throws Exception {
        mockMvc.perform(put("/api/products/{id}", ID).header(GatewayUserHeaderFilter.USER_ID_HEADER, "user-42")
                        .contentType(MediaType.APPLICATION_JSON).content(PRODUCT_JSON))
                .andExpect(status().isOk());

        verify(productService).update(eq(ID), any(ProductRequest.class));
    }

    @Test
    void deleteProductWithUserIdReachesTheHandler() throws Exception {
        mockMvc.perform(delete("/api/products/{id}", ID).header(GatewayUserHeaderFilter.USER_ID_HEADER, "user-42"))
                .andExpect(status().isNoContent());

        verify(productService).delete(ID);
    }

    @Test
    void adjustInventoryWithUserIdReachesTheHandler() throws Exception {
        mockMvc.perform(patch("/api/products/{id}/inventory", ID)
                        .header(GatewayUserHeaderFilter.USER_ID_HEADER, "user-42")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"delta\":5}"))
                .andExpect(status().isOk());

        verify(inventoryService).adjust(eq(ID), anyInt());
    }

    @Test
    void createCategoryWithUserIdReachesTheHandler() throws Exception {
        mockMvc.perform(post("/api/categories").header(GatewayUserHeaderFilter.USER_ID_HEADER, "user-42")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Books\"}"))
                .andExpect(status().isCreated());

        verify(categoryService).create(any(CategoryRequest.class));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void readsWorkWithOrWithoutUserId(boolean withHeader) throws Exception {
        when(productService.search(any(ProductFilter.class), any(Pageable.class)))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
        when(categoryService.findAll()).thenReturn(List.of());

        for (MockHttpServletRequestBuilder request : List.of(
                get("/api/products"), get("/api/products/{id}", ID), get("/api/categories"))) {
            if (withHeader) {
                request.header(GatewayUserHeaderFilter.USER_ID_HEADER, "user-42");
            }
            mockMvc.perform(request).andExpect(status().isOk());
        }

        verify(productService).get(ID);
        verify(categoryService).findAll();
    }
}
