package com.achintha.productservice.inventory;

import com.achintha.productservice.ports.NotificationPort;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Safety net (section 5): order-service is authoritative and releases holds itself; holds still {@code HELD}
 * {@code grace} (1 h) after their {@code expiresAt} are released here, so a lost release call cannot lock stock
 * forever. One replica runs it at a time (ShedLock); each order is released in its own transaction under the same
 * per-order lock as the API.
 */
@Slf4j
@Component
public class ReservationCleanupJob {

    private final StockReservationRepository reservationRepository;
    private final ReservationService reservationService;
    private final NotificationPort notifications;
    private final Clock clock;
    private final Duration grace;
    private final int batchSize;

    public ReservationCleanupJob(StockReservationRepository reservationRepository,
                                 ReservationService reservationService, NotificationPort notifications, Clock clock,
                                 @Value("${app.scheduling.reservation-cleanup.grace:PT1H}") Duration grace,
                                 @Value("${app.scheduling.reservation-cleanup.batch-size:200}") int batchSize) {
        this.reservationRepository = reservationRepository;
        this.reservationService = reservationService;
        this.notifications = notifications;
        this.clock = clock;
        this.grace = grace;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduling.reservation-cleanup.interval:PT5M}")
    @SchedulerLock(name = "product-service.reservationCleanup", lockAtMostFor = "PT10M")
    public void scheduledRun() {
        releaseExpired();
    }

    /** @return the number of reservation lines released */
    public int releaseExpired() {
        Instant cutoff = clock.instant().minus(grace);
        List<String> orderRefs = reservationRepository.orderRefsHeldBefore(Timestamp.from(cutoff), batchSize);
        int released = 0;
        for (String orderRef : orderRefs) {
            try {
                released += reservationService.releaseExpired(orderRef, cutoff);
            } catch (RuntimeException e) {
                log.warn("Could not release expired holds of order {}: {}", orderRef, e.getMessage());
            }
        }
        if (released > 0) {
            log.warn("Safety net released {} stock hold line(s) of {} order(s) past expiry + {}", released,
                    orderRefs.size(), grace);
            notifications.notifyAdmins("STOCK_HOLDS_EXPIRED", released + " hold line(s) released by the safety net");
        }
        return released;
    }
}
