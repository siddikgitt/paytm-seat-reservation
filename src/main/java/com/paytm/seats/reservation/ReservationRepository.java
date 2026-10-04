package com.paytm.seats.reservation;

import java.sql.Timestamp;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import com.paytm.seats.show.SeatStatus;
import com.paytm.seats.show.ShowRepository.SeatRow;

/**
 * Every statement that decides ownership is a single guarded write. Reads here are either
 * advisory (the fast path) or taken under FOR UPDATE inside the reserving transaction.
 */
@Repository
public class ReservationRepository {

    private static final RowMapper<Reservation> RESERVATION = (rs, i) -> new Reservation(
            rs.getObject("id", UUID.class),
            rs.getObject("show_id", UUID.class),
            rs.getString("user_id"),
            rs.getString("idempotency_key"),
            rs.getString("request_hash"),
            Arrays.asList((String[]) rs.getArray("seats").getArray()),
            rs.getLong("amount_paise"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toInstant());

    private static final RowMapper<SeatRow> SEAT = (rs, i) ->
            new SeatRow(rs.getString("label"), SeatStatus.fromDb(rs.getString("status")));

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<Reservation> findByKey(String userId, UUID showId, String idempotencyKey) {
        return jdbc.query("""
                SELECT * FROM reservations WHERE user_id = ? AND show_id = ? AND idempotency_key = ?
                """, RESERVATION, userId, showId, idempotencyKey).stream().findFirst();
    }

    public Optional<Reservation> findById(UUID id) {
        return jdbc.query("SELECT * FROM reservations WHERE id = ?", RESERVATION, id).stream().findFirst();
    }

    public List<Reservation> findForUser(UUID showId, String userId) {
        return jdbc.query("SELECT * FROM reservations WHERE show_id = ? AND user_id = ? ORDER BY created_at",
                RESERVATION, showId, userId);
    }

    /** Advisory read for the fast decline path. Never used to grant a seat. */
    public List<SeatRow> seatStates(UUID showId, List<String> labels) {
        return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? AND label = ANY(?::text[])",
                SEAT, showId, labels.toArray(String[]::new));
    }

    /**
     * Idempotency gate. The unique constraint on (user_id, show_id, idempotency_key) means a concurrent
     * insert with the same key blocks until the first transaction ends, then either inserts (first rolled
     * back) or does nothing (first committed). Returns false when a committed row already owns the key.
     */
    public boolean insertIfAbsent(Reservation r) {
        return jdbc.update("""
                INSERT INTO reservations (id, show_id, user_id, idempotency_key, request_hash, seats,
                                          amount_paise, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?::text[], ?, ?, ?)
                ON CONFLICT ON CONSTRAINT reservations_idempotency_uq DO NOTHING
                """, r.id(), r.showId(), r.userId(), r.idempotencyKey(), r.requestHash(),
                r.seats().toArray(String[]::new), r.amountPaise(), r.status(), Timestamp.from(r.createdAt())) == 1;
    }

    /**
     * Per-user limit as one conditional upsert. The quota row is locked for the rest of the transaction,
     * so one user's parallel requests serialise here and the WHERE re-evaluates against the latest count.
     */
    public boolean reserveQuota(UUID showId, String userId, int seats, int limit) {
        return jdbc.update("""
                INSERT INTO user_show_quota (show_id, user_id, seats_held) VALUES (?, ?, ?)
                ON CONFLICT (show_id, user_id) DO UPDATE
                    SET seats_held = user_show_quota.seats_held + EXCLUDED.seats_held
                    WHERE user_show_quota.seats_held + EXCLUDED.seats_held <= ?
                """, showId, userId, seats, limit) == 1;
    }

    public void releaseQuota(UUID showId, String userId, int seats) {
        jdbc.update("UPDATE user_show_quota SET seats_held = seats_held - ? WHERE show_id = ? AND user_id = ?",
                seats, showId, userId);
    }

    /**
     * Row locks on the requested seats, acquired in one deterministic order (byte order of the label)
     * by every transaction, so two multi-seat requests can never wait on each other in a cycle.
     */
    public List<SeatRow> lockSeats(UUID showId, List<String> labels) {
        return jdbc.query("""
                SELECT label, status FROM seats
                WHERE show_id = ? AND label = ANY(?::text[])
                ORDER BY label COLLATE "C"
                FOR UPDATE
                """, SEAT, showId, labels.toArray(String[]::new));
    }

    /** The decision itself: only rows still 'available' change hands. */
    public int claimSeats(UUID showId, List<String> labels, UUID reservationId, String userId) {
        return jdbc.update("""
                UPDATE seats SET status = 'confirmed', reservation_id = ?, user_id = ?, updated_at = now()
                WHERE show_id = ? AND label = ANY(?::text[]) AND status = 'available'
                """, reservationId, userId, showId, labels.toArray(String[]::new));
    }

    /** Ownership is part of the predicate: another user's reservation is indistinguishable from a missing one. */
    public Optional<Reservation> lockReservation(UUID id, String userId) {
        return jdbc.query("SELECT * FROM reservations WHERE id = ? AND user_id = ? FOR UPDATE", RESERVATION, id, userId)
                .stream().findFirst();
    }

    public List<SeatRow> lockSeatsOf(UUID reservationId) {
        return jdbc.query("""
                SELECT label, status FROM seats WHERE reservation_id = ?
                ORDER BY label COLLATE "C"
                FOR UPDATE
                """, SEAT, reservationId);
    }

    /** Guarded on reservation_id: a release can only ever free seats this reservation still owns. */
    public int releaseSeats(UUID reservationId) {
        return jdbc.update("""
                UPDATE seats SET status = 'available', reservation_id = NULL, user_id = NULL, updated_at = now()
                WHERE reservation_id = ? AND status <> 'available'
                """, reservationId);
    }

    public int markCancelled(UUID reservationId) {
        return jdbc.update("""
                UPDATE reservations SET status = 'cancelled', cancelled_at = now()
                WHERE id = ? AND status = 'confirmed'
                """, reservationId);
    }
}
