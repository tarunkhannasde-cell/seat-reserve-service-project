package com.tarun.seat_reserve_service_project.metrics;


import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Business counters. Exposed at /actuator/prometheus as:
 * <pre>
 *   reservations_confirmed_total{show_id}          reservations that won seats
 *   seats_sold_total{show_id}                      seats sold (a reservation may hold several)
 *   reservations_declined_total{show_id,reason}    seat_taken | per_user_limit | idempotent_replay |
 *                                                  idempotency_key_conflict | unknown_seat | invalid_request
 *   reservations_cancelled_total{show_id}
 *   seats_released_total{show_id}
 * </pre>
 * Counters are recorded only after the transaction has committed (or definitively declined), so
 * seats_sold_total - seats_released_total == seats_confirmed gauge for a process that has
 * served the show since creation.
 */
@Component
public class BookingMetrics {

    public static final String SEAT_TAKEN = "seat_taken";
    public static final String PER_USER_LIMIT = "per_user_limit";
    public static final String IDEMPOTENT_REPLAY = "idempotent_replay";
    public static final String IDEMPOTENCY_CONFLICT = "idempotency_key_conflict";
    public static final String UNKNOWN_SEAT = "unknown_seat";
    public static final String INVALID = "invalid_request";

    private static final Logger log = LoggerFactory.getLogger(BookingMetrics.class);

    private final MeterRegistry registry;

    public BookingMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void confirmed(UUID showId, int seats) {
        safely(() -> {
            counter("reservations.confirmed", showId).increment();
            counter("seats.sold", showId).increment(seats);
        });
    }

    public void declined(UUID showId, String reason) {
        safely(() -> Counter.builder("reservations.declined")
                .tag("show_id", String.valueOf(showId))
                .tag("reason", reason)
                .register(registry)
                .increment());
    }

    public void cancelled(UUID showId, int seats) {
        safely(() -> {
            counter("reservations.cancelled", showId).increment();
            counter("seats.released", showId).increment(seats);
        });
    }

    /** Metrics run after the commit: a metrics bug must never turn a committed reservation into a 500. */
    private static void safely(Runnable r) {
        try {
            r.run();
        } catch (RuntimeException e) {
            log.atError().addKeyValue("error", e.toString()).log("metric recording failed");
        }
    }

    private Counter counter(String name, UUID showId) {
        return Counter.builder(name).tag("show_id", String.valueOf(showId)).register(registry);
    }
}
