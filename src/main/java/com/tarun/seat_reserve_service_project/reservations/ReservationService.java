package com.tarun.seat_reserve_service_project.reservations;


import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.tarun.seat_reserve_service_project.error.ApiException;
import com.tarun.seat_reserve_service_project.metrics.BookingMetrics;
import com.tarun.seat_reserve_service_project.persistance.*;
import com.tarun.seat_reserve_service_project.show.Show;
import com.tarun.seat_reserve_service_project.show.ShowService;

/**
 * The system of record for who owns each seat (Spring Data JPA on MySQL/InnoDB).
 *
 * <h2>The atomic decision</h2>
 * One READ COMMITTED transaction per reservation, taking locks in a single global order:
 * <ol>
 *   <li><b>Idempotency slot</b> — persist the reservation row; UNIQUE (user_id, idempotency_key) makes a
 *       concurrent duplicate wait on the index entry, then fail with a duplicate key.</li>
 *   <li><b>Seats</b> — {@code SELECT ... FOR UPDATE} (JPA PESSIMISTIC_WRITE) on every requested seat,
 *       acquired in primary-key = label order so multi-seat requests never deadlock. Rows returned
 *       under the lock are the latest committed versions, so the status check cannot be stale.
 *       Then a guarded {@code UPDATE ... WHERE status = 'available'} (belt and braces).</li>
 *   <li><b>Per-user counter</b> — conditional {@code UPDATE ... SET seats_held = seats_held + n
 *       WHERE seats_held + n <= limit}.</li>
 * </ol>
 * Any decline throws, rolling back everything: requests are <b>all-or-nothing</b>.
 * The unlocked pre-check can only <i>decline</i> (a fast path for hot seats), never grant.
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);
    private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[\\x21-\\x7E]{1,128}");
    private static final int MAX_SEATS_PER_REQUEST = 200;
    private static final int MAX_ATTEMPTS = 8;

    private final ReservationRepository reservations;
    private final SeatRepository seats;
    private final UserShowHoldRepository holds;
    private final TransactionTemplate tx;
    private final ShowService shows;
    private final BookingMetrics metrics;

    public ReservationService(ReservationRepository reservations, SeatRepository seats, UserShowHoldRepository holds,
                              TransactionTemplate tx, ShowService shows, BookingMetrics metrics) {
        this.reservations = reservations;
        this.seats = seats;
        this.holds = holds;
        this.tx = tx;
        this.shows = shows;
        this.metrics = metrics;
    }

    public record ReserveResult(Reservation reservation, boolean replay) {
    }

    public record CancelResult(Reservation reservation, boolean alreadyCancelled) {
    }

    // ------------------------------------------------------------------ reserve

    public ReserveResult reserve(String userId, UUID showId, List<String> requestedSeats, String idempotencyKey) {
        Show show = shows.get(showId);
        try {
            if (idempotencyKey == null || !IDEMPOTENCY_KEY.matcher(idempotencyKey).matches()) {
                throw ApiException.badRequest("idempotency_key is required (1-128 printable ASCII chars)");
            }
            List<String> wanted = validateSeats(requestedSeats);
            if (wanted.size() > show.perUserLimit()) {
                throw perUserLimit(show);
            }
            String requestHash = requestHash(show.id(), wanted);

            ReserveResult result = withRetry(() -> attempt(userId, show, wanted, idempotencyKey, requestHash));
            if (result.replay()) {
                metrics.declined(showId, BookingMetrics.IDEMPOTENT_REPLAY);
                outcome(BookingMetrics.IDEMPOTENT_REPLAY, result.reservation().seats());
            } else {
                metrics.confirmed(showId, result.reservation().seats().size());
                outcome("confirmed", result.reservation().seats());
            }
            return result;
        } catch (ApiException e) {
            metrics.declined(showId, e.code());
            outcome(e.code(), requestedSeats);
            throw e;
        }
    }

    /** One full attempt. Re-run from the top on a lock conflict, so a retry re-reads everything. */
    private ReserveResult attempt(String userId, Show show, List<String> wanted, String idempotencyKey, String requestHash) {
        String showId = show.id().toString();

        // 1. Retry of a request that already succeeded? Answer from the stored reservation.
        Optional<ReservationEntity> existing = reservations.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
        if (existing.isPresent()) {
            return replayOrConflict(existing.get(), requestHash);
        }

        // 2. Unlocked fast path: may only DECLINE. Keeps the 499 losers of a hot-seat storm off the row lock.
        Map<String, String> current = new HashMap<>();
        seats.findStatuses(showId, wanted).forEach(s -> current.put(s.getLabel(), s.getStatus()));
        List<String> unknown = wanted.stream().filter(s -> !current.containsKey(s)).toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, BookingMetrics.UNKNOWN_SEAT,
                    "seats do not exist in this show", unknown);
        }
        List<String> taken = wanted.stream().filter(s -> !SeatEntity.AVAILABLE.equals(current.get(s))).toList();
        if (!taken.isEmpty()) {
            // The seat may be taken by our own committed retry-twin; re-check the key before declining.
            Optional<ReservationEntity> raced = reservations.findByUserIdAndIdempotencyKey(userId, idempotencyKey);
            if (raced.isPresent()) {
                return replayOrConflict(raced.get(), requestHash);
            }
            throw seatTaken(taken);
        }

        // 3. Make sure the per-user counter row exists (own short transaction, see UserShowHoldRepository).
        tx.executeWithoutResult(s -> holds.ensureRow(showId, userId));

        // 4. The atomic decision.
        try {
            return tx.execute(s -> decide(userId, show, wanted, idempotencyKey, requestHash));
        } catch (DataIntegrityViolationException e) {
            if (!isIdempotencyDuplicate(e)) {
                throw e;
            }
            // A twin with the same key committed first: return its reservation (or 409 if the body differs).
            return reservations.findByUserIdAndIdempotencyKey(userId, idempotencyKey)
                    .map(r -> replayOrConflict(r, requestHash))
                    .orElseThrow(() -> new CannotAcquireLockException("idempotency twin not visible yet"));
        }
    }

    private ReserveResult decide(String userId, Show show, List<String> wanted, String idempotencyKey, String requestHash) {
        String showId = show.id().toString();
        String reservationId = UUID.randomUUID().toString();
        long amount = Math.multiplyExact(show.pricePaise(), (long) wanted.size());

        // (a) claim the idempotency slot: INSERT now, so a concurrent twin blocks on the unique key
        ReservationEntity row = ReservationEntity.confirmed(reservationId, showId, userId, wanted, amount,
                idempotencyKey, requestHash);
        reservations.saveAndFlush(row);

        // (b) lock every requested seat (label order), then check under the lock
        List<String> takenUnderLock = seats.lockSeats(showId, wanted).stream()
                .filter(s -> !SeatEntity.AVAILABLE.equals(s.getStatus()))
                .map(SeatEntity::getLabel)
                .toList();
        if (!takenUnderLock.isEmpty()) {
            throw seatTaken(takenUnderLock); // rollback also discards the reservation row from (a)
        }
        int updated = seats.confirmIfAvailable(showId, wanted, reservationId, userId);
        if (updated != wanted.size()) {
            throw new IllegalStateException("guarded seat update touched " + updated + " of " + wanted.size());
        }

        // (c) per-user limit: conditional increment, serialises only this user's concurrent requests
        if (holds.addIfWithinLimit(showId, userId, wanted.size(), show.perUserLimit()) == 0) {
            throw perUserLimit(show);
        }
        return new ReserveResult(Reservation.from(row), false);
    }

    // ------------------------------------------------------------------ cancel

    /**
     * Owner-only explicit release. Lock order: reservation row -> its seats (label order) -> user counter.
     * Seats are released only WHERE reservation_id = this reservation, so a cancel can never free a
     * seat that now belongs to someone else.
     */
    public CancelResult cancel(String userId, UUID reservationId) {
        String id = reservationId.toString();
        CancelResult result = withRetry(() -> tx.execute(status -> {
            ReservationEntity r = reservations.lockById(id).orElse(null);
            if (r == null || !r.getUserId().equals(userId)) {
                // not-owner is indistinguishable from not-found: don't leak other users' reservation ids
                throw ApiException.notFound("reservation_not_found", "no such reservation for this user");
            }
            Reservation current = Reservation.from(r);
            if (ReservationEntity.CANCELLED.equals(r.getStatus())) {
                return new CancelResult(current, true);
            }
            seats.lockSeatsOfReservation(r.getShowId(), id);
            int released = seats.releaseReservation(r.getShowId(), id);
            holds.release(r.getShowId(), userId, released);
            reservations.markCancelled(id, Instant.now());
            return new CancelResult(current.withStatus(ReservationEntity.CANCELLED), false);
        }));
        if (!result.alreadyCancelled()) {
            metrics.cancelled(result.reservation().showId(), result.reservation().seats().size());
        }
        outcome(result.alreadyCancelled() ? "already_cancelled" : "cancelled", result.reservation().seats());
        return result;
    }

    public Reservation getOwned(String userId, UUID reservationId) {
        return reservations.findById(reservationId.toString())
                .filter(r -> r.getUserId().equals(userId))
                .map(Reservation::from)
                .orElseThrow(() -> ApiException.notFound("reservation_not_found", "no such reservation for this user"));
    }

    // ------------------------------------------------------------------ helpers

    private static ReserveResult replayOrConflict(ReservationEntity existing, String requestHash) {
        if (!existing.getRequestHash().equals(requestHash)) {
            throw new ApiException(HttpStatus.CONFLICT, BookingMetrics.IDEMPOTENCY_CONFLICT,
                    "idempotency_key was already used with a different request");
        }
        return new ReserveResult(Reservation.from(existing), true);
    }

    private static boolean isIdempotencyDuplicate(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(ReservationRepository.IDEMPOTENCY_CONSTRAINT)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> validateSeats(List<String> seats) {
        if (seats == null || seats.isEmpty()) {
            throw ApiException.badRequest("seats must be a non-empty array");
        }
        if (seats.size() > MAX_SEATS_PER_REQUEST) {
            throw ApiException.badRequest("at most " + MAX_SEATS_PER_REQUEST + " seats per request");
        }
        Set<String> seen = new HashSet<>();
        for (String seat : seats) {
            if (seat == null || !ShowService.SEAT_LABEL.matcher(seat).matches()) {
                throw ApiException.badRequest("invalid seat label: " + seat);
            }
            if (!seen.add(seat)) {
                throw ApiException.badRequest("duplicate seat in request: " + seat);
            }
        }
        return List.copyOf(seats);
    }

    /** Canonical fingerprint of "what was asked for": show + the SET of seats (order-insensitive). */
    private static String requestHash(UUID showId, List<String> seats) {
        String canonical = showId + "|" + String.join(",", new TreeSet<>(seats));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ApiException seatTaken(List<String> seats) {
        return new ApiException(HttpStatus.CONFLICT, BookingMetrics.SEAT_TAKEN, "seat(s) already taken", seats);
    }

    private static ApiException perUserLimit(Show show) {
        return new ApiException(HttpStatus.CONFLICT, BookingMetrics.PER_USER_LIMIT,
                "per-user limit of " + show.perUserLimit() + " seats for this show would be exceeded");
    }

    /**
     * Seat locks are ordered, so seat deadlocks cannot happen. InnoDB can still pick a deadlock victim
     * on the unique-key check when several same-key twins wait behind one that rolls back (each holds
     * a shared lock on the key and wants an exclusive one). Retrying the whole attempt from the top
     * resolves it correctly - the retry usually ends on the fast path as a replay or a clean decline.
     */
    private <T> T withRetry(Supplier<T> work) {
        for (int attempt = 1; ; attempt++) {
            try {
                return work.get();
            } catch (PessimisticLockingFailureException e) {
                if (attempt >= MAX_ATTEMPTS) {
                    throw e;
                }
                log.atWarn().addKeyValue("attempt", attempt).addKeyValue("error", e.getClass().getSimpleName())
                        .log("lock conflict, retrying transaction");
                try {
                    Thread.sleep(attempt * 5L);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private static void outcome(String outcome, List<String> seats) {
        // outcome goes in the MDC only: a key that is both MDC and key-value makes the ECS writer drop the event
        MDC.put("outcome", outcome);
        log.atInfo().addKeyValue("seats", String.valueOf(seats)).log("reservation decision");
    }
}
