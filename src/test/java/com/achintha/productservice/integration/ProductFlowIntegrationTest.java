package com.achintha.productservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.category.Category;
import com.achintha.productservice.outbox.OutboxRelay;
import com.achintha.productservice.product.ProductRepository;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.review.ReviewEligibilityRepository;
import com.achintha.productservice.security.AssistantPermission;
import com.achintha.productservice.security.UserStatus;
import com.achintha.productservice.store.StoreVisibilityRepository;
import com.achintha.productservice.support.IntegrationTest;
import com.achintha.productservice.support.TestUser;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * The whole life of a product: admin category, merchant aggregate, store visibility from store-events, public
 * search, order-service quote / reserve / commit, review eligibility from order-events, review, reply, moderation,
 * a silent ban from user-events, and ProductChanged relayed to Kafka from the outbox. Events travel through the
 * embedded broker, exactly as in production.
 */
class ProductFlowIntegrationTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Autowired
    private KafkaTemplate<String, String> kafkaTemplate;
    @Autowired
    private EmbeddedKafkaBroker broker;
    @Autowired
    private OutboxRelay relay;
    @Autowired
    private StoreVisibilityRepository storeVisibilityRepository;
    @Autowired
    private ReviewEligibilityRepository eligibilityRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private VariantRepository variantRepository;

    @Test
    void productLifecycleAcrossServices() throws Exception {
        // --- admin: category
        String slug = "lamps-" + next();
        mockMvc.perform(json(withBearer(post("/api/admin/categories"), tokenFor(admin())),
                        "{\"slug\":\"" + slug + "\",\"name\":\"Lamps " + next() + "\"}"))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/public/categories"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.slug == '" + slug + "')]").exists());
        Category category = categoryRepository.findBySlug(slug).orElseThrow();

        // --- merchant: product with two variants, activated
        TestUser merchant = merchant();
        String body = createProduct(merchant, """
                {"name":"Brass Desk Lamp","description":"Warm light","categorySlug":"%s",
                 "options":[{"name":"Finish","values":["Brass","Black"]}],
                 "variants":[{"sku":"LAMP-BR-%d","attributes":{"Finish":"Brass"},"listPrice":4500.00,"initialStock":5},
                             {"sku":"LAMP-BK-%d","attributes":{"Finish":"Black"},"listPrice":4200.00,"initialStock":0}]}
                """.formatted(category.getSlug(), next(), next()));
        String product = read(body, "$.publicId");
        String brass = read(body, "$.variants[0].publicId");
        activate(merchant, product);
        mockMvc.perform(get("/api/public/products/" + product)).andExpect(status().isNotFound());

        // --- store-service: the store becomes visible (store-events through Kafka)
        String storePublicId = "STR-2610-" + suffix();
        send("store-events", merchant.storeId(), """
                {"eventId":"%s","eventType":"StoreVisibilityChanged","occurredAt":"%s","aggregateId":"%s",
                 "storeId":"%s","storePublicId":"%s","storeName":"Lamp House","published":true,
                 "acceptingOrders":true,"visible":true,"codEnabled":true,"change":"CREATED"}
                """.formatted(UUID.randomUUID(), Instant.now(), merchant.storeId(), merchant.storeId(),
                storePublicId));
        await().atMost(WAIT).until(() -> storeVisibilityRepository.findById(merchant.storeId())
                .map(s -> s.isPubliclyVisible()).orElse(false));

        mockMvc.perform(get("/api/public/products").param("q", "brass desk").param("category", slug))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].publicId").value(product))
                .andExpect(jsonPath("$.content[0].store.publicId").value(storePublicId))
                .andExpect(jsonPath("$.content[0].store.name").value("Lamp House"))
                .andExpect(jsonPath("$.content[0].price").value(4200.00))
                .andExpect(jsonPath("$.content[0].inStock").value(true));
        mockMvc.perform(get("/api/public/products/" + product))
                .andExpect(jsonPath("$.variants.length()").value(2))
                .andExpect(jsonPath("$.variants[1].inStock").value(false))
                .andExpect(jsonPath("$.options[0].values[1]").value("Black"));

        // --- order-service: quote, reserve, commit
        mockMvc.perform(json(withBearer(post("/internal/variants/quote"), serviceToken()),
                        "{\"variantPublicIds\":[\"" + brass + "\",\"VAR-2610-ZZZZZZ\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].storeId").value(merchant.storeId().toString()))
                .andExpect(jsonPath("$.items[0].storePublicId").value(storePublicId))
                .andExpect(jsonPath("$.items[0].productName").value("Brass Desk Lamp"))
                .andExpect(jsonPath("$.items[0].variantName").value("Finish: Brass"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(4500.00))
                .andExpect(jsonPath("$.items[0].availableQuantity").value(5))
                .andExpect(jsonPath("$.items[0].codAllowed").value(true))
                .andExpect(jsonPath("$.items[0].purchasable").value(true))
                .andExpect(jsonPath("$.missing[0]").value("VAR-2610-ZZZZZZ"));
        String orderPublicId = "ORD-2610-" + suffix();
        UUID orderId = UUID.randomUUID();
        UUID variantId = variantRepository.findByPublicId(brass).orElseThrow().getId();
        mockMvc.perform(json(withBearer(post("/internal/reservations"), serviceToken()),
                        "{\"orderRef\":\"" + orderPublicId + "\",\"expiresAt\":\"" + Instant.now().plusSeconds(86400)
                                + "\",\"lines\":[{\"variantId\":\"" + variantId + "\",\"quantity\":2}]}"))
                .andExpect(status().isCreated());
        mockMvc.perform(withBearer(post("/internal/reservations/" + orderPublicId + "/commit"), serviceToken()))
                .andExpect(status().isOk());
        mockMvc.perform(withBearer(get("/api/merchant/products/" + product), tokenFor(merchant)))
                .andExpect(jsonPath("$.variants[0].stock.quantityAvailable").value(3))
                .andExpect(jsonPath("$.variants[0].stock.reservedQuantity").value(0));

        // --- order-service: OrderCompleted (delivered twice: at-least-once) makes the item reviewable
        TestUser customer = customer();
        String completed = """
                {"eventId":"%s","eventType":"OrderCompleted","occurredAt":"%s","orderId":"%s","orderPublicId":"%s",
                 "storeId":"%s","customerId":"%s","status":"COMPLETED",
                 "lines":[{"variantId":"%s","variantPublicId":"%s","quantity":2}]}
                """.formatted(UUID.randomUUID(), Instant.now(), orderId, orderPublicId, merchant.storeId(),
                customer.id(), variantId, brass);
        send("order-events", orderId, completed);
        send("order-events", orderId, completed);
        await().atMost(WAIT).until(() -> !eligibilityRepository.findAllByOrderId(orderId).isEmpty());
        assertThat(eligibilityRepository.findAllByOrderId(orderId)).hasSize(1);

        // --- customer: review (once), other customers / orders are not eligible
        String review = "{\"orderPublicId\":\"" + orderPublicId + "\",\"rating\":4,"
                + "\"text\":\"Lovely <script>alert(1)</script>light\",\"attachmentKeys\":[\"reviews/1.jpg\"]}";
        String created = mockMvc.perform(json(withBearer(post("/api/customer/products/" + product + "/reviews"),
                        tokenFor(customer)), review))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.text").value("Lovely light"))
                .andExpect(jsonPath("$.variantLabel").value("Finish: Brass"))
                .andExpect(jsonPath("$.attachmentUrls[0]").value("https://images.example.invalid/reviews/1.jpg"))
                .andReturn().getResponse().getContentAsString();
        String reviewId = read(created, "$.publicId");
        mockMvc.perform(json(withBearer(post("/api/customer/products/" + product + "/reviews"),
                        tokenFor(customer)), review))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REVIEW_ALREADY_EXISTS"));
        mockMvc.perform(json(withBearer(post("/api/customer/products/" + product + "/reviews"),
                        tokenFor(customer())), review))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("REVIEW_NOT_ELIGIBLE"));
        TestUser banned = user(com.achintha.productservice.security.Role.ROLE_CUSTOMER, UserStatus.BANNED, null,
                null);
        mockMvc.perform(json(withBearer(post("/api/customer/products/" + product + "/reviews"), tokenFor(banned)),
                        review))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ACCOUNT_BANNED"));
        assertThat(productRepository.findByPublicId(product).orElseThrow().getRatingAvg())
                .isEqualByComparingTo("4.00");

        // --- merchant: reply once (assistants need REVIEW_REPLY), another store cannot
        String reply = "{\"text\":\"Thank you!\"}";
        mockMvc.perform(json(withBearer(post("/api/merchant/products/reviews/" + reviewId + "/reply"),
                        tokenFor(assistant(merchant, AssistantPermission.PRODUCT_EDIT))), reply))
                .andExpect(status().isForbidden());
        mockMvc.perform(json(withBearer(post("/api/merchant/products/reviews/" + reviewId + "/reply"),
                        tokenFor(merchant())), reply))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(withBearer(post("/api/merchant/products/reviews/" + reviewId + "/reply"),
                        tokenFor(assistant(merchant, AssistantPermission.REVIEW_REPLY))), reply))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reply.text").value("Thank you!"));
        mockMvc.perform(json(withBearer(post("/api/merchant/products/reviews/" + reviewId + "/reply"),
                        tokenFor(merchant)), reply))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("REPLY_ALREADY_EXISTS"));
        mockMvc.perform(get("/api/public/products/" + product + "/reviews"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].rating").value(4))
                .andExpect(jsonPath("$.content[0].reply.text").value("Thank you!"))
                .andExpect(jsonPath("$.content[0].orderPublicId").doesNotExist());

        // --- admin: hide (rating drops out) and unhide
        String admin = tokenFor(admin());
        mockMvc.perform(json(withBearer(post("/api/admin/reviews/" + reviewId + "/hide"), admin),
                        "{\"reason\":\"spam\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(true));
        mockMvc.perform(get("/api/public/products/" + product + "/reviews"))
                .andExpect(jsonPath("$.totalElements").value(0));
        assertThat(productRepository.findByPublicId(product).orElseThrow().getRatingCount()).isZero();
        mockMvc.perform(json(withBearer(post("/api/admin/reviews/" + reviewId + "/unhide"), admin),
                        "{\"reason\":\"appeal upheld\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.hidden").value(false));
        mockMvc.perform(get("/api/public/products").param("category", slug).param("minRating", "4"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mockMvc.perform(withBearer(get("/api/admin/products").param("store", storePublicId), admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content[0].publicId").value(product));

        // --- user-service: the merchant is banned silently (BAN_GRACE): listings leave search at once
        send("user-events", merchant.id(), """
                {"eventId":"%s","eventType":"UserStatusChanged","occurredAt":"%s","userId":"%s","publicId":"%s",
                 "role":"ROLE_MERCHANT","status":"BAN_GRACE","previousStatus":"ACTIVE","storeId":"%s","tokenVersion":1}
                """.formatted(UUID.randomUUID(), Instant.now(), merchant.id(), merchant.publicId(),
                merchant.storeId()));
        await().atMost(WAIT).until(() -> "BAN_GRACE".equals(storeVisibilityRepository.findById(merchant.storeId())
                .map(s -> s.getOwnerStatus()).orElse(null)));
        mockMvc.perform(get("/api/public/products/" + product)).andExpect(status().isNotFound());
        // ... while the merchant sees nothing unusual
        mockMvc.perform(withBearer(get("/api/merchant/products/" + product), tokenFor(merchant)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

        // --- outbox: ProductChanged is relayed to product-events, keyed by the product id
        UUID productId = productRepository.findByPublicId(product).orElseThrow().getId();
        try (Consumer<String, String> consumer = consumer()) {
            broker.consumeFromAnEmbeddedTopic(consumer, "product-events");
            while (relay.publishPending() > 0) {
                // drain
            }
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + WAIT.toMillis();
            while (records.stream().filter(r -> productId.toString().equals(r.key())).count() < 2
                    && System.currentTimeMillis() < deadline) {
                KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(2)).forEach(records::add);
            }
            List<String> mine = records.stream().filter(r -> productId.toString().equals(r.key()))
                    .map(ConsumerRecord::value).toList();
            assertThat(mine).hasSize(2);
            assertThat(mine.get(0)).contains("\"eventType\":\"ProductChanged\"", "\"change\":\"CREATED\"",
                    "\"productPublicId\":\"" + product + "\"", "\"eventId\"", "\"occurredAt\"");
            assertThat(mine.get(1)).contains("\"change\":\"STATUS_CHANGED\"", "\"status\":\"ACTIVE\"");
        }
    }

    private void send(String topic, UUID key, String json) throws Exception {
        kafkaTemplate.send(topic, key.toString(), json.replace("\n", "")).get();
    }

    private Consumer<String, String> consumer() {
        Map<String, Object> props = KafkaTestUtils.consumerProps(broker, "flow-test-" + UUID.randomUUID(), false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }
}
