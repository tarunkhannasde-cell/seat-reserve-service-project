package com.tarun.seat_reserve_service_project.persistance;


import java.time.Instant;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface ReservationRepository extends JpaRepository<ReservationEntity, String> {

    String IDEMPOTENCY_CONSTRAINT = "reservations_user_idem_uq";

    Optional<ReservationEntity> findByUserIdAndIdempotencyKey(String userId, String idempotencyKey);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from ReservationEntity r where r.id = :id")
    Optional<ReservationEntity> lockById(@Param("id") String id);

    @Modifying(flushAutomatically = true)
    @Query("update ReservationEntity r set r.status = 'cancelled', r.cancelledAt = :at where r.id = :id and r.status = 'confirmed'")
    int markCancelled(@Param("id") String id, @Param("at") Instant at);
}
