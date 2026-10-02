# Seat Reservation at Scale

A JSON HTTP service that sells assigned seats for a show and stays correct when thousands of buyers
stampede the same seats at once. No seat is ever sold twice, the per-user limit holds, and a retried
request never books twice. Java 21, Spring Boot 4, Spring Data JPA, MySQL (InnoDB), Flyway, Micrometer/Prometheus.

- **Live URL:** https://seat-reserve-service-project-production.up.railway.app
- **Design and trade-offs:** [WRITEUP.md](WRITEUP.md)
- **Burst results:** [docs/burst-local-20k.txt](docs/burst-local-20k.txt) (full default burst on a clean `docker compose` checkout)

```bash
curl https://seat-reserve-service-project-production.up.railway.app/readyz
```

## Run it locally (clean checkout)

You need Docker. The compose file builds the same image that Railway deploys, and runs it with MySQL 8.4:

```bash
docker compose up --build          # API on http://localhost:8080
```

Local defaults: admin key `dev-admin-key`, and the dev token issuer is on.

To run without Docker you need JDK 21 and a MySQL 8+ server. Set `MYSQLHOST`, `MYSQLPORT`,
`MYSQLDATABASE`, `MYSQLUSER`, `MYSQLPASSWORD`, `ADMIN_KEY` and `AUTH_SECRET` (at least 32 bytes), then:
```bash
./mvnw -DskipTests package && java -jar target/*.jar
```
Flyway creates the schema on first start.

## Quick start (curl)

`ADMIN_KEY` is a secret you set in the environment, not something the server issues. Generate one with
`openssl rand -hex 32` and set it as the `ADMIN_KEY` variable (locally it defaults to `dev-admin-key`).
User tokens are minted by the server through `POST /auth/token`, which only works while
`AUTH_DEV_ISSUER_ENABLED=true`.

```bash
BASE=https://seat-reserve-service-project-production.up.railway.app   # or http://localhost:8080
ADMIN_KEY=<your admin key>

# 1. create a show (admin)
curl -s -X POST $BASE/shows -H 'Content-Type: application/json' -H "X-Admin-Key: $ADMIN_KEY" \
  -d '{"name":"friday-night","seats":["A1","A2","A3"],"price_paise":25000}'
#   -> {"id":"<show_id>", ..., "counts":{"available":3,"held":0,"confirmed":0,"total":3}, ...}

# 2. mint a user token (signed HS256 JWT, valid 12h)
curl -s -X POST $BASE/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
#   -> {"token":"<jwt>", ...}

# 3. reserve (identity comes from the token, never the body)
curl -s -X POST $BASE/shows/<show_id>/reserve -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer <jwt>' -d '{"seats":["A1"],"idempotency_key":"order-1"}'
#   -> 201 {"reservation_id":"…","seats":["A1"],"amount_paise":25000,"status":"confirmed", ...}
#      same request again -> 200 replay (Idempotent-Replayed: true)

# 4. show state, then cancel
curl -s $BASE/shows/<show_id>
curl -s -X POST $BASE/reservations/<reservation_id>/cancel -H 'Authorization: Bearer <jwt>'
```

## One-command burst

```bash
./burst.sh <BASE_URL> --admin-key <ADMIN_KEY>
```
Examples:
```bash
./burst.sh http://localhost:8080                       # against docker compose (default dev key)
make burst-live ADMIN_KEY=<key>                        # against the live Railway URL
./burst.sh <URL> --total 20000 --concurrency 1000 --storm 500 --hot-seats 5   # the defaults
```
It's a single-file Java 21 program ([src/burst/Burst.java](src/burst/Burst.java)) with no build step and no
dependencies. It creates a fresh 2,000-seat show and fires these workloads at it, interleaved and all
starting at t=0:

| phase | what it does |
|---|---|
| hot-storm | `--storm` distinct users per hot seat (A1..A5), all grabbing the same seat |
| stampede | the rest of the volume: random 1–2 seat requests over the hall |
| idem-retry | 300 users each send an identical request (same key) 5 times concurrently |
| key-reuse | 200 users each send one key with two *different* seat sets, concurrently |
| per-user-limit | 50 users each fire 10 parallel single-seat reserves on a limit-4 show |
| spoof | requests whose body claims to be another user |

While it runs, a monitor polls `GET /shows/{id}` and checks the invariant on every sample. Afterwards it
tries 100 cross-user cancels, then prints the outcome distribution (confirmed / declined by reason /
5xx / transport errors), the final reconciliation, and a PASS/FAIL line for every requirement in the
correctness bar. It also cross-checks `/actuator/prometheus` against the API and its own view.

Latest results:

| target | load | result |
|---|---|---|
| `docker compose`, clean checkout | 20,000 requests, concurrency 1,000, storm 500 × 5 seats | **13/13 PASS**, 0 × 5xx, 2,024 req/s, p99 2.3 s |
| Railway (live) | 5,000 requests, concurrency 300, storm 200 × 5 seats | 0 × 5xx, all invariant/correctness checks pass ¹ |

¹ That run came from a corporate network that drops some outbound connections under load. 32 requests
never connected, which tripped the two "every request got an answer" checks. The server returned no
5xx and no seat had more than one winner.

## API

All bodies are JSON. Money is integer paise. Every response carries `X-Request-Id`, and an inbound
`X-Request-Id` is honoured.

| method & path | auth | result |
|---|---|---|
| `POST /shows` | `X-Admin-Key` | 201 show; every seat `available`. Body: `{"name","seats":[...],"price_paise", "per_user_limit"?}` (default limit 4) |
| `GET /shows/{id}` | none | per-seat status + `counts` {available, held, confirmed, total} |
| `POST /auth/token` | none (dev only, `AUTH_DEV_ISSUER_ENABLED=true`) | `{"user_id":"alice"}` → HS256 JWT. Stands in for a real IdP |
| `POST /shows/{id}/reserve` | `Bearer` token | `{"seats":["A12"],"idempotency_key":"…"}` (or an `Idempotency-Key` header) |
| `GET /reservations/{id}` | `Bearer` token (owner only) | the reservation |
| `POST /reservations/{id}/cancel` | `Bearer` token (owner only) | releases the seats; idempotent |
| `GET /livez`, `GET /readyz` | none | liveness / readiness |
| `GET /actuator/prometheus` | none | metrics |

Reserve outcomes:

| status | `error` | meaning |
|---|---|---|
| 201 | none | confirmed: `{reservation_id, show_id, user_id, seats, amount_paise, status:"confirmed"}` |
| 200 | none | idempotent replay: the original reservation, plus header `Idempotent-Replayed: true` |
| 409 | `seat_taken` | at least one requested seat is not available; **nothing** is reserved (all-or-nothing) |
| 409 | `per_user_limit` | this would take the user past the show's limit |
| 409 | `idempotency_key_conflict` | the key was already used with a different seat set |
| 422 | `unknown_seat` | a seat doesn't exist in this show |
| 400 / 401 / 404 | various | bad input / missing or invalid token / unknown show |
| 503 | `service_unavailable` | DB unreachable; `Retry-After: 2`; retry with the same key |

Identity comes **only** from the token's `sub` claim. A `user_id` field in the body is ignored. Cancel
and read are owner-only, and someone else's reservation looks exactly like a missing one (404).

## Observability (live)

**Health**
- `/livez`: the process is up. It doesn't depend on the DB, so a DB outage never gets the app restarted.
- `/readyz`: checks the database out of the pool, with a 2 s timeout and a schema check. It returns
  **503 DOWN within milliseconds when MySQL is unreachable** and recovers by itself. It shows its components:
  ```json
  {"components":{"database":{"details":{"database":"MySQL","latency_ms":7},"status":"UP"},"readinessState":{"status":"UP"}},"status":"UP"}
  ```

**Metrics:** https://seat-reserve-service-project-production.up.railway.app/actuator/prometheus

| metric | type | meaning |
|---|---|---|
| `reservations_confirmed_total{show_id}` | counter | successful reservations (201) |
| `seats_sold_total{show_id}` | counter | seats confirmed by those reservations |
| `reservations_declined_total{show_id,reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_conflict`, `unknown_seat`, … |
| `seats_available` / `seats_held` / `seats_confirmed` / `seats{show_id}` | gauge | live seat state per show, refreshed every second from the DB |
| `seat_invariant_violations` | gauge | shows where available + held + confirmed ≠ total. **Must be 0** |
| `http_server_requests_seconds_*{uri,status}` | histogram | latency and status codes, incl. 5xx |
| `hikaricp_connections_active/pending` | gauge | DB pool saturation |

Counters are recorded only after the transaction commits, so they reconcile exactly with the API. The
burst tool checks this.

**Logs:** structured JSON (ECS), one line per request plus one per reservation decision. Each line carries
`request_id`, `user_id` and `outcome`:
```json
{"@timestamp":"…","log":{"level":"INFO","logger":"…ReservationService"},"message":"reservation decision","user_id":"alice-mur313g84q7q","request_id":"ba61f889-…","outcome":"confirmed","seats":"[A1]"}
{"@timestamp":"…","log":{"level":"INFO","logger":"access"},"message":"request completed","request_id":"3ffab759-…","http":{"method":"POST","path":"/shows","status":201},"duration_ms":246.1}
```
Railway has no public log URL. Project members can view live logs in the dashboard (service → Deploy Logs,
filterable by `request_id`) or with `railway logs`. You can trace any request: take the `X-Request-Id`
from a response and search the logs for it. Locally, use `docker compose logs -f app`.

## Tests

- **Postman** ([src/postman](src/postman)): 29 requests and 116 assertions, self-cleaning (teardown cancels
  everything it booked).
  ```bash
  make postman                         # local (docker compose)
  make postman-live ADMIN_KEY=<key>    # live URL
  ```
- **JUnit:** [UnitTest](src/test/java/dev/seats/UnitTest.java) and
  [ConcurrencyTest](src/test/java/dev/seats/ConcurrencyTest.java). The concurrency test needs a local
  MySQL ([src/scripts/mysql-local.sh](src/scripts/mysql-local.sh)).

## Deploy (Railway)

The service builds from the root `Dockerfile`, alongside a Railway MySQL service. App variables:

| variable | value |
|---|---|
| `MYSQLHOST` / `MYSQLPORT` / `MYSQLUSER` / `MYSQLPASSWORD` / `MYSQLDATABASE` | the MySQL service's connection values |
| `ADMIN_KEY` | random secret for `POST /shows` |
| `AUTH_SECRET` | random, at least 32 bytes (JWT signing key) |
| `AUTH_DEV_ISSUER_ENABLED` | `true` only while running bursts (it lets anyone mint a token) |
| `JAVA_OPTS` | `-Xmx400m -Xss512k -XX:MaxMetaspaceSize=192m -XX:ActiveProcessorCount=2` |
| `DB_POOL_SIZE` | `20` |

The `JAVA_OPTS` cap is required. The JVM sees the whole host (48 CPUs, ~31 GB max heap) rather than
the container limit, and without the cap it was OOM-killed mid-burst. That caused 502s for 72% of requests.