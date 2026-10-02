package com.tarun.seat_reserve_service_project.persistance;


import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** One physical seat. Its state only ever changes through the guarded updates in {@link SeatRepository}. */
@Entity
@Table(name = "seats")
@IdClass(SeatEntity.Key.class)
public class SeatEntity extends NewAware<SeatEntity.Key> {

    public static final String AVAILABLE = "available";
    public static final String HELD = "held";
    public static final String CONFIRMED = "confirmed";

    @Id
    @Column(name = "show_id", length = 36)
    private String showId;

    @Id
    @Column(length = 16)
    private String label;

    @Column(nullable = false)
    private int position;

    @Column(nullable = false, length = 16)
    private String status;

    @Column(name = "reservation_id", length = 36)
    private String reservationId;

    @Column(name = "user_id", length = 64)
    private String userId;

    protected SeatEntity() {
    }

    public static SeatEntity available(String showId, String label, int position) {
        SeatEntity s = new SeatEntity();
        s.showId = showId;
        s.label = label;
        s.position = position;
        s.status = AVAILABLE;
        s.markNew();
        return s;
    }

    @Override
    public Key getId() {
        return new Key(showId, label);
    }

    public String getLabel() {
        return label;
    }

    public String getStatus() {
        return status;
    }

    public static class Key implements Serializable {
        private String showId;
        private String label;

        protected Key() {
        }

        public Key(String showId, String label) {
            this.showId = showId;
            this.label = label;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(showId, k.showId) && Objects.equals(label, k.label);
        }

        @Override
        public int hashCode() {
            return Objects.hash(showId, label);
        }
    }
}
