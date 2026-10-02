package com.tarun.seat_reserve_service_project.persistance;


import java.io.Serializable;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;

/** Seats a user currently holds for a show. Only mutated by the conditional updates in its repository. */
@Entity
@Table(name = "user_show_holds")
@IdClass(UserShowHoldEntity.Key.class)
public class UserShowHoldEntity {

    @Id
    @Column(name = "show_id", length = 36)
    private String showId;

    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    @Column(name = "seats_held", nullable = false)
    private int seatsHeld;

    protected UserShowHoldEntity() {
    }

    public int getSeatsHeld() {
        return seatsHeld;
    }

    public static class Key implements Serializable {
        private String showId;
        private String userId;

        protected Key() {
        }

        public Key(String showId, String userId) {
            this.showId = showId;
            this.userId = userId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(showId, k.showId) && Objects.equals(userId, k.userId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(showId, userId);
        }
    }
}

