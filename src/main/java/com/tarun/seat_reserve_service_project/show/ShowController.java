package com.tarun.seat_reserve_service_project.show;


import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tarun.seat_reserve_service_project.auth.AdminGuard;



@RestController
public class ShowController {

    private final ShowService shows;
    private final AdminGuard admin;

    public ShowController(ShowService shows, AdminGuard admin) {
        this.shows = shows;
        this.admin = admin;
    }

    public record CreateShowRequest(String name,
                                    List<String> seats,
                                    @JsonProperty("price_paise") Long pricePaise,
                                    @JsonProperty("per_user_limit") Integer perUserLimit) {
    }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> create(@RequestHeader(name = "X-Admin-Key", required = false) String adminKey,
                                      @RequestBody CreateShowRequest request) {
        admin.check(adminKey);
        Show show = shows.create(request.name(), request.seats(), request.pricePaise(), request.perUserLimit());
        return render(shows.state(show.id()));
    }

    @GetMapping("/shows/{id}")
    public Map<String, Object> get(@PathVariable UUID id) {
        return render(shows.state(id));
    }

    private static Map<String, Object> render(ShowService.ShowState state) {
        Map<String, Object> body = ShowService.toJson(state.show());
        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("available", state.counts().available());
        counts.put("held", state.counts().held());
        counts.put("confirmed", state.counts().confirmed());
        counts.put("total", state.counts().total());
        body.put("counts", counts);
        body.put("reconciled", state.counts().reconciles());
        body.put("seats", state.seats().stream()
                .map(s -> Map.of("seat", s.label(), "status", s.status()))
                .toList());
        return body;
    }
}
