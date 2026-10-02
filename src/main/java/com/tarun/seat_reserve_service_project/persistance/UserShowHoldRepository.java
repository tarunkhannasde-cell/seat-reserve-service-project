package com.tarun.seat_reserve_service_project.persistance;


import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserShowHoldRepository extends JpaRepository<UserShowHoldEntity, UserShowHoldEntity.Key> {

    /**
     * Make sure the counter row exists. Run in its own short transaction BEFORE the reservation
     * transaction, so the main transaction only ever takes an exclusive lock on an existing row.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO user_show_holds (show_id, user_id, seats_held) VALUES (:showId, :userId, 0)",
            nativeQuery = true)
    int ensureRow(@Param("showId") String showId, @Param("userId") String userId);

    /** Conditional increment: 1 = within the limit and applied, 0 = would exceed the limit. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update UserShowHoldEntity h set h.seatsHeld = h.seatsHeld + :n
            where h.showId = :showId and h.userId = :userId and h.seatsHeld + :n <= :limit
            """)
    int addIfWithinLimit(@Param("showId") String showId, @Param("userId") String userId,
                         @Param("n") int n, @Param("limit") int limit);

    @Modifying(flushAutomatically = true)
    @Query("update UserShowHoldEntity h set h.seatsHeld = h.seatsHeld - :n where h.showId = :showId and h.userId = :userId")
    int release(@Param("showId") String showId, @Param("userId") String userId, @Param("n") int n);
}
