package com.paytm.seats.show;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.paytm.seats.auth.Admin;
import com.paytm.seats.auth.AuthUser;
import com.paytm.seats.observability.RequestContext;

@RestController
public class ShowController {

    public record SeatView(String label, String status) {
    }

    public record ShowView(UUID id, String name, long pricePaise, int perUserLimit, int totalSeats,
                           Instant createdAt, SeatCounts counts, boolean reconciled, List<SeatView> seats) {
    }

    private final ShowService shows;

    public ShowController(ShowService shows) {
        this.shows = shows;
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public ShowView create(@Admin AuthUser admin, @RequestBody ShowService.CreateShow body) {
        Show show = shows.create(body);
        return view(show);
    }

    @GetMapping("/shows/{id}")
    public ShowView get(@PathVariable UUID id) {
        RequestContext.show(id);
        return view(shows.get(id));
    }

    private ShowView view(Show show) {
        List<ShowRepository.SeatRow> rows = shows.seats(show.id());
        SeatCounts counts = ShowRepository.countsOf(rows, show.totalSeats());
        List<SeatView> seats = rows.stream().map(r -> new SeatView(r.label(), r.status().db())).toList();
        return new ShowView(show.id(), show.name(), show.pricePaise(), show.perUserLimit(), show.totalSeats(),
                show.createdAt(), counts, counts.reconciles(), seats);
    }
}
