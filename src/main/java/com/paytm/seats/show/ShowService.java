package com.paytm.seats.show;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.paytm.seats.api.ApiException;
import com.paytm.seats.config.AppProperties;
import com.paytm.seats.observability.RequestContext;

@Service
public class ShowService {

    private static final Logger log = LoggerFactory.getLogger(ShowService.class);
    public static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9_-]{1,16}");

    public record CreateShow(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {
    }

    private final ShowRepository repo;
    private final AppProperties props;
    private final Map<UUID, Show> cache = new ConcurrentHashMap<>();

    public ShowService(ShowRepository repo, AppProperties props) {
        this.repo = repo;
        this.props = props;
    }

    @Transactional
    public Show create(CreateShow req) {
        if (req == null || req.name() == null || req.name().isBlank() || req.name().length() > 200) {
            throw ApiException.badRequest("invalid_request", "name is required (max 200 chars)");
        }
        if (req.pricePaise() == null || req.pricePaise() <= 0) {
            throw ApiException.badRequest("invalid_request", "price_paise must be a positive integer (paise)");
        }
        int limit = req.perUserLimit() == null ? props.defaultPerUserLimit() : req.perUserLimit();
        if (limit < 1 || limit > 100) {
            throw ApiException.badRequest("invalid_request", "per_user_limit must be between 1 and 100");
        }
        List<String> seats = req.seats();
        if (seats == null || seats.isEmpty() || seats.size() > props.maxSeatsPerShow()) {
            throw ApiException.badRequest("invalid_request",
                    "seats must contain 1.." + props.maxSeatsPerShow() + " labels");
        }
        Set<String> seen = new HashSet<>();
        for (String label : seats) {
            if (label == null || !SEAT_LABEL.matcher(label).matches()) {
                throw ApiException.badRequest("invalid_seat", "seat labels must match " + SEAT_LABEL.pattern());
            }
            if (!seen.add(label)) {
                throw ApiException.badRequest("duplicate_seat", "duplicate seat label " + label);
            }
        }
        Show show = new Show(UUID.randomUUID(), req.name().trim(), req.pricePaise(), limit, seats.size(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        repo.insert(show);
        repo.insertSeats(show.id(), seats);
        cache.put(show.id(), show);
        RequestContext.show(show.id());
        log.info("show created seats={} price_paise={} per_user_limit={}", show.totalSeats(), show.pricePaise(), limit);
        return show;
    }

    public Show get(UUID id) {
        Show cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        Show show = repo.find(id).orElseThrow(() -> ApiException.notFound("show not found"));
        cache.put(id, show);
        return show;
    }

    public List<ShowRepository.SeatRow> seats(UUID id) {
        return repo.seats(id);
    }
}
