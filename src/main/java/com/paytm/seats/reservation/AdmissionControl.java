package com.paytm.seats.reservation;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import com.paytm.seats.api.ApiException;
import com.paytm.seats.observability.RequestContext;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Bulkhead in front of the write path. At most {@code max-concurrent} reserve/cancel requests touch the
 * database at once; the rest queue (cheaply, on virtual threads) for up to {@code max-wait}. A request that
 * cannot be admitted in time is shed with 429 + Retry-After before it has read or written anything, so a
 * retry with the same idempotency key is always safe. This replaces "wait for the pool, then 5xx".
 */
@Component
public class AdmissionControl {

    private final Semaphore permits;
    private final int maxConcurrent;
    private final long maxWaitMs;
    private final Counter shed;

    public AdmissionControl(@Value("${app.admission.max-concurrent:64}") int maxConcurrent,
                            @Value("${app.admission.max-wait:25s}") Duration maxWait,
                            MeterRegistry registry) {
        this.maxConcurrent = maxConcurrent;
        this.permits = new Semaphore(maxConcurrent, true);
        this.maxWaitMs = maxWait.toMillis();
        this.shed = Counter.builder("requests.shed")
                .description("Write requests rejected with 429 because the service was saturated").register(registry);
        Gauge.builder("admission.in_flight", () -> this.maxConcurrent - permits.availablePermits())
                .description("Write requests currently admitted").register(registry);
        Gauge.builder("admission.queued", permits::getQueueLength)
                .description("Write requests waiting for admission").register(registry);
    }

    public <T> T admit(Supplier<T> work) {
        boolean acquired;
        try {
            acquired = permits.tryAcquire(maxWaitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            shed.increment();
            RequestContext.outcome("shed", "overloaded");
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "overloaded",
                    "service saturated, retry with the same idempotency key");
        }
        try {
            return work.get();
        } finally {
            permits.release();
        }
    }
}
