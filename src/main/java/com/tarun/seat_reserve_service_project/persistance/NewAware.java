package com.tarun.seat_reserve_service_project.persistance;


import org.springframework.data.domain.Persistable;

import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Transient;

/**
 * Our entities use application-assigned ids, so Spring Data cannot infer "new" from a null id and
 * would call merge() (a SELECT per row) on save. Tracking newness explicitly makes save() a plain
 * persist -> INSERT, which is also what lets the idempotency INSERT fail fast on the unique key.
 */
@MappedSuperclass
public abstract class NewAware<ID> implements Persistable<ID> {

    @Transient
    private boolean isNew = false;

    protected void markNew() {
        this.isNew = true;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markPersisted() {
        this.isNew = false;
    }
}
