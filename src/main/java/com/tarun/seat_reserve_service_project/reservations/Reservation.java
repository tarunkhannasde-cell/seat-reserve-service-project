package com.tarun.seat_reserve_service_project.reservations;


import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.tarun.seat_reserve_service_project.persistance.ReservationEntity;


public record Reservation(UUID id, UUID showId, String userId, List<String> seats, long amountPaise,
                          String status, String requestHash, Instant createdAt) {

    static Reservation from(ReservationEntity e) {
        return new Reservation(UUID.fromString(e.getId()), UUID.fromString(e.getShowId()), e.getUserId(),
                List.copyOf(e.getSeats()), e.getAmountPaise(), e.getStatus(), e.getRequestHash(), e.getCreatedAt());
    }

    Reservation withStatus(String newStatus) {
        return new Reservation(id, showId, userId, seats, amountPaise, newStatus, requestHash, createdAt);
    }

    public Map<String, Object> toJson() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reservation_id", id);
        body.put("show_id", showId);
        body.put("user_id", userId);
        body.put("seats", seats);
        body.put("amount_paise", amountPaise);
        body.put("status", status);
        body.put("created_at", createdAt);
        return body;
    }
}
