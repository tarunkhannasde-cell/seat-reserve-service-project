package com.tarun.seat_reserve_service_project.persistance;


import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "shows")
public class ShowEntity extends NewAware<String> {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "price_paise", nullable = false)
    private long pricePaise;

    @Column(name = "per_user_limit", nullable = false)
    private int perUserLimit;

    @Column(name = "total_seats", nullable = false)
    private int totalSeats;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ShowEntity() {
    }

    public static ShowEntity create(String id, String name, long pricePaise, int perUserLimit, int totalSeats) {
        ShowEntity s = new ShowEntity();
        s.id = id;
        s.name = name;
        s.pricePaise = pricePaise;
        s.perUserLimit = perUserLimit;
        s.totalSeats = totalSeats;
        s.createdAt = Instant.now();
        s.markNew();
        return s;
    }

    @Override
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public long getPricePaise() {
        return pricePaise;
    }

    public int getPerUserLimit() {
        return perUserLimit;
    }

    public int getTotalSeats() {
        return totalSeats;
    }
}
