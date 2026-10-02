package com.achintha.productservice.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.category.CategoryRepository;
import com.achintha.productservice.events.StoreEvent;
import com.achintha.productservice.events.StoreEventHandler;
import com.achintha.productservice.events.UserEvent;
import com.achintha.productservice.events.UserEventHandler;
import com.achintha.productservice.security.AssistantPermission;
import com.achintha.productservice.security.Role;
import com.achintha.productservice.security.SecurityStateCache;
import com.achintha.productservice.security.UserStatus;
import com.jayway.jsonpath.JsonPath;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base class of the integration tests: the full application against a real PostgreSQL (Testcontainers), an embedded
 * Kafka broker and a WireMock user-service (JWKS, service token, security state), all shared by the whole test run so
 * the Spring context is cached. Schedulers are off; tests call the jobs directly. The clock is a
 * {@link MutableClock}. Tokens are minted with {@link TestKeys}, exactly as user-service would.
 */
@SpringBootTest(properties = {
        "spring.cloud.config.enabled=false",
        "eureka.client.enabled=false",
        "app.scheduling.enabled=false",
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "security.internal.client-secret=" + IntegrationTest.SERVICE_CLIENT_SECRET,
        "logging.level.org.apache.kafka=WARN",
        "logging.level.kafka=WARN",
        "logging.level.state.change.logger=WARN"
})
@AutoConfigureMockMvc
@EmbeddedKafka(topics = {"user-events", "store-events", "order-events", "product-events"}, partitions = 1)
@Import(IntegrationTest.ClockConfig.class)
public abstract class IntegrationTest {

    public static final String SERVICE_CLIENT_SECRET = "test-product-service-secret-123456";

    private static final AtomicLong SEQUENCE = new AtomicLong(ThreadLocalRandom.current().nextLong(1_000_000L));
    private static final PostgreSQLContainer POSTGRES = SharedPostgres.CONTAINER;
    private static final JwtEncoder ENCODER = NimbusJwtEncoder
            .withKeyPair((RSAPublicKey) TestKeys.KEY_PAIR.getPublic(),
                    (RSAPrivateKey) TestKeys.KEY_PAIR.getPrivate())
            .algorithm(SignatureAlgorithm.RS256)
            .jwkPostProcessor(jwk -> jwk.keyID(SharedWireMock.KEY_ID))
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("security.jwt.jwks-uri", SharedWireMock::jwksUri);
        registry.add("spring.cloud.discovery.client.simple.instances.user-service[0].uri",
                SharedWireMock.USER_SERVICE::baseUrl);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected TransactionTemplate transactionTemplate;
    @Autowired
    protected MutableClock clock;
    @Autowired
    protected SecurityStateCache securityStateCache;
    @Autowired
    protected CategoryRepository categoryRepository;
    @Autowired
    protected StoreEventHandler storeEventHandler;
    @Autowired
    protected UserEventHandler userEventHandler;

    @AfterEach
    void resetClock() {
        clock.reset();
    }

    // ------------------------------------------------------------------------------------------------- users

    protected static long next() {
        return SEQUENCE.incrementAndGet();
    }

    /** A user known to the security-state cache (as if user-events had been consumed) with the given status. */
    protected TestUser user(Role role, UserStatus status, UUID storeId, List<String> perms) {
        TestUser user = new TestUser(UUID.randomUUID(), "USR-2610-" + suffix(), role, storeId, perms, 1);
        securityStateCache.apply(user.id(), user.tokenVersion(), status);
        return user;
    }

    protected TestUser customer() {
        return user(Role.ROLE_CUSTOMER, UserStatus.ACTIVE, null, null);
    }

    protected TestUser merchant() {
        return merchant(UserStatus.ACTIVE);
    }

    protected TestUser merchant(UserStatus status) {
        return user(Role.ROLE_MERCHANT, status, UUID.randomUUID(), null);
    }

    protected TestUser assistant(TestUser merchant, AssistantPermission... permissions) {
        return user(Role.ROLE_ASSISTANT, UserStatus.ACTIVE, merchant.storeId(),
                Arrays.stream(permissions).map(Enum::name).toList());
    }

    protected TestUser admin() {
        return user(Role.ROLE_ADMIN, UserStatus.ACTIVE, null, null);
    }

    protected TestUser superAdmin() {
        return user(Role.ROLE_SUPER_ADMIN, UserStatus.ACTIVE, null, null);
    }

    /** A signed access token with exactly the claims user-service issues (section 3.3). */
    protected String tokenFor(TestUser user) {
        return tokenFor(user, "ACTIVE");
    }

    /** @param statusClaim the {@code status} claim (e.g. {@code PENDING_APPROVAL} for an unapproved merchant) */
    protected String tokenFor(TestUser user, String statusClaim) {
        Instant now = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer("user-service")
                .audience(List.of("marketplace"))
                .subject(user.id().toString())
                .issuedAt(now)
                .expiresAt(now.plusSeconds(600))
                .id(UUID.randomUUID().toString())
                .claim("pid", user.publicId())
                .claim("role", user.role().name())
                .claim("status", statusClaim)
                .claim("tv", user.tokenVersion());
        if (user.storeId() != null) {
            claims.claim("storeId", user.storeId().toString());
        }
        if (user.perms() != null) {
            claims.claim("perms", user.perms());
        }
        return encode(claims.build());
    }

    protected String serviceToken() {
        Instant now = clock.instant();
        return encode(JwtClaimsSet.builder()
                .issuer("user-service")
                .audience(List.of("marketplace"))
                .subject("order-service")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .id(UUID.randomUUID().toString())
                .claim("role", Role.ROLE_SERVICE.name())
                .claim("svc", "order-service")
                .build());
    }

    protected static String encode(JwtClaimsSet claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId(SharedWireMock.KEY_ID).type("JWT").build();
        return ENCODER.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    // ---------------------------------------------------------------------------------------------- fixtures

    protected static String suffix() {
        String alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";
        long n = next();
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 6; i++) {
            s.append(alphabet.charAt((int) (n % 32)));
            n /= 32;
        }
        return s.toString();
    }

    /** An active top-level category, created directly. */
    protected Category category() {
        return transactionTemplate.execute(tx -> {
            long n = next();
            Category category = new Category();
            category.setId(UUID.randomUUID());
            category.setSlug("cat-" + n);
            category.setName("Category " + n);
            category.setActive(true);
            category.setCreatedAt(Instant.now());
            category.setUpdatedAt(Instant.now());
            return categoryRepository.save(category);
        });
    }

    /** Store-service announces the merchant's store (StoreVisibilityChanged). @return the store's public id */
    protected String storeVisible(TestUser merchant, boolean visible) {
        String publicId = "STR-2610-" + suffix();
        storeEventHandler.handle(new StoreEvent(UUID.randomUUID(), "StoreVisibilityChanged", clock.instant(),
                merchant.storeId(), publicId, "Store " + publicId, visible, true, "TEST"));
        return publicId;
    }

    /** user-service announces the merchant's status (UserStatusChanged). */
    protected void ownerStatus(TestUser merchant, UserStatus status) {
        userEventHandler.handle(new UserEvent(UUID.randomUUID(), "UserStatusChanged", clock.instant(), merchant.id(),
                merchant.publicId(), Role.ROLE_MERCHANT.name(), status.name(), null, merchant.storeId(), 1L, null));
    }

    /** A simple product's JSON (one default variant). */
    protected static String simpleProductJson(String name, Category category, String sku, String price, int stock) {
        return """
                {"name":"%s","description":"A <b>fine</b> thing","categorySlug":"%s","sku":"%s","listPrice":%s,
                 "initialStock":%d,"imageKeys":["products/%s.png"]}
                """.formatted(name, category.getSlug(), sku, price, stock, sku);
    }

    /** Creates a product through the merchant API. @return the JSON response */
    protected String createProduct(TestUser merchant, String json) throws Exception {
        return mockMvc.perform(json(withBearer(post("/api/merchant/products"), tokenFor(merchant)), json))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
    }

    protected void activate(TestUser merchant, String productPublicId) throws Exception {
        mockMvc.perform(json(withBearer(post("/api/merchant/products/" + productPublicId + "/status"),
                        tokenFor(merchant)), "{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
    }

    /** A visible store with one active simple product. */
    protected Listing listing(Category category, String name, String price, int stock) throws Exception {
        TestUser merchant = merchant();
        String store = storeVisible(merchant, true);
        String sku = "SKU-" + next();
        String body = createProduct(merchant, simpleProductJson(name, category, sku, price, stock));
        String product = read(body, "$.publicId");
        activate(merchant, product);
        return new Listing(merchant, store, product, read(body, "$.variants[0].publicId"));
    }

    /** A merchant's visible product: its store public id, product and (first) variant public ids. */
    protected record Listing(TestUser merchant, String store, String product, String variant) {
    }

    // -------------------------------------------------------------------------------------------------- HTTP

    protected static MockHttpServletRequestBuilder withBearer(MockHttpServletRequestBuilder request, String token) {
        return token == null ? request : request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected static MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, String body) {
        return request.contentType(MediaType.APPLICATION_JSON).content(body);
    }

    protected static String read(String json, String path) {
        Object value = JsonPath.read(json, path);
        return value == null ? null : value.toString();
    }
}
