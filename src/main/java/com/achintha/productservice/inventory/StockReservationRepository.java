package com.achintha.productservice.inventory;

import java.sql.Timestamp;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StockReservationRepository extends JpaRepository<StockReservation, Long> {

    List<StockReservation> findAllByOrderRefOrderByIdAsc(String orderRef);

    /** Orders with holds still HELD before {@code cutoff} (safety net), oldest first. */
    @Query(value = """
            select order_ref from stock_reservations
            where status = 'HELD' and expires_at < :cutoff
            group by order_ref
            order by min(expires_at)
            limit :limit
            """, nativeQuery = true)
    List<String> orderRefsHeldBefore(@Param("cutoff") Timestamp cutoff, @Param("limit") int limit);
}
