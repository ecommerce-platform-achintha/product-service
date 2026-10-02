package com.achintha.productservice.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.achintha.productservice.audit.AuditLogRepository;
import com.achintha.productservice.category.Category;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.support.IntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Stock: the merchant stock edit (audited, optimistic locking) and the internal reservation API (all-or-nothing,
 * idempotent per orderRef, quote may only reduce, commit / release, safety net).
 */
class InventoryTest extends IntegrationTest {

    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private VariantRepository variantRepository;
    @Autowired
    private StockReservationRepository reservationRepository;
    @Autowired
    private ReservationCleanupJob cleanupJob;
    @Autowired
    private AuditLogRepository auditLogRepository;

    private Category category;
    private Listing lamp;

    @BeforeEach
    void setUp() throws Exception {
        category = category();
        lamp = listing(category, "Stock Lamp", "1000.00", 10);
    }

    private Inventory stock(String variantPublicId) {
        UUID id = variantRepository.findByPublicId(variantPublicId).orElseThrow().getId();
        return inventoryRepository.findById(id).orElseThrow();
    }

    private ResultActions reserve(String orderRef, String linesJson) throws Exception {
        String body = "{\"orderRef\":\"" + orderRef + "\",\"expiresAt\":\"" + clock.instant().plus(Duration.ofHours(24))
                + "\",\"lines\":" + linesJson + "}";
        return mockMvc.perform(json(withBearer(post("/internal/reservations"), serviceToken()), body));
    }

    private static String line(String variant, int quantity) {
        return "{\"variantPublicId\":\"" + variant + "\",\"quantity\":" + quantity + "}";
    }

    private ResultActions call(String method, String orderRef) throws Exception {
        return mockMvc.perform(withBearer(post("/internal/reservations/" + orderRef + "/" + method), serviceToken()));
    }

    // ----------------------------------------------------------------------------------------------- reserve

    @Test
    void reserveIsIdempotentPerOrderRef() throws Exception {
        String ref = "ORD-2610-" + suffix();
        reserve(ref, "[" + line(lamp.variant(), 3) + "]")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("HELD"))
                .andExpect(jsonPath("$.lines[0].quantity").value(3));
        // A retry with the same lines changes nothing and answers 200
        reserve(ref, "[" + line(lamp.variant(), 3) + "]")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HELD"));
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(7);
        assertThat(stock(lamp.variant()).getReservedQuantity()).isEqualTo(3);
        // The same orderRef with different lines is a conflict
        reserve(ref, "[" + line(lamp.variant(), 4) + "]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("RESERVATION_CONFLICT"));
    }

    @Test
    void reserveIsAllOrNothing() throws Exception {
        TestVariant second = secondVariantOfSameStore(2);
        String ref = "ORD-2610-" + suffix();
        reserve(ref, "[" + line(lamp.variant(), 5) + "," + line(second.publicId(), 3) + "]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        // Nothing was held: the first line was rolled back with the second
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(10);
        assertThat(stock(lamp.variant()).getReservedQuantity()).isZero();
        assertThat(reservationRepository.findAllByOrderRefOrderByIdAsc(ref)).isEmpty();
    }

    @Test
    void onlySellableVariantsOfOneVisibleStoreCanBeReserved() throws Exception {
        // Another store's variant in the same order
        Listing other = listing(category, "Other Lamp", "10.00", 5);
        reserve("ORD-2610-" + suffix(), "[" + line(lamp.variant(), 1) + "," + line(other.variant(), 1) + "]")
                .andExpect(status().isBadRequest());
        // Store no longer visible (e.g. merchant in ban grace)
        ownerStatus(other.merchant(), com.achintha.productservice.security.UserStatus.BAN_GRACE);
        reserve("ORD-2610-" + suffix(), "[" + line(other.variant(), 1) + "]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VARIANT_NOT_AVAILABLE"));
        // Archived product
        mockMvc.perform(json(withBearer(post("/api/merchant/products/" + lamp.product() + "/status"),
                tokenFor(lamp.merchant())), "{\"status\":\"ARCHIVED\"}")).andExpect(status().isOk());
        reserve("ORD-2610-" + suffix(), "[" + line(lamp.variant(), 1) + "]")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("VARIANT_NOT_AVAILABLE"));
    }

    // ------------------------------------------------------------------------------------------------ adjust

    @Test
    void quoteAdjustmentOnlyReducesAndReleasesTheDifference() throws Exception {
        TestVariant second = secondVariantOfSameStore(5);
        String ref = "ORD-2610-" + suffix();
        reserve(ref, "[" + line(lamp.variant(), 4) + "," + line(second.publicId(), 2) + "]")
                .andExpect(status().isCreated());

        String increase = "{\"lines\":[" + line(lamp.variant(), 5) + "]}";
        mockMvc.perform(json(withBearer(put("/internal/reservations/" + ref), serviceToken()), increase))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RESERVATION_ADJUST_NOT_ALLOWED"));
        String addLine = "{\"lines\":[" + line(lamp.variant(), 1) + ",{\"variantId\":\"" + UUID.randomUUID()
                + "\",\"quantity\":1}]}";
        mockMvc.perform(json(withBearer(put("/internal/reservations/" + ref), serviceToken()), addLine))
                .andExpect(status().isBadRequest()) // a quote cannot add lines
                .andExpect(jsonPath("$.code").value("RESERVATION_ADJUST_NOT_ALLOWED"));

        // Keep 1 of the lamp, drop the second line (left out)
        Instant newExpiry = clock.instant().plus(Duration.ofHours(48));
        String reduce = "{\"lines\":[" + line(lamp.variant(), 1) + "],\"expiresAt\":\"" + newExpiry + "\"}";
        for (int i = 0; i < 2; i++) { // the same adjustment twice is harmless
            mockMvc.perform(json(withBearer(put("/internal/reservations/" + ref), serviceToken()), reduce))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("HELD"))
                    .andExpect(jsonPath("$.lines[0].quantity").value(1))
                    .andExpect(jsonPath("$.lines[1].status").value("RELEASED"))
                    .andExpect(jsonPath("$.lines[1].releaseReason").value("ADJUSTED"));
        }
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(9);
        assertThat(stock(lamp.variant()).getReservedQuantity()).isEqualTo(1);
        assertThat(stock(second.publicId()).getQuantityAvailable()).isEqualTo(5);
        assertThat(stock(second.publicId()).getReservedQuantity()).isZero();
    }

    // ---------------------------------------------------------------------------------------- commit/release

    @Test
    void commitTakesTheStockOnceAndCannotBeReleased() throws Exception {
        String ref = "ORD-2610-" + suffix();
        reserve(ref, "[" + line(lamp.variant(), 2) + "]").andExpect(status().isCreated());
        call("commit", ref).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMMITTED"));
        call("commit", ref).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMMITTED"));
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(8);
        assertThat(stock(lamp.variant()).getReservedQuantity()).isZero();

        call("release", ref).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_COMMITTED"));
        mockMvc.perform(json(withBearer(put("/internal/reservations/" + ref), serviceToken()),
                        "{\"lines\":[]}"))
                .andExpect(status().isConflict());
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(8);
    }

    @Test
    void releaseReturnsTheStockOnceAndCannotBeCommittedAfterwards() throws Exception {
        String ref = "ORD-2610-" + suffix();
        reserve(ref, "[" + line(lamp.variant(), 6) + "]").andExpect(status().isCreated());
        call("release", ref).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        call("release", ref).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("RELEASED"));
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(10);
        assertThat(stock(lamp.variant()).getReservedQuantity()).isZero();

        call("commit", ref).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RESERVATION_RELEASED"));
        mockMvc.perform(withBearer(get("/internal/reservations/" + ref), serviceToken()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.lines[0].releaseReason").value("RELEASED"));
    }

    @Test
    void safetyNetReleasesHoldsAnHourPastTheirExpiry() throws Exception {
        String ref = "ORD-2610-" + suffix();
        String body = "{\"orderRef\":\"" + ref + "\",\"expiresAt\":\"" + clock.instant().plus(Duration.ofMinutes(30))
                + "\",\"lines\":[" + line(lamp.variant(), 4) + "]}";
        mockMvc.perform(json(withBearer(post("/internal/reservations"), serviceToken()), body))
                .andExpect(status().isCreated());

        clock.advance(Duration.ofMinutes(80)); // expired 50 min ago: still within the 1 h grace
        cleanupJob.releaseExpired();
        assertThat(stock(lamp.variant()).getReservedQuantity()).isEqualTo(4);

        clock.advance(Duration.ofMinutes(15)); // now 65 min past expiry
        assertThat(cleanupJob.releaseExpired()).isGreaterThanOrEqualTo(1);
        assertThat(stock(lamp.variant()).getQuantityAvailable()).isEqualTo(10);
        assertThat(stock(lamp.variant()).getReservedQuantity()).isZero();
        assertThat(reservationRepository.findAllByOrderRefOrderByIdAsc(ref))
                .allSatisfy(r -> assertThat(r.getReleaseReason()).isEqualTo(ReleaseReason.EXPIRED));
    }

    // -------------------------------------------------------------------------------------------- stock edit

    @Test
    void merchantStockEditSetsOrAddsAndIsAudited() throws Exception {
        String token = tokenFor(lamp.merchant());
        String url = "/api/merchant/variants/" + lamp.variant() + "/stock";
        mockMvc.perform(json(withBearer(patch(url), token), "{\"mode\":\"SET\",\"quantity\":25,\"reason\":\"count\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantityAvailable").value(25));
        mockMvc.perform(json(withBearer(patch(url), token),
                        "{\"mode\":\"DELTA\",\"quantity\":-5,\"reason\":\"damaged <script>\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.quantityAvailable").value(20));
        mockMvc.perform(json(withBearer(patch(url), token),
                        "{\"mode\":\"DELTA\",\"quantity\":-21,\"reason\":\"too many\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("INSUFFICIENT_STOCK"));
        mockMvc.perform(json(withBearer(patch(url), token), "{\"mode\":\"SET\",\"quantity\":1}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));

        // Held units are not touched by an edit; a stale expectedVersion is refused
        reserve("ORD-2610-" + suffix(), "[" + line(lamp.variant(), 2) + "]").andExpect(status().isCreated());
        Inventory current = stock(lamp.variant());
        assertThat(current.getQuantityAvailable()).isEqualTo(18);
        assertThat(current.getReservedQuantity()).isEqualTo(2);
        mockMvc.perform(json(withBearer(patch(url), token), "{\"mode\":\"SET\",\"quantity\":30,\"reason\":\"x\","
                        + "\"expectedVersion\":" + (current.getVersion() - 1) + "}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CONCURRENT_MODIFICATION"));
        mockMvc.perform(json(withBearer(patch(url), token), "{\"mode\":\"SET\",\"quantity\":30,\"reason\":\"x\","
                        + "\"expectedVersion\":" + current.getVersion() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantityAvailable").value(30))
                .andExpect(jsonPath("$.reservedQuantity").value(2));

        assertThat(auditLogRepository.findAllByTargetTypeAndTargetIdOrderByIdAsc("VARIANT", lamp.variant()))
                .extracting(e -> e.getAction())
                .containsExactly("STOCK_SET", "STOCK_ADJUSTED", "STOCK_SET");
        assertThat(auditLogRepository.findAllByTargetTypeAndTargetIdOrderByIdAsc("VARIANT", lamp.variant()).get(1)
                .getReason()).isEqualTo("damaged");
    }

    // ------------------------------------------------------------------------------------------------ helpers

    record TestVariant(String publicId) {
    }

    /** Turns the lamp into a two-variant product (Colour: Red with 10 units, Blue with {@code stock}). */
    private TestVariant secondVariantOfSameStore(int stock) throws Exception {
        String sku = read(mockMvc.perform(withBearer(get("/api/merchant/products/" + lamp.product()),
                tokenFor(lamp.merchant()))).andReturn().getResponse().getContentAsString(), "$.variants[0].sku");
        String body = """
                {"name":"Stock Lamp","categorySlug":"%s","options":[{"name":"Colour","values":["Red","Blue"]}],
                 "variants":[{"publicId":"%s","sku":"%s","attributes":{"Colour":"Red"},"listPrice":1000.00},
                             {"sku":"%s","attributes":{"Colour":"Blue"},"listPrice":1100.00,"initialStock":%d}]}
                """.formatted(category.getSlug(), lamp.variant(), sku, "BLUE-" + next(), stock);
        String response = mockMvc.perform(json(withBearer(put("/api/merchant/products/" + lamp.product()),
                        tokenFor(lamp.merchant())), body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return new TestVariant(read(response, "$.variants[1].publicId"));
    }
}
