package com.achintha.productservice.inventory;

import com.achintha.productservice.inventory.ReservationDtos.AdjustRequest;
import com.achintha.productservice.inventory.ReservationDtos.ReservationResponse;
import com.achintha.productservice.inventory.ReservationDtos.ReserveRequest;
import com.achintha.productservice.inventory.ReservationService.ReserveResult;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock reservations for order-service ({@code ROLE_SERVICE} only; never routed by the gateway; not in the public
 * docs). Every call is idempotent per {@code orderRef}.
 */
@RestController
@RequestMapping("/internal/reservations")
@PreAuthorize("hasAuthority('ROLE_SERVICE')")
@RequiredArgsConstructor
public class InternalReservationController {

    private final ReservationService reservationService;

    /** 201 when the hold was placed, 200 when an identical earlier request is replayed. */
    @PostMapping
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        ReserveResult result = reservationService.reserve(request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(result.reservation());
    }

    @GetMapping("/{orderRef}")
    public ReservationResponse get(@PathVariable String orderRef) {
        return reservationService.get(orderRef);
    }

    @PutMapping("/{orderRef}")
    public ReservationResponse adjust(@PathVariable String orderRef, @Valid @RequestBody AdjustRequest request) {
        return reservationService.adjust(orderRef, request);
    }

    @PostMapping("/{orderRef}/commit")
    public ReservationResponse commit(@PathVariable String orderRef) {
        return reservationService.commit(orderRef);
    }

    @PostMapping("/{orderRef}/release")
    public ReservationResponse release(@PathVariable String orderRef) {
        return reservationService.release(orderRef);
    }
}
