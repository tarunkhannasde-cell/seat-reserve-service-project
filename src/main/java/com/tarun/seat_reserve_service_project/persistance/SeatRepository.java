package com.tarun.seat_reserve_service_project.persistance;


import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SeatRepository extends JpaRepository<SeatEntity, SeatEntity.Key> {

    interface SeatStatus {
        String getLabel();

        String getStatus();
    }

    /** Whole seat map in one SELECT = one consistent snapshot, so the counts always reconcile. */
    @Query("select s.label as label, s.status as status from SeatEntity s where s.showId = :showId order by s.position")
    List<SeatStatus> findSeatMap(@Param("showId") String showId);

    /** Unlocked read for the decline-only fast path. */
    @Query("select s.label as label, s.status as status from SeatEntity s where s.showId = :showId and s.label in :labels")
    List<SeatStatus> findStatuses(@Param("showId") String showId, @Param("labels") Collection<String> labels);

    /**
     * SELECT ... FOR UPDATE on the requested seats. InnoDB locks rows as it walks the primary key
     * (show_id, label), i.e. in label order, so every multi-seat request locks in the same order and
     * two of them can never deadlock. The returned rows are the latest committed versions.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatEntity s where s.showId = :showId and s.label in :labels order by s.label")
    List<SeatEntity> lockSeats(@Param("showId") String showId, @Param("labels") Collection<String> labels);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from SeatEntity s where s.showId = :showId and s.reservationId = :reservationId order by s.label")
    List<SeatEntity> lockSeatsOfReservation(@Param("showId") String showId, @Param("reservationId") String reservationId);

    /** Guarded write: only flips seats that are still available. Returns the number of seats taken. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update SeatEntity s set s.status = 'confirmed', s.reservationId = :reservationId, s.userId = :userId
            where s.showId = :showId and s.label in :labels and s.status = 'available'
            """)
    int confirmIfAvailable(@Param("showId") String showId, @Param("labels") Collection<String> labels,
                           @Param("reservationId") String reservationId, @Param("userId") String userId);

    /** Releases only seats owned by this reservation: a cancel can never free someone else's seat. */
    @Modifying(flushAutomatically = true)
    @Query("""
            update SeatEntity s set s.status = 'available', s.reservationId = null, s.userId = null
            where s.showId = :showId and s.reservationId = :reservationId
            """)
    int releaseReservation(@Param("showId") String showId, @Param("reservationId") String reservationId);

    /** [show_id, total_seats, available, held, confirmed] for every show, from one statement. */
    @Query(value = """
            SELECT sh.id, sh.total_seats,
                   SUM(s.status = 'available'), SUM(s.status = 'held'), SUM(s.status = 'confirmed')
            FROM shows sh JOIN seats s ON s.show_id = sh.id
            GROUP BY sh.id, sh.total_seats
            """, nativeQuery = true)
    List<Object[]> countsByShow();
}
