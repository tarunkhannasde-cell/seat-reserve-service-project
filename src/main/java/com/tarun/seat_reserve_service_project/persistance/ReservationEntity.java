package com.tarun.seat_reserve_service_project.persistance;


import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Converter;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "reservations")
public class ReservationEntity extends NewAware<String> {

    public static final String CONFIRMED = "confirmed";
    public static final String CANCELLED = "cancelled";

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "show_id", nullable = false, length = 36)
    private String showId;

    @Column(name = "user_id", nullable = false, length = 64)
    private String userId;

    @Convert(converter = SeatListConverter.class)
    @Column(nullable = false, length = 4096)
    private List<String> seats;

    @Column(name = "amount_paise", nullable = false)
    private long amountPaise;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false, length = 64, columnDefinition = "char(64)")
    private String requestHash;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected ReservationEntity() {
    }

    public static ReservationEntity confirmed(String id, String showId, String userId, List<String> seats,
                                              long amountPaise, String idempotencyKey, String requestHash) {
        ReservationEntity r = new ReservationEntity();
        r.id = id;
        r.showId = showId;
        r.userId = userId;
        r.seats = List.copyOf(seats);
        r.amountPaise = amountPaise;
        r.status = CONFIRMED;
        r.idempotencyKey = idempotencyKey;
        r.requestHash = requestHash;
        r.createdAt = Instant.now();
        r.markNew();
        return r;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getShowId() {
        return showId;
    }

    public String getUserId() {
        return userId;
    }

    public List<String> getSeats() {
        return seats;
    }

    public long getAmountPaise() {
        return amountPaise;
    }

    public String getStatus() {
        return status;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    /** Seat labels match [A-Za-z0-9_-], so a comma is a safe separator. */
    @Converter
    public static class SeatListConverter implements AttributeConverter<List<String>, String> {
        @Override
        public String convertToDatabaseColumn(List<String> seats) {
            return String.join(",", seats);
        }

        @Override
        public List<String> convertToEntityAttribute(String value) {
            return value == null || value.isEmpty() ? List.of() : Arrays.asList(value.split(","));
        }
    }
}
