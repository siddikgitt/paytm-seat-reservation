package com.paytm.seats.reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record Reservation(
        UUID id,
        UUID showId,
        String userId,
        String idempotencyKey,
        String requestHash,
        List<String> seats,
        long amountPaise,
        String status,
        Instant createdAt) {

    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";
}
