package com.paytm.seats.show;

import java.sql.Array;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

@Repository
public class ShowRepository {

    public record SeatRow(String label, SeatStatus status) {
    }

    private static final RowMapper<Show> SHOW = (rs, i) -> new Show(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getLong("price_paise"),
            rs.getInt("per_user_limit"),
            rs.getInt("total_seats"),
            rs.getTimestamp("created_at").toInstant());

    private final JdbcTemplate jdbc;

    public ShowRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(Show show) {
        jdbc.update("""
                INSERT INTO shows (id, name, price_paise, per_user_limit, total_seats, created_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                Timestamp.from(show.createdAt()));
    }

    /** One statement for the whole hall; position preserves the order the admin supplied. */
    public int insertSeats(UUID showId, List<String> labels) {
        return jdbc.execute((java.sql.Connection con) -> {
            Array array = con.createArrayOf("text", labels.toArray());
            try (var ps = con.prepareStatement("""
                    INSERT INTO seats (show_id, label, position)
                    SELECT ?, t.label, t.ord FROM unnest(?::text[]) WITH ORDINALITY AS t(label, ord)
                    """)) {
                ps.setObject(1, showId);
                ps.setArray(2, array);
                return ps.executeUpdate();
            } finally {
                array.free();
            }
        });
    }

    public Optional<Show> find(UUID id) {
        return jdbc.query("SELECT * FROM shows WHERE id = ?", SHOW, id).stream().findFirst();
    }

    /** A single statement, hence a single MVCC snapshot: the seat list and counts always reconcile. */
    public List<SeatRow> seats(UUID showId) {
        return jdbc.query("SELECT label, status FROM seats WHERE show_id = ? ORDER BY position",
                (rs, i) -> new SeatRow(rs.getString("label"), SeatStatus.fromDb(rs.getString("status"))), showId);
    }

    public List<Show> recent(int limit) {
        return jdbc.query("SELECT * FROM shows ORDER BY created_at DESC LIMIT ?", SHOW, limit);
    }

    /** Per-show counts from one GROUP BY (one snapshot), totals taken from each show's declared size. */
    public Map<UUID, SeatCounts> counts(List<Show> shows) {
        Map<UUID, long[]> acc = new HashMap<>();
        if (shows.isEmpty()) {
            return Map.of();
        }
        List<UUID> showIds = shows.stream().map(Show::id).toList();
        jdbc.query(con -> {
            var ps = con.prepareStatement("""
                    SELECT show_id, status, count(*) AS n FROM seats
                    WHERE show_id = ANY(?) GROUP BY show_id, status
                    """);
            ps.setArray(1, con.createArrayOf("uuid", showIds.toArray()));
            return ps;
        }, rs -> {
            long[] c = acc.computeIfAbsent(rs.getObject("show_id", UUID.class), k -> new long[3]);
            c[SeatStatus.fromDb(rs.getString("status")).ordinal()] = rs.getLong("n");
        });
        Map<UUID, SeatCounts> out = new HashMap<>();
        for (Show show : shows) {
            long[] c = acc.getOrDefault(show.id(), new long[3]);
            out.put(show.id(), new SeatCounts(c[0], c[1], c[2], show.totalSeats()));
        }
        return out;
    }

    /** Counts from the seat rows, checked against the show's declared total rather than the row count. */
    public static SeatCounts countsOf(List<SeatRow> seats, long declaredTotal) {
        long[] c = new long[3];
        for (SeatRow s : seats) {
            c[s.status().ordinal()]++;
        }
        return new SeatCounts(c[0], c[1], c[2], declaredTotal);
    }
}
