package com.achintha.productservice.security;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.support.IntegrationTest;
import com.achintha.productservice.support.TestUser;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * Role-by-endpoint authorization matrix (section 11): every endpoint is called by every role and anonymously.
 * "Allowed" calls target harmless or non-existent resources, so 404 (and the odd 400 from business rules) proves the
 * call got past authorization; 401/403 prove it did not. Assistants pass {@code /api/merchant/**} routes only with
 * the route's permission.
 */
class AuthorizationMatrixTest extends IntegrationTest {

    enum Who { ANONYMOUS, CUSTOMER, MERCHANT, ASSISTANT, ASSISTANT_NO_PERMS, ADMIN, SUPER_ADMIN, SERVICE }

    private static final String MISSING = "XXX-2610-ZZZZZZ";

    private static Map<Who, String> tokens;
    private static Listing listing;
    private static Category category;

    @BeforeEach
    void fixtures() throws Exception {
        if (tokens != null) {
            return;
        }
        category = category();
        listing = listing(category, "Matrix Lamp", "1500.00", 1_000_000);
        TestUser merchant = listing.merchant();
        tokens = new EnumMap<>(Who.class);
        tokens.put(Who.CUSTOMER, tokenFor(customer()));
        tokens.put(Who.MERCHANT, tokenFor(merchant));
        tokens.put(Who.ASSISTANT, tokenFor(assistant(merchant, AssistantPermission.values())));
        tokens.put(Who.ASSISTANT_NO_PERMS, tokenFor(assistant(merchant, AssistantPermission.ORDER_VIEW)));
        tokens.put(Who.ADMIN, tokenFor(admin()));
        tokens.put(Who.SUPER_ADMIN, tokenFor(superAdmin()));
        tokens.put(Who.SERVICE, serviceToken());
    }

    record Endpoint(HttpMethod method, String path, Supplier<String> body, Map<Who, Integer> allowed) {

        @Override
        public String toString() {
            return method + " " + path;
        }
    }

    static Stream<Arguments> matrix() {
        List<Arguments> arguments = new ArrayList<>();
        for (Endpoint endpoint : endpoints()) {
            for (Who who : Who.values()) {
                int expected = endpoint.allowed().getOrDefault(who, who == Who.ANONYMOUS ? 401 : 403);
                arguments.add(Arguments.of(endpoint, who, expected));
            }
        }
        return arguments.stream();
    }

    @ParameterizedTest(name = "{0} as {1} -> {2}")
    @MethodSource("matrix")
    void endpointAnswersAsExpected(Endpoint endpoint, Who who, int expected) throws Exception {
        MockHttpServletRequestBuilder request = request(endpoint.method(), fill(endpoint.path()));
        if (endpoint.body() != null) {
            request = json(request, fill(endpoint.body().get()));
        }
        MockHttpServletResponse response = mockMvc.perform(withBearer(request, tokens.get(who)))
                .andReturn().getResponse();
        Assertions.assertThat(response.getStatus())
                .as("%s as %s: %s", endpoint, who, response.getContentAsString())
                .isEqualTo(expected);
    }

    private static String fill(String text) {
        return text.replace("{product}", listing.product())
                .replace("{variant}", listing.variant())
                .replace("{store}", listing.store())
                .replace("{category}", category.getSlug());
    }

    // ------------------------------------------------------------------------------------------------ endpoints

    private static List<Endpoint> endpoints() {
        Map<Who, Integer> everyone = only(200, Who.values());
        Map<Who, Integer> merchantSide = only(200, Who.MERCHANT, Who.ASSISTANT);
        Map<Who, Integer> merchantSide404 = only(404, Who.MERCHANT, Who.ASSISTANT);
        Map<Who, Integer> admins = only(200, Who.ADMIN, Who.SUPER_ADMIN);
        Map<Who, Integer> admins404 = only(404, Who.ADMIN, Who.SUPER_ADMIN);
        Map<Who, Integer> service404 = only(404, Who.SERVICE);
        Supplier<String> reason = () -> "{\"reason\":\"matrix test\"}";
        Supplier<String> product = () -> simpleProductJson("Matrix " + next(), category, "MX-" + next(), "99.00", 1);
        Supplier<String> discount = () -> "{\"scope\":\"PRODUCT\",\"productPublicId\":\"" + MISSING
                + "\",\"type\":\"PERCENT\",\"value\":10}";

        return List.of(
                // Public
                new Endpoint(HttpMethod.GET, "/api/public/categories", null, everyone),
                new Endpoint(HttpMethod.GET, "/api/public/products", null, everyone),
                new Endpoint(HttpMethod.GET, "/api/public/products?q=lamp&sort=price,desc&inStock=true", null,
                        everyone),
                new Endpoint(HttpMethod.GET, "/api/public/products/{product}", null, everyone),
                new Endpoint(HttpMethod.GET, "/api/public/products/" + MISSING, null, only(404, Who.values())),
                new Endpoint(HttpMethod.GET, "/api/public/products/{product}/reviews", null, everyone),

                // Merchant: products (PRODUCT_EDIT; reads also STOCK_EDIT / DISCOUNT_MANAGE)
                new Endpoint(HttpMethod.GET, "/api/merchant/products", null, merchantSide),
                new Endpoint(HttpMethod.GET, "/api/merchant/products/{product}", null, merchantSide),
                new Endpoint(HttpMethod.POST, "/api/merchant/products", product,
                        only(201, Who.MERCHANT, Who.ASSISTANT)),
                new Endpoint(HttpMethod.PUT, "/api/merchant/products/" + MISSING, product, merchantSide404),
                new Endpoint(HttpMethod.POST, "/api/merchant/products/" + MISSING + "/status",
                        () -> "{\"status\":\"ACTIVE\"}", merchantSide404),
                new Endpoint(HttpMethod.DELETE, "/api/merchant/products/" + MISSING, null, merchantSide404),

                // Merchant: stock (STOCK_EDIT)
                new Endpoint(HttpMethod.PATCH, "/api/merchant/variants/" + MISSING + "/stock",
                        () -> "{\"mode\":\"SET\",\"quantity\":5,\"reason\":\"count\"}", merchantSide404),

                // Merchant: discounts (DISCOUNT_MANAGE)
                new Endpoint(HttpMethod.GET, "/api/merchant/discounts", null, merchantSide),
                new Endpoint(HttpMethod.GET, "/api/merchant/discounts/" + MISSING, null, merchantSide404),
                new Endpoint(HttpMethod.POST, "/api/merchant/discounts", discount, merchantSide404),
                new Endpoint(HttpMethod.PUT, "/api/merchant/discounts/" + MISSING, discount, merchantSide404),
                new Endpoint(HttpMethod.DELETE, "/api/merchant/discounts/" + MISSING, null, merchantSide404),

                // Merchant: review replies (REVIEW_REPLY)
                new Endpoint(HttpMethod.GET, "/api/merchant/products/reviews", null, merchantSide),
                new Endpoint(HttpMethod.POST, "/api/merchant/products/reviews/" + MISSING + "/reply",
                        () -> "{\"text\":\"Thanks!\"}", merchantSide404),

                // Customer
                new Endpoint(HttpMethod.POST, "/api/customer/products/{product}/reviews",
                        () -> "{\"orderPublicId\":\"ORD-2610-ZZZZZZ\",\"rating\":5}", only(404, Who.CUSTOMER)),

                // Admin (and super admin)
                new Endpoint(HttpMethod.GET, "/api/admin/categories", null, admins),
                new Endpoint(HttpMethod.GET, "/api/admin/categories/{category}", null, admins),
                new Endpoint(HttpMethod.POST, "/api/admin/categories",
                        () -> "{\"slug\":\"matrix-" + next() + "\",\"name\":\"Matrix " + next() + "\"}",
                        only(201, Who.ADMIN, Who.SUPER_ADMIN)),
                new Endpoint(HttpMethod.PUT, "/api/admin/categories/no-such-category",
                        () -> "{\"slug\":\"no-such-category\",\"name\":\"Nope " + next() + "\"}", admins404),
                new Endpoint(HttpMethod.DELETE, "/api/admin/categories/no-such-category", null, admins404),
                new Endpoint(HttpMethod.GET, "/api/admin/products?store={store}", null, admins),
                new Endpoint(HttpMethod.GET, "/api/admin/products/{product}", null, admins),
                new Endpoint(HttpMethod.GET, "/api/admin/reviews?product={product}", null, admins),
                new Endpoint(HttpMethod.POST, "/api/admin/reviews/" + MISSING + "/hide", reason, admins404),
                new Endpoint(HttpMethod.POST, "/api/admin/reviews/" + MISSING + "/unhide", reason, admins404),

                // Internal (service tokens only)
                new Endpoint(HttpMethod.POST, "/internal/variants/quote",
                        () -> "{\"variantPublicIds\":[\"{variant}\"]}", only(200, Who.SERVICE)),
                new Endpoint(HttpMethod.POST, "/internal/reservations",
                        () -> "{\"orderRef\":\"ORD-MATRIX-" + next() + "\",\"expiresAt\":\"2999-01-01T00:00:00Z\","
                                + "\"lines\":[{\"variantPublicId\":\"{variant}\",\"quantity\":1}]}",
                        only(201, Who.SERVICE)),
                new Endpoint(HttpMethod.GET, "/internal/reservations/NO-SUCH-ORDER", null, service404),
                new Endpoint(HttpMethod.PUT, "/internal/reservations/NO-SUCH-ORDER", () -> "{\"lines\":[]}",
                        service404),
                new Endpoint(HttpMethod.POST, "/internal/reservations/NO-SUCH-ORDER/commit", null, service404),
                new Endpoint(HttpMethod.POST, "/internal/reservations/NO-SUCH-ORDER/release", null, service404),

                // Deny by default: unknown paths, non-GET public calls and the removed endpoints
                new Endpoint(HttpMethod.POST, "/api/public/products", () -> "{}", Map.of()),
                new Endpoint(HttpMethod.GET, "/api/unknown", null, Map.of()),
                new Endpoint(HttpMethod.GET, "/api/products", null, Map.of()),
                new Endpoint(HttpMethod.PATCH, "/api/products/{product}/inventory", () -> "{\"delta\":-1}", Map.of()),
                new Endpoint(HttpMethod.POST, "/api/categories", () -> "{\"name\":\"Old\"}", Map.of()));
    }

    private static Map<Who, Integer> only(int status, Who... who) {
        return Arrays.stream(who).collect(Collectors.toMap(w -> w, w -> status));
    }
}
