package com.tarun.seat_reserve_service_project.metrics;


import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.tarun.seat_reserve_service_project.persistance.SeatRepository;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;

/**
 * Seat-state gauges read from the database (the system of record), refreshed every second:
 * <pre>
 *   seats_available{show_id}  seats_held{show_id}  seats_confirmed{show_id}  seats_total{show_id}
 *   seat_invariant_violations   number of shows where available+held+confirmed != total (must be 0)
 * </pre>
 * All values come from one GROUP BY statement, i.e. one consistent snapshot.
 */
@Component
public class SeatGauges {

    private static final Logger log = LoggerFactory.getLogger(SeatGauges.class);

    private final SeatRepository seats;
    private final MultiGauge available;
    private final MultiGauge held;
    private final MultiGauge confirmed;
    private final MultiGauge total;
    private volatile int violations;

    public SeatGauges(SeatRepository seats, MeterRegistry registry) {
        this.seats = seats;
        this.available = MultiGauge.builder("seats.available").description("seats currently available").register(registry);
        this.held = MultiGauge.builder("seats.held").description("seats currently held").register(registry);
        this.confirmed = MultiGauge.builder("seats.confirmed").description("seats currently confirmed").register(registry);
        this.total = MultiGauge.builder("seats.total").description("seats in the show").register(registry);
        registry.gauge("seat.invariant.violations", this, g -> g.violations);
    }

    @Scheduled(fixedDelayString = "${booking.metrics.gauge-refresh-ms:1000}")
    public void refresh() {
        try {
            List<MultiGauge.Row<?>> a = new ArrayList<>(), h = new ArrayList<>(), c = new ArrayList<>(), t = new ArrayList<>();
            int bad = 0;
            for (Object[] row : seats.countsByShow()) {
                Tags tags = Tags.of("show_id", String.valueOf(row[0]));
                long tot = ((Number) row[1]).longValue();
                long av = ((Number) row[2]).longValue();
                long he = ((Number) row[3]).longValue();
                long co = ((Number) row[4]).longValue();
                a.add(MultiGauge.Row.of(tags, av));
                h.add(MultiGauge.Row.of(tags, he));
                c.add(MultiGauge.Row.of(tags, co));
                t.add(MultiGauge.Row.of(tags, tot));
                if (av + he + co != tot) {
                    bad++;
                }
            }
            available.register(a, true);
            held.register(h, true);
            confirmed.register(c, true);
            total.register(t, true);
            if (bad > 0 && violations == 0) {
                log.atError().addKeyValue("shows", bad).log("seat reconciliation invariant violated");
            }
            violations = bad;
        } catch (RuntimeException e) {
            log.atWarn().addKeyValue("error", e.toString()).log("seat gauge refresh failed");
        }
    }
}

