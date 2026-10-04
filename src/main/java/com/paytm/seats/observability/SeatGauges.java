package com.paytm.seats.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.paytm.seats.config.AppProperties;
import com.paytm.seats.show.SeatCounts;
import com.paytm.seats.show.Show;
import com.paytm.seats.show.ShowRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Seat-state gauges read from the database (the system of record), not from in-process counters, so
 * they agree with GET /shows/{id} across restarts and instances. Bounded to the most recent shows.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

    private final ShowRepository shows;
    private final int trackedShows;
    private final MultiGauge seats;
    private final MultiGauge available;
    private final MultiGauge drift;

    public SeatGauges(ShowRepository shows, MeterRegistry registry, AppProperties props) {
        this.shows = shows;
        this.trackedShows = props.metrics().trackedShows();
        this.seats = MultiGauge.builder("show.seats")
                .description("Seats per show by status (available/held/confirmed)").register(registry);
        this.available = MultiGauge.builder("seats.available")
                .description("Seats currently available per show").register(registry);
        this.drift = MultiGauge.builder("seats.reconciliation.drift")
                .description("total_seats - (available + held + confirmed); must always be 0").register(registry);
    }

    @Scheduled(fixedDelayString = "${app.metrics.refresh-ms:1000}", initialDelay = 2000)
    public void refresh() {
        try {
            List<Show> recent = shows.recent(trackedShows);
            Map<UUID, SeatCounts> counts = shows.counts(recent);
            List<MultiGauge.Row<?>> seatRows = new ArrayList<>();
            List<MultiGauge.Row<?>> availableRows = new ArrayList<>();
            List<MultiGauge.Row<?>> driftRows = new ArrayList<>();
            for (Show show : recent) {
                SeatCounts c = counts.get(show.id());
                String id = show.id().toString();
                seatRows.add(MultiGauge.Row.of(Tags.of("show_id", id, "status", "available"), c.available()));
                seatRows.add(MultiGauge.Row.of(Tags.of("show_id", id, "status", "held"), c.held()));
                seatRows.add(MultiGauge.Row.of(Tags.of("show_id", id, "status", "confirmed"), c.confirmed()));
                seatRows.add(MultiGauge.Row.of(Tags.of("show_id", id, "status", "total"), c.total()));
                availableRows.add(MultiGauge.Row.of(Tags.of("show_id", id), c.available()));
                driftRows.add(MultiGauge.Row.of(Tags.of("show_id", id),
                        c.total() - (c.available() + c.held() + c.confirmed())));
            }
            seats.register(seatRows, true);
            available.register(availableRows, true);
            drift.register(driftRows, true);
        } catch (Exception e) {
            log.warn("seat gauge refresh failed: {}", e.toString());
        }
    }
}
