package com.tarun.seat_reserve_service_project.reservations;


import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.tarun.seat_reserve_service_project.auth.AuthenticatedUser;
import com.tarun.seat_reserve_service_project.error.ApiException;
import com.fasterxml.jackson.annotation.JsonProperty;

@RestController
public class ReservationController {

    private final ReservationService reservations;

    public ReservationController(ReservationService reservations) {
        this.reservations = reservations;
    }

    /** Any user_id / owner field a client sends is ignored: identity is the token's subject. */
    public record ReserveRequest(List<String> seats, @JsonProperty("idempotency_key") String idempotencyKey) {
    }

    /**
     * 201 — new reservation (exactly one per hot seat).
     * 200 — idempotent replay: the original reservation, unchanged, with header Idempotent-Replayed: true.
     * 409 — seat_taken | per_user_limit | idempotency_key_conflict.   422 — unknown_seat.   400 — invalid.
     */
    @PostMapping("/shows/{showId}/reserve")
    public ResponseEntity<Map<String, Object>> reserve(AuthenticatedUser user,
                                                       @PathVariable UUID showId,
                                                       @RequestHeader(name = "Idempotency-Key", required = false) String headerKey,
                                                       @RequestBody ReserveRequest request) {
        String bodyKey = request.idempotencyKey();
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey)) {
            throw ApiException.badRequest("Idempotency-Key header and idempotency_key body field differ");
        }
        String key = headerKey != null ? headerKey : bodyKey;
        ReservationService.ReserveResult result = reservations.reserve(user.id(), showId, request.seats(), key);
        if (result.replay()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(result.reservation().toJson());
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.reservation().toJson());
    }

    @PostMapping("/reservations/{id}/cancel")
    public Map<String, Object> cancel(AuthenticatedUser user, @PathVariable UUID id) {
        return reservations.cancel(user.id(), id).reservation().toJson();
    }

    @GetMapping("/reservations/{id}")
    public Map<String, Object> get(AuthenticatedUser user, @PathVariable UUID id) {
        return reservations.getOwned(user.id(), id).toJson();
    }
}
