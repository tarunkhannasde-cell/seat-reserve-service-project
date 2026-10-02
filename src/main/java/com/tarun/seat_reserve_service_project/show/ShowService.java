package com.tarun.seat_reserve_service_project.show;


import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
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

import com.tarun.seat_reserve_service_project.config.BookingProperties;
import com.tarun.seat_reserve_service_project.error.ApiException;
import com.tarun.seat_reserve_service_project.persistance.SeatEntity;
import com.tarun.seat_reserve_service_project.persistance.SeatRepository;
import com.tarun.seat_reserve_service_project.persistance.ShowEntity;
import com.tarun.seat_reserve_service_project.persistance.ShowRepository;


@Service
public class ShowService {

    public static final Pattern SEAT_LABEL = Pattern.compile("[A-Za-z0-9_-]{1,16}");
    public static final int MAX_SEATS = 100_000;
    private static final Logger log = LoggerFactory.getLogger(ShowService.class);

    private final ShowRepository showRepo;
    private final SeatRepository seatRepo;
    private final BookingProperties props;
    private final Map<UUID, Show> cache = new ConcurrentHashMap<>();

    public ShowService(ShowRepository showRepo, SeatRepository seatRepo, BookingProperties props) {
        this.showRepo = showRepo;
        this.seatRepo = seatRepo;
        this.props = props;
    }

    public record SeatState(String label, String status) {
    }

    public record Counts(int available, int held, int confirmed, int total) {
        public boolean reconciles() {
            return available + held + confirmed == total;
        }
    }

    public record ShowState(Show show, Counts counts, List<SeatState> seats) {
    }

    @Transactional
    public Show create(String name, List<String> seats, Long pricePaise, Integer perUserLimit) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw ApiException.badRequest("name is required (max 200 chars)");
        }
        if (seats == null || seats.isEmpty() || seats.size() > MAX_SEATS) {
            throw ApiException.badRequest("seats must contain 1.." + MAX_SEATS + " labels");
        }
        Set<String> unique = new HashSet<>();
        for (String seat : seats) {
            if (seat == null || !SEAT_LABEL.matcher(seat).matches()) {
                throw ApiException.badRequest("invalid seat label: " + seat);
            }
            if (!unique.add(seat)) {
                throw ApiException.badRequest("duplicate seat label: " + seat);
            }
        }
        if (pricePaise == null || pricePaise < 0) {
            throw ApiException.badRequest("price_paise must be a non-negative integer");
        }
        int limit = perUserLimit == null ? props.defaultPerUserLimit() : perUserLimit;
        if (limit < 1) {
            throw ApiException.badRequest("per_user_limit must be >= 1");
        }

        Show show = new Show(UUID.randomUUID(), name, pricePaise, limit, seats.size());
        String id = show.id().toString();
        showRepo.save(ShowEntity.create(id, name, pricePaise, limit, seats.size()));
        showRepo.flush(); // parent row first, then the seats as JDBC batches
        List<SeatEntity> rows = new ArrayList<>(seats.size());
        for (int i = 0; i < seats.size(); i++) {
            rows.add(SeatEntity.available(id, seats.get(i), i + 1));
        }
        seatRepo.saveAll(rows);
        cache.put(show.id(), show);
        log.atInfo().addKeyValue("show_id", id).addKeyValue("total_seats", show.totalSeats())
                .addKeyValue("per_user_limit", limit).log("show created");
        return show;
    }

    public Show get(UUID id) {
        Show cached = cache.get(id);
        if (cached != null) {
            return cached;
        }
        Show show = showRepo.findById(id.toString())
                .map(e -> new Show(id, e.getName(), e.getPricePaise(), e.getPerUserLimit(), e.getTotalSeats()))
                .orElseThrow(() -> ApiException.notFound("show_not_found", "no show with id " + id));
        cache.put(id, show);
        return show;
    }

    /**
     * Seat map and counts come from ONE SELECT, i.e. one InnoDB consistent-read snapshot, so the counts
     * always reconcile against total_seats even while reservations are landing concurrently.
     */
    public ShowState state(UUID id) {
        Show show = get(id);
        List<SeatState> seats = new ArrayList<>(show.totalSeats());
        int available = 0, held = 0, confirmed = 0;
        for (SeatRepository.SeatStatus s : seatRepo.findSeatMap(id.toString())) {
            seats.add(new SeatState(s.getLabel(), s.getStatus()));
            switch (s.getStatus()) {
                case SeatEntity.AVAILABLE -> available++;
                case SeatEntity.HELD -> held++;
                case SeatEntity.CONFIRMED -> confirmed++;
                default -> throw new IllegalStateException("unknown seat status " + s.getStatus());
            }
        }
        return new ShowState(show, new Counts(available, held, confirmed, show.totalSeats()), seats);
    }

    public static Map<String, Object> toJson(Show show) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", show.id());
        body.put("name", show.name());
        body.put("price_paise", show.pricePaise());
        body.put("per_user_limit", show.perUserLimit());
        body.put("total_seats", show.totalSeats());
        return body;
    }
}
