package com.paytm.seats.observability;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business counters. Incremented only after the deciding transaction has committed (or a decline is
 * final), so they reconcile with what GET /shows/{id} reports.
 */
@Component
public class ReservationMetrics {

    public static final String SEAT_TAKEN = "seat_taken";
    public static final String PER_USER_LIMIT = "per_user_limit";
    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String IDEMPOTENCY_KEY_REUSED = "idempotency_key_reused";
    public static final String INVALID = "invalid";

    private final MeterRegistry registry;
    private final Counter confirmed;
    private final Counter seatsConfirmed;
    private final Counter cancelled;
    private final Counter seatsReleased;
    private final Map<String, Counter> declined = new ConcurrentHashMap<>();

    public ReservationMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.confirmed = Counter.builder("reservations.confirmed")
                .description("Reservations committed (one per successful 201)").register(registry);
        this.seatsConfirmed = Counter.builder("seats.confirmed")
                .description("Seats moved to confirmed").register(registry);
        this.cancelled = Counter.builder("reservations.cancelled")
                .description("Reservations cancelled by their owner").register(registry);
        this.seatsReleased = Counter.builder("seats.released")
                .description("Seats returned to available by a cancel").register(registry);
        for (String reason : new String[] {SEAT_TAKEN, PER_USER_LIMIT, IDEMPOTENT_REPLAY, IDEMPOTENCY_KEY_REUSED, INVALID}) {
            declinedCounter(reason);
        }
    }

    public void confirmed(int seats) {
        confirmed.increment();
        seatsConfirmed.increment(seats);
    }

    public void cancelled(int seats) {
        cancelled.increment();
        seatsReleased.increment(seats);
    }

    public void declined(String reason) {
        declinedCounter(reason).increment();
    }

    private Counter declinedCounter(String reason) {
        return declined.computeIfAbsent(reason, r -> Counter.builder("reservations.declined")
                .description("Reserve requests that did not create a new reservation, by reason")
                .tag("reason", r)
                .register(registry));
    }
}
