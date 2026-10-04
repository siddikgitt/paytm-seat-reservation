package com.paytm.seats.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.paytm.seats.auth.AuthUser;

@RestController
public class ReservationController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    public record ReservationView(UUID reservationId, UUID showId, String userId, List<String> seats,
                                  long amountPaise, String status, Instant createdAt) {

        static ReservationView of(Reservation r) {
            return new ReservationView(r.id(), r.showId(), r.userId(), r.seats(), r.amountPaise(), r.status(),
                    r.createdAt());
        }
    }

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /**
     * 201 for a new reservation; 200 + {@code Idempotent-Replayed: true} for a retry of the same key, so a
     * storm with retries still yields exactly one 201 per seat. The user is always the token's subject;
     * any user field in the body is ignored.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<ReservationView> reserve(AuthUser user, @PathVariable UUID showId,
                                                   @RequestBody(required = false) ReservationService.ReserveRequest body,
                                                   @RequestHeader(value = IDEMPOTENCY_HEADER, required = false) String key) {
        ReservationService.ReserveResult result = reservations.reserve(user, showId, body, key);
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(ReservationView.of(result.reservation()));
    }
}
