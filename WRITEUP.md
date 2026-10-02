# WRITEUP: Seat Reservation at Scale

Live: https://seat-reserve-service-project-production.up.railway.app · Stack: Java 21, Spring Boot 4,
Spring Data JPA, MySQL/InnoDB (READ COMMITTED), Flyway, Micrometer.

## 1. The atomic decision

**The mechanism.** Each reservation is one database transaction that takes InnoDB row locks in a fixed
global order. Every write in it is *guarded*: it checks the state it expects in its own `WHERE` clause.
([ReservationService.decide](src/main/java/com/tarun/seat_reserve_service_project/reservations/ReservationService.java))

1. **Claim the idempotency slot.** `INSERT` the reservation row. `UNIQUE (user_id, idempotency_key)`
   means a concurrent twin with the same key blocks on that index entry (see §2).
2. **Lock the seats.** `SELECT … FROM seats WHERE show_id=? AND label IN (…) ORDER BY label FOR UPDATE`
   (JPA `PESSIMISTIC_WRITE`). Under the lock InnoDB returns the latest *committed* row, so the status we
   check can't be stale. If any seat isn't `available`, we throw and the whole transaction, including the
   reservation row, rolls back.
3. **Guarded update.** `UPDATE seats SET status='confirmed', reservation_id=?, user_id=? WHERE … AND
   status='available'`. We require that it touched exactly *n* rows. This is a second guard, so even a
   bug in step 2 couldn't double-sell.
4. **Per-user limit.** `UPDATE user_show_holds SET seats_held = seats_held + n WHERE show_id=? AND
   user_id=? AND seats_held + n <= limit`. If 0 rows change, it's `409 per_user_limit` and everything
   rolls back. This row lock serialises only *that user's* parallel requests. (The counter row is created
   beforehand by `INSERT IGNORE … seats_held = 0` in its own short transaction, so step 4 always has a
   row to lock.)

**Why it's race-free.** The schema can't represent a double-sell. `PRIMARY KEY (show_id, label)` means
each seat exists exactly once. A single `reservation_id` column means it has at most one owner, and
`CHECK ((status='available') = (reservation_id IS NULL))` ties ownership to status. Two buyers racing for
A12 queue on the same row lock. The first commits `confirmed`. The second then reads the committed row
under its own lock, sees `confirmed`, and gets a clean `409 seat_taken`. Nothing reads first and writes
later outside a lock: the only unlocked read (below) is allowed to decline, never to grant.

**Hot-seat fast path.** Before taking locks, an unlocked `SELECT status` declines straight away if a seat
is already taken. When 500 people storm A12, the 499 losers mostly never queue on the row lock, which is
why a storm finishes with 1 × 201 and 499 × 409 rather than a pile-up of lock waits. A stale read here
can only cause an extra trip into the locked path, where the real decision is made.

**Multi-seat and deadlocks: all-or-nothing.** `["A12","A13"]` with A13 taken books nothing (409 listing
the taken seats). Seats are always locked in primary-key order (`label`, with `utf8mb4_bin` collation, so
the order is binary and deterministic). Every transaction acquires seat locks in the same order, so there
can't be a lock cycle between seat locks. The global order is: reservation/idempotency row → seats (in
label order) → user counter. Cancel uses the same order (reservation row → its seats → counter). One
deadlock is still possible: several same-key twins waiting on the unique index while the first one rolls
back. InnoDB picks a victim. We retry the whole attempt from the top (up to 8 times with backoff), and the
retry usually ends as a replay or a clean decline. Retries are logged at WARN.

**Seen live.** Every hot seat in every burst got exactly one 201. The schema checks and
`seat_invariant_violations` stayed at 0.

## 2. Idempotency

- **Where the key lives.** In the `reservations` row itself (`idempotency_key`, `request_hash`), behind
  `UNIQUE (user_id, idempotency_key)`. Keys are scoped per user, so one user can't collide with or probe
  another user's keys. The key comes from the body `idempotency_key` or the `Idempotency-Key` header
  (both are accepted; if both are present and differ, it's a 400).
- **Exactly once.** The key is claimed by an `INSERT` *inside* the reservation transaction. Exactly one
  insert per key can commit. A concurrent twin waits on the index entry, then either fails with a
  duplicate key (the first one committed: we load that row and return it) or goes ahead (the first rolled
  back, e.g. its seat was taken, so nothing was reserved and the key is still free). Because the slot,
  the seats and the counter commit together, "key used but no seats" and "seats taken but key unused"
  can't happen.
- **Replay.** A retry with the same key and same request returns **200** with the original reservation
  unchanged, plus `Idempotent-Replayed: true`. It moves no seats and no counters, and it's counted as
  `reservations_declined_total{reason="idempotent_replay"}`.
- **Same key, different body.** `request_hash` is the SHA-256 of `show_id | sorted seat set`, so seat
  order doesn't matter. A different hash for an existing key gets a `409 idempotency_key_conflict`.
- **Declines aren't stored.** If a request failed (seat taken), retrying the same key re-evaluates it.
  That's deliberate: the first attempt changed nothing, so there's nothing to replay.

## 3. Holds and expiry

**Model: confirm-on-reserve plus an explicit owner-only cancel** (`POST /reservations/{id}/cancel`).
Reservations go straight to `confirmed`. There's no payment step to wait for, so a TTL hold would add a
sweeper and a second state machine without adding correctness. The `held` status exists in the schema and
in every count and gauge, so TTL holds can be added without a migration (see §7).

**Cancel.** It locks the reservation row (404 for anyone but the owner, indistinguishable from a missing
reservation), then that reservation's seats in label order. It releases with
`UPDATE seats SET status='available', reservation_id=NULL … WHERE reservation_id = :this`, decrements the
user's counter by the number of rows actually released, and marks the reservation `cancelled`. Because
the release is scoped to `reservation_id = this reservation`, a cancel can never free a seat that now
belongs to someone else. A second cancel is an idempotent 200. A released seat is immediately re-bookable
through the normal locked path (the Postman suite re-books it).

## 4. Consistency vs. availability under a partition

This is a **CP system by choice**. Selling a seat twice is the failure that matters, and turning a buyer
away for a few seconds is the acceptable one. There's a single MySQL primary that's the only source of
truth. Nothing makes a seat decision from a cache, a replica or local state.

- **App can't reach the DB.** Reserve and cancel fail closed with `503 service_unavailable` and
  `Retry-After: 2`. They never return a guess and never a 500. `/readyz` goes DOWN within milliseconds
  (an out-of-pool 2 s probe), so the load balancer stops sending traffic, while `/livez` stays UP so the
  platform doesn't restart a healthy process. Clients retry **with the same idempotency key**, so a
  request that actually committed before the partition is replayed, not booked again. (Tested by cutting
  the DB connection under a running instance: 503 for 8 ms, then 200 once the connection came back.)
- **Ambiguous commits** (the commit happened, the response was lost) are exactly the case idempotency
  covers. The retry finds the committed row.
- **What we give up.** During a DB outage nobody can buy. Scaling reads (e.g. `GET /shows/{id}` from a
  replica) would be fine because they're advisory. The reserve path must stay on the primary.

## 5. Observability: what I'd get paged for at 2am

The metrics are at `/actuator/prometheus`, and the README has the full list. Counters increment only after
commit, so they reconcile *exactly* with the API. The burst tool checks client view = metrics = API state.

**Page someone:**
1. `seat_invariant_violations > 0`: available + held + confirmed ≠ total for some show. The data is
   corrupt. Stop the on-sale.
2. Any `http_server_requests_seconds_count{status=~"5.."}` during an on-sale. Declines are 4xx by design,
   so a 5xx is a bug or an outage.
3. `/readyz` DOWN for more than 1 minute (DB unreachable or not migrated).
4. Container restarts or OOM kills (`process_uptime_seconds` resetting). That's exactly what took down
   72% of the first live burst (see below).

**Ticket, don't page:** `hikaricp_connections_pending` staying high (pool saturation, so p99 climbs), p99
reserve latency above 2 s, and a rising rate of "lock conflict, retrying" WARN logs.

**Logs** are structured ECS JSON. Every line has a `request_id` (generated, or the client's `X-Request-Id`
if sent; always echoed back) and, where relevant, `user_id` and `outcome`. There's one access line per
request and one `reservation decision` line per reserve or cancel, so any single response can be traced.

**A real incident from deployment.** The first live burst got 2,059 × 502 (72%). Prometheus showed why:
`system_cpu_count 48` and a max heap of about 31 GB. The JVM was sizing itself for the Railway *host*, not
the container, and got OOM-killed mid-burst (`process_uptime_seconds` 69 s straight afterwards). Capping
it (`-Xmx400m -XX:ActiveProcessorCount=2`) and setting the pool to 20 fixed it: 0 × 5xx on the next runs.

## 6. AI usage: what was directed vs. what was decided

AI (Claude Code) was used in this project, mainly as an implementation and productivity tool.

- **Decided by me:** the design. That covers where the atomic decision lives (lock-ordered `SELECT … FOR
  UPDATE` plus guarded updates in a single transaction), all-or-nothing semantics for multi-seat requests,
  the idempotency model (a per-user unique key claimed by an INSERT inside the reservation transaction, a
  request hash for same-key-different-body, 200 for replays), the conditional per-user counter,
  owner-scoped cancel rather than TTL holds, CP behaviour under a DB partition, and the stack (Java 21,
  Spring Boot, Spring Data JPA on MySQL, Railway).
- **Done with AI:** boilerplate and scaffolding. That means the JPA entities and repositories, controllers
  and DTOs, error mapping, the metrics and logging wiring, the Flyway migration from my schema, the
  Postman collection, the burst tool's HTTP plumbing and report formatting, the Dockerfile/compose files,
  and drafting this README and WRITEUP. I reviewed the generated code against the design and checked it
  with Postman and burst runs.
- **Deployment:** I used AI to help with Railway configuration issues (database connection variables,
  JVM memory limits for the container) and with log and metric analysis while load-testing the live service.

## 7. What I'd do next

1. **TTL holds plus payment.** Reserve creates `held` seats with `expires_at`, and
   `POST /reservations/{id}/confirm` makes them `confirmed`. A sweeper releases expired holds with
   `UPDATE … WHERE status='held' AND expires_at < now() AND reservation_id = ?`. That's the same guarded,
   owner-scoped write, so expiry can never touch a confirmed or resold seat.
2. **A waiting room / admission control** per show (a token bucket) in front of the on-sale, so the DB sees
   a bounded arrival rate rather than a thundering herd. Plus per-user rate limits against bots.
3. **Optional best-effort mode** (`"mode":"best_effort"`) using `FOR UPDATE SKIP LOCKED` to take whatever
   subset of the requested seats is free.
4. **A reconciliation job** that cross-checks `seats` against `reservations` and the per-user counters,
   and exports any drift as a gauge.
5. **Operational hardening.** Use private networking to the DB instead of the public proxy, alert rules
   checked into the repo, a Grafana dashboard for the burst metrics, and a real IdP so the dev token
   issuer can be removed.
