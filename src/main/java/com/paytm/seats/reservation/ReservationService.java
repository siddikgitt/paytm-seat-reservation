package com.paytm.seats.reservation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.paytm.seats.api.ApiException;
import com.paytm.seats.auth.AuthUser;
import com.paytm.seats.observability.RequestContext;
import com.paytm.seats.observability.ReservationMetrics;
import com.paytm.seats.show.SeatStatus;
import com.paytm.seats.show.Show;
import com.paytm.seats.show.ShowRepository.SeatRow;
import com.paytm.seats.show.ShowService;

/**
 * Reserve flow (all-or-nothing for multi-seat requests):
 * <ol>
 *   <li>Replay lookup by (user, show, idempotency key) - no locks.</li>
 *   <li>Fast decline if any requested seat is visibly taken - advisory, can only refuse, never grant.</li>
 *   <li>One READ COMMITTED transaction taking locks in a fixed order:
 *       reservation row (idempotency) then quota row then seat rows sorted by label.
 *       The guarded {@code UPDATE ... WHERE status = 'available'} is the decision.</li>
 * </ol>
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_KEY_LENGTH = 128;

    public record ReserveRequest(List<String> seats, String idempotencyKey) {
    }

    public record ReserveResult(Reservation reservation, boolean replayed) {
    }

    private final ReservationRepository repo;
    private final ShowService shows;
    private final TransactionTemplate tx;
    private final ReservationMetrics metrics;

    public ReservationService(ReservationRepository repo, ShowService shows, TransactionTemplate tx,
                              ReservationMetrics metrics) {
        this.repo = repo;
        this.shows = shows;
        this.tx = tx;
        this.metrics = metrics;
    }

    public ReserveResult reserve(AuthUser user, UUID showId, ReserveRequest body, String headerKey) {
        RequestContext.show(showId);
        Show show = shows.get(showId);
        String key = idempotencyKey(body, headerKey);
        List<String> seats = normalizeSeats(body == null ? null : body.seats());
        String requestHash = hash(seats);

        ReservationRepository.Precheck pre = repo.precheck(user.userId(), showId, key, seats);
        if (pre.existingReservationId() != null) {
            Reservation prior = repo.findById(pre.existingReservationId())
                    .orElseThrow(() -> new IllegalStateException("reservation vanished"));
            return replay(prior, requestHash);
        }
        if (pre.found() != seats.size()) {
            metrics.declined(ReservationMetrics.INVALID);
            RequestContext.outcome("declined", "unknown_seat");
            throw ApiException.badRequest("unknown_seat", "one or more seats do not exist in this show");
        }
        if (seats.size() > show.perUserLimit()) {
            throw decline(ReservationMetrics.PER_USER_LIMIT,
                    "request exceeds per-user limit of " + show.perUserLimit());
        }
        if (pre.unavailable() > 0) {
            throw decline(ReservationMetrics.SEAT_TAKEN, "seat already taken");
        }

        Reservation candidate = new Reservation(UUID.randomUUID(), showId, user.userId(), key, requestHash, seats,
                Math.multiplyExact(show.pricePaise(), (long) seats.size()), Reservation.CONFIRMED,
                Instant.now().truncatedTo(ChronoUnit.MICROS));

        ReserveResult result = withRetry(() -> tx.execute(status -> attempt(show, candidate)));
        if (!result.replayed()) {
            metrics.confirmed(seats.size());
            RequestContext.reservation(candidate.id());
            RequestContext.outcome("confirmed", null);
            log.info("reservation confirmed seats={} amount_paise={}", seats, candidate.amountPaise());
        }
        return result;
    }

    private ReserveResult attempt(Show show, Reservation r) {
        if (!repo.insertIfAbsent(r)) {
            Reservation existing = repo.findByKey(r.userId(), r.showId(), r.idempotencyKey())
                    .orElseThrow(() -> new IllegalStateException("idempotency conflict without a committed row"));
            return replay(existing, r.requestHash());
        }
        if (!repo.reserveQuota(show.id(), r.userId(), r.seats().size(), show.perUserLimit())) {
            throw decline(ReservationMetrics.PER_USER_LIMIT,
                    "user already holds the maximum of " + show.perUserLimit() + " seats for this show");
        }
        List<SeatRow> locked = repo.lockSeats(show.id(), r.seats());
        if (locked.size() != r.seats().size() || locked.stream().anyMatch(s -> s.status() != SeatStatus.AVAILABLE)) {
            throw decline(ReservationMetrics.SEAT_TAKEN, "seat already taken");
        }
        int claimed = repo.claimSeats(show.id(), r.seats(), r.id(), r.userId());
        if (claimed != r.seats().size()) {
            throw decline(ReservationMetrics.SEAT_TAKEN, "seat already taken");
        }
        return new ReserveResult(r, false);
    }

    /**
     * Owner-only, idempotent cancel. Lock order matches reserve (reservation, quota, seats), and the seat
     * release is guarded on reservation_id, so it can never free a seat that now belongs to someone else.
     */
    public Reservation cancel(AuthUser user, UUID reservationId) {
        RequestContext.reservation(reservationId);
        record Cancelled(Reservation reservation, int released) {
        }
        Cancelled result = withRetry(() -> tx.execute(status -> {
            Reservation r = repo.lockReservation(reservationId, user.userId())
                    .orElseThrow(() -> ApiException.notFound("reservation not found"));
            if (Reservation.CANCELLED.equals(r.status())) {
                return new Cancelled(r, -1);
            }
            repo.releaseQuota(r.showId(), r.userId(), r.seats().size());
            repo.lockSeatsOf(r.id());
            int released = repo.releaseSeats(r.id());
            repo.markCancelled(r.id());
            return new Cancelled(withStatus(r, Reservation.CANCELLED), released);
        }));
        Reservation r = result.reservation();
        RequestContext.show(r.showId());
        if (result.released() < 0) {
            RequestContext.outcome("cancel_replayed", null);
            return r;
        }
        if (result.released() != r.seats().size()) {
            log.error("cancel released {} seats but reservation owned {}", result.released(), r.seats().size());
        }
        metrics.cancelled(result.released());
        RequestContext.outcome("cancelled", null);
        log.info("reservation cancelled seats={}", r.seats());
        return r;
    }

    public Reservation get(AuthUser user, UUID reservationId) {
        return repo.findById(reservationId)
                .filter(r -> r.userId().equals(user.userId()))
                .orElseThrow(() -> ApiException.notFound("reservation not found"));
    }

    public List<Reservation> mine(AuthUser user, UUID showId) {
        shows.get(showId);
        return repo.findForUser(showId, user.userId());
    }

    private static Reservation withStatus(Reservation r, String status) {
        return new Reservation(r.id(), r.showId(), r.userId(), r.idempotencyKey(), r.requestHash(), r.seats(),
                r.amountPaise(), status, r.createdAt());
    }

    private ReserveResult replay(Reservation existing, String requestHash) {
        RequestContext.reservation(existing.id());
        if (!existing.requestHash().equals(requestHash)) {
            throw decline(ReservationMetrics.IDEMPOTENCY_KEY_REUSED,
                    "idempotency key already used with a different request");
        }
        metrics.declined(ReservationMetrics.IDEMPOTENT_REPLAY);
        RequestContext.outcome("replayed", ReservationMetrics.IDEMPOTENT_REPLAY);
        return new ReserveResult(existing, true);
    }

    private ApiException decline(String reason, String message) {
        metrics.declined(reason);
        RequestContext.outcome("declined", reason);
        return ApiException.conflict(reason, message);
    }

    /** Lock-timeout / deadlock / serialization failures are retried; declines are final and never retried. */
    static <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (TransientDataAccessException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.warn("transient failure, retrying attempt={} cause={}", attempt, e.getClass().getSimpleName());
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextLong(5, 25L * attempt));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private String idempotencyKey(ReserveRequest body, String headerKey) {
        String bodyKey = body == null ? null : body.idempotencyKey();
        if (bodyKey != null && headerKey != null && !bodyKey.equals(headerKey)) {
            throw invalid("idempotency key in header and body differ");
        }
        String key = bodyKey != null ? bodyKey : headerKey;
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw invalid("idempotency_key is required (1.." + MAX_KEY_LENGTH + " chars)");
        }
        return key;
    }

    private List<String> normalizeSeats(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw invalid("seats must be a non-empty array");
        }
        if (seats.size() > 100) {
            throw invalid("too many seats in one request");
        }
        HashSet<String> unique = new HashSet<>();
        for (String s : seats) {
            if (s == null || !ShowService.SEAT_LABEL.matcher(s).matches()) {
                throw invalid("invalid seat label");
            }
            if (!unique.add(s)) {
                throw invalid("duplicate seat " + s + " in request");
            }
        }
        List<String> sorted = new ArrayList<>(seats);
        sorted.sort(null);
        return List.copyOf(sorted);
    }

    private ApiException invalid(String message) {
        metrics.declined(ReservationMetrics.INVALID);
        RequestContext.outcome("declined", "invalid_request");
        return ApiException.badRequest("invalid_request", message);
    }

    /** Canonical request body: the sorted seat set. Same key + same set = replay; anything else = 409. */
    static String hash(List<String> sortedSeats) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.join(",", sortedSeats).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
