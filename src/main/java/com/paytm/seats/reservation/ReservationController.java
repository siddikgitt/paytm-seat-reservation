package com.paytm.seats.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
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
    private final AdmissionControl admission;

    public ReservationController(ReservationService reservations, AdmissionControl admission) {
        this.reservations = reservations;
        this.admission = admission;
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
        ReservationService.ReserveResult result = admission.admit(() -> reservations.reserve(user, showId, body, key));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .header(REPLAYED_HEADER, Boolean.toString(result.replayed()))
                .body(ReservationView.of(result.reservation()));
    }

    /** Only the owner may cancel; anyone else gets 404. Cancelling twice returns the cancelled reservation. */
    @PostMapping("/reservations/{id}/cancel")
    public ReservationView cancel(AuthUser user, @PathVariable UUID id) {
        return ReservationView.of(admission.admit(() -> reservations.cancel(user, id)));
    }

    @GetMapping("/reservations/{id}")
    public ReservationView get(AuthUser user, @PathVariable UUID id) {
        return ReservationView.of(reservations.get(user, id));
    }

    @GetMapping("/me/reservations")
    public List<ReservationView> mine(AuthUser user, @RequestParam("show_id") UUID showId) {
        return reservations.mine(user, showId).stream().map(ReservationView::of).toList();
    }
}
