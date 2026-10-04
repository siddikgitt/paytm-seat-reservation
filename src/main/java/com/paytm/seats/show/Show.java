package com.paytm.seats.show;

import java.time.Instant;
import java.util.UUID;

/** Shows are immutable once created, which is what makes caching them safe. */
public record Show(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats, Instant createdAt) {
}
