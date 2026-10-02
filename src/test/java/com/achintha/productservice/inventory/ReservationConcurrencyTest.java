package com.achintha.productservice.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.achintha.productservice.exception.InsufficientStockException;
import com.achintha.productservice.inventory.ReservationDtos.LineRequest;
import com.achintha.productservice.inventory.ReservationDtos.ReserveRequest;
import com.achintha.productservice.product.VariantRepository;
import com.achintha.productservice.support.IntegrationTest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Many orders race for the same stock: the conditional updates must never oversell, and replays of one order must
 * never hold twice.
 */
class ReservationConcurrencyTest extends IntegrationTest {

    private static final int STOCK = 10;
    private static final int BUYERS = 40;

    @Autowired
    private ReservationService reservationService;
    @Autowired
    private InventoryRepository inventoryRepository;
    @Autowired
    private VariantRepository variantRepository;
    @Autowired
    private StockReservationRepository reservationRepository;

    private ReserveRequest request(String orderRef, UUID variantId, int quantity) {
        return new ReserveRequest(orderRef, clock.instant().plus(Duration.ofHours(24)),
                List.of(new LineRequest(variantId, null, quantity)));
    }

    @Test
    void parallelReservationsNeverOversell() throws Exception {
        Listing listing = listing(category(), "Last Units", "500.00", STOCK);
        UUID variantId = variantRepository.findByPublicId(listing.variant()).orElseThrow().getId();

        List<Callable<Boolean>> buyers = new ArrayList<>();
        for (int i = 0; i < BUYERS; i++) {
            String ref = "ORD-RACE-" + next();
            buyers.add(() -> {
                try {
                    reservationService.reserve(request(ref, variantId, 1));
                    return true;
                } catch (InsufficientStockException e) {
                    return false;
                }
            });
        }
        long succeeded = runTogether(buyers).stream().filter(Boolean::booleanValue).count();

        assertThat(succeeded).isEqualTo(STOCK);
        Inventory inventory = inventoryRepository.findById(variantId).orElseThrow();
        assertThat(inventory.getQuantityAvailable()).isZero();
        assertThat(inventory.getReservedQuantity()).isEqualTo(STOCK);
    }

    @Test
    void parallelReplaysOfOneOrderHoldOnce() throws Exception {
        Listing listing = listing(category(), "Replayed", "500.00", STOCK);
        UUID variantId = variantRepository.findByPublicId(listing.variant()).orElseThrow().getId();
        String ref = "ORD-REPLAY-" + next();

        List<Callable<Boolean>> replays = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            replays.add(() -> reservationService.reserve(request(ref, variantId, 3)).created());
        }
        List<Boolean> created = runTogether(replays);

        assertThat(created.stream().filter(Boolean::booleanValue).count()).isEqualTo(1);
        assertThat(reservationRepository.findAllByOrderRefOrderByIdAsc(ref)).hasSize(1);
        Inventory inventory = inventoryRepository.findById(variantId).orElseThrow();
        assertThat(inventory.getQuantityAvailable()).isEqualTo(STOCK - 3);
        assertThat(inventory.getReservedQuantity()).isEqualTo(3);
    }

    @Test
    void parallelCommitAndReleaseOfOneOrderApplyOnlyOne() throws Exception {
        Listing listing = listing(category(), "Contended", "500.00", STOCK);
        UUID variantId = variantRepository.findByPublicId(listing.variant()).orElseThrow().getId();
        String ref = "ORD-CONTENDED-" + next();
        reservationService.reserve(request(ref, variantId, 4));

        List<Callable<Boolean>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            boolean commit = i % 2 == 0;
            calls.add(() -> {
                try {
                    if (commit) {
                        reservationService.commit(ref);
                    } else {
                        reservationService.release(ref);
                    }
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            });
        }
        runTogether(calls);

        Inventory inventory = inventoryRepository.findById(variantId).orElseThrow();
        assertThat(inventory.getReservedQuantity()).isZero();
        // Either committed (6 left) or released (10 back), never both or neither
        assertThat(inventory.getQuantityAvailable()).isIn(STOCK - 4, STOCK);
    }

    private static <T> List<T> runTogether(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(tasks.size(), 16));
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
