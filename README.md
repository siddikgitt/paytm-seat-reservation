# Seat Reservation at Scale

A JSON HTTP service for assigned seats, designed to prevent double sales, enforce per-show quotas,
and make reservation retries idempotent. Public capacity limitations are documented below.
Java 21 + Spring Boot 3.3 (virtual threads) + PostgreSQL (16 locally; Northflank provisioned 18). Design notes are in [WRITEUP.md](WRITEUP.md).

**Submission status: NOT READY FOR HR.** The repaired deployment passes the functional burst; the free public deployment still fails the strict 20,000-concurrent gate.
See [verified results and remaining blockers](VERIFICATION.md); the live URL alone is not a passing submission.

**Repository:** https://github.com/siddikgitt/paytm-seat-reservation

**Live service:** [readiness response](https://p01--seat-reservation--wlpk4pp6vqpm.code.run/readyz)

**API base URL:** `https://p01--seat-reservation--wlpk4pp6vqpm.code.run` (the root path is not an API route).

**Live logs under load:** [20-second recording](evidence/northflank-live-logs.mp4).

[Readiness](https://p01--seat-reservation--wlpk4pp6vqpm.code.run/readyz) · [Liveness](https://p01--seat-reservation--wlpk4pp6vqpm.code.run/livez) · [Metrics](https://p01--seat-reservation--wlpk4pp6vqpm.code.run/metrics)

| What | Where |
|---|---|
| Liveness | `GET /livez` |
| Readiness (checks Postgres, fails closed) | `GET /readyz` |
| Prometheus metrics | `GET /metrics` |
| Logs | JSON on stdout, Northflank dashboard > Logs (see [Logs](#logs)) |
| Burst | `./burst.sh <BASE_URL>` or `make burst URL=<BASE_URL>` |

## Quick start (clean checkout)

Requires only Docker. The image is built with the same `Dockerfile` that Render uses.

```bash
make up                       # docker compose up --build, waits for /readyz
./burst.sh http://localhost:8080
make down
```

No JDK is needed locally: `burst.sh` falls back to running the single-file burst program in an
`eclipse-temurin:21-jdk` container, and `make test` runs Maven inside a container.

> On macOS with Colima / Docker Desktop, the default 1000-connection burst against `localhost` goes
> through the VM's port forwarder. A smaller diagnostic run does not meet the strict grading requirement.
> Run the strict client on a machine with sufficient connection and memory capacity.

## API

All bodies are JSON with `snake_case` fields. Money is integer paise. Decimal JSON numbers (including `25000.0`)
are rejected for `price_paise` and `per_user_limit`, rather than rounded or truncated.

### Tokens (stand-in identity provider)

```bash
# user token: identity is the JWT subject, never a request body field
curl -s -X POST $URL/auth/token -H 'Content-Type: application/json' -d '{"user_id":"alice"}'
# -> {"token":"eyJ...","user_id":"alice","role":"user","expires_in":86400}

# admin token (or send X-Admin-Key directly on admin endpoints)
curl -s -X POST $URL/auth/token -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"user_id":"ops","role":"admin"}'
```

### Create a show (admin)

```bash
curl -s -X POST $URL/shows -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"name":"friday-night","seats":["A1","A2","A12","A13"],"price_paise":25000,"per_user_limit":4}'
```
`201` returns the show with `id`, every seat `available`, and `counts`. `per_user_limit` is optional and defaults to 4.

### Reserve

```bash
curl -s -X POST $URL/shows/$SHOW/reserve -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' -d '{"seats":["A12"],"idempotency_key":"7f1c..."}'
```
The idempotency key can also be sent as an `Idempotency-Key` header. If both are present they must match.

| Outcome | Status | `reason` |
|---|---|---|
| New reservation | `201` | (body: `reservation_id, show_id, user_id, seats, amount_paise, status: "confirmed"`) |
| Retry of the same key and same seats | `200`, header `Idempotent-Replayed: true` | (original reservation, unchanged) |
| Any requested seat held or confirmed by someone | `409` | `seat_taken` |
| Would exceed `per_user_limit` | `409` | `per_user_limit` |
| Same key, different seats | `409` | `idempotency_key_reused` |
| Unknown seat, duplicate seat, missing key | `400` | `unknown_seat` / `invalid_request` |
| Missing or invalid token | `401` | `unauthorized` |
| Service saturated (nothing was read or written) | `429`, `Retry-After` | `overloaded` |

**Multi-seat requests are all-or-nothing:** `["A12","A13"]` with A13 taken books nothing and returns `409 seat_taken`.
Seat order doesn't matter for idempotency (`["A13","A12"]` is the same request).
Any `user_id` in the body is ignored; the reservation always belongs to the token's subject.

### Cancel (owner only)

```bash
curl -s -X POST $URL/reservations/$RID/cancel -H "Authorization: Bearer $TOKEN"
```
`200` returns the reservation with `status: "cancelled"`, and its seats go back to `available`.
Cancelling again returns the same `200` and changes nothing. Another user's reservation returns `404`.

### Read

- `GET /shows/{id}` returns per-seat `status` (available / held / confirmed) and
  `counts {available, held, confirmed, total}`, plus `reconciled: true|false`. Everything comes from a single SQL
  statement, which means a single snapshot.
- `GET /reservations/{id}` returns the reservation, owner only.
- `GET /me/reservations?show_id=...` lists the caller's reservations for a show.

## Burst

```bash
./burst.sh https://p01--seat-reservation--wlpk4pp6vqpm.code.run        # or: make burst URL=...
ADMIN_KEY=<deployed ADMIN_API_KEY> ./burst.sh https://...
```

Phases:
1. Hot-seat storm: 500 users fire at `A12` at the same instant. Exactly one `201`, 499 `409 seat_taken`.
2. Five hot seats with 500 users each, all at once. Exactly one winner per seat.
3. Stampede: 20,000 reserves at up to 1,000 in flight, 60% aimed at the front three rows, 20% two-seat requests.
   10% of requests are sent twice concurrently with the same key, simulating client retries.
   While this runs, `GET /shows/{id}` is polled to check the invariant.
4. One idempotency key fired 20 times in parallel gives exactly one `201` and 19 replays. Then the same key with
   different seats returns `409 idempotency_key_reused`.
5. One user fires 10 parallel reserves on a limit-4 show and ends up with at most 4 seats.
6. Spoofing: a body `user_id` is ignored, a non-owner cancel returns `404`, and an owner cancel makes the seat
   re-bookable. A repeat cancel must not resurrect a seat that someone else now owns.

It prints the outcome distribution per phase and in total (status and reason), latency percentiles, and then the
**reconciliation**:
- `available + held + confirmed == total`
- the API's confirmed count must equal the seats in the live reservations the API returned to the client (no phantom sales)
- the `/metrics` counter deltas must match what the client observed
- the `seats_available` gauge must match the API

It exits non-zero on any violation.

Tunables (environment variables): `CONCURRENCY` (1000), `STAMPEDE` (20000), `HOT_USERS` (500), `USERS` (2000),
`ROWS`x`COLS` (20x100), `RETRY_PCT` (10), `RETRIES_429` (5), `ADMIN_KEY`, `STRICT` (false).

### Verification status

The previous draft contained performance figures without retained evidence. Those figures have been removed.
Verified runs, exact commits, commands, and limitations are recorded in [VERIFICATION.md](VERIFICATION.md).
A local run is not proof that the public deployment meets the grading bar.

### Strict grading mode

```bash
ADMIN_KEY=... STRICT=true ./burst.sh https://p01--seat-reservation--wlpk4pp6vqpm.code.run
# equivalent: ADMIN_KEY=... make burst-strict URL=https://p01--seat-reservation--wlpk4pp6vqpm.code.run
```

The client prefers HTTP/2 (with HTTP/1.1 fallback) and reports the negotiated protocol. This lets HTTPS
requests share connections rather than requiring one TLS socket per buyer. The load generator uses multiple
clients with at most 64 active requests each to avoid the observed 100-stream per-connection limit.
Total client slots exceed the configured concurrency; strict mode still permits 20,000 active attempts.
Token setup uses at most eight requests in flight and is separate from the reservation stampede.
Strict mode forces 20,000 stampede tasks, permits 20,000 simultaneous HTTP attempts, and disables 429 retries,
even if conflicting environment variables are supplied. Strict requests allow up to 120 seconds to connect
and 600 seconds for the complete response, including the body. An expired response future is cancelled
and counted as a transport failure; it is never retried in strict mode. Tasks wait behind a common start gate. The script reports
peak outstanding client HTTP attempts; this is not a claim that every connection reached the server simultaneously.
A 429 or any unexpected outcome fails strict verification. Cold-start readiness attempts are counted separately
from the burst, which begins once the service is ready. Every subsequent HTTP attempt is counted, including
state polls, setup requests, and intermediate retry responses.
The verifier also prints bounded failure-source diagnostics using safe response headers and page classifications;
it never prints tokens, cookies, request bodies, or full upstream error HTML.

Metrics are mandatory: missing metrics, missing counters, counter deltas that disagree with reservation responses,
and an available-seat gauge that disagrees with the API all fail the run. Run against one isolated instance without
other reservation traffic, because the business counters are process-wide. A restart during the run also invalidates
counter reconciliation. Failed or malformed state polls fail verification; at least one valid live snapshot is required.

## Observability

### Metrics (`GET /metrics`)

| Metric | Type | Meaning |
|---|---|---|
| `reservations_confirmed_total` | counter | committed reservations (one per `201`) |
| `seats_confirmed_total` | counter | seats moved to confirmed |
| `reservations_declined_total{reason}` | counter | `seat_taken`, `per_user_limit`, `idempotent_replay`, `idempotency_key_reused`, `invalid` |
| `reservations_cancelled_total`, `seats_released_total` | counter | owner cancels |
| `seats_available{show_id}` | gauge | available seats, read from Postgres every second |
| `show_seats{show_id,status}` | gauge | available / held / confirmed / total per show |
| `seats_reconciliation_drift{show_id}` | gauge | `total - (available+held+confirmed)`; must always be 0 |
| `requests_shed_total`, `admission_in_flight`, `admission_queued` | counter, gauges | bulkhead state |
| `hikaricp_connections_*` | gauges | pool active / idle / pending |
| `http_server_requests_seconds_*` | histogram | latency by route and status |

Business counters are incremented only after the deciding transaction commits, so they reconcile with the API.
Seat gauges are queried from the database rather than kept in memory, so they survive restarts and agree
across instances. They cover the 20 most recent shows, which keeps label cardinality bounded.

`idempotent_replay` is counted under `reservations_declined_total` because a replay is a request that did not
create a reservation. It is still answered `200` with the original reservation.

### Logs

Every request emits one JSON line on stdout with `request_id`, `method`, `path`, `status`, `latency_ms`, plus
`user_id`, `show_id`, `reservation_id`, `outcome` and `reason` when they apply. An inbound `X-Request-Id` is honoured
(the burst tags its requests `burst-<run>-...`) and echoed back in the response. The same `request_id` is in error
response bodies.

```json
{"ts":"...","message":"request","logger_name":"access","level":"INFO","request_id":"burst-27ojwt-1a2b3c4d","user_id":"u17","show_id":"3731e110-...","outcome":"declined","reason":"seat_taken","method":"POST","path":"/shows/3731e110-.../reserve","status":409,"latency_ms":12}
```

On Northflank, open the service's **Logs** tab. The [live-log recording](evidence/northflank-live-logs.mp4)
captures the live stream at one frame per second during functional burst `2ikkxp` on revision `0b5b4d8`.
It contains synthetic users and no deployment credentials. The recording proves live logging;
[VERIFICATION.md](VERIFICATION.md) separately records the load outcomes.

### Health

- `/livez` checks only that the process is alive.
- `/readyz` checks readiness plus a real `SELECT 1` on a **dedicated short-timeout connection** (2s) instead of
  the pool. The probe has a two-second timeout and a one-second cache; the isolated outage test requires
  `503` within six seconds and verifies recovery. This avoids pool-borrow contention, but HTTP connector
  or CPU saturation can still delay health responses. The deployment uses `/readyz`.

## Current deployment: Northflank

The live service and private PostgreSQL 18 database run on Northflank's **Free Developer Sandbox**
in London, each with 0.2 shared vCPU and 512 MB RAM. Database storage is 6 GB persistent.
See [deployment instructions](deploy/northflank.md). The functional burst passed, but the completed
strict run returned **410 gateway 503s**. This does **not** meet the submission gate.
The attempted HTTP/2 configuration also failed and is not the recommended deployment.

The Sandbox is described as always on; no 30-day database expiry is shown for this deployment.
This does not guarantee indefinite availability. Check readiness before sharing the URL.
The admin credential is provided privately; `/auth/token` creates evaluator user tokens as shown above.

## Alternative deployment: Render (previous test environment)

1. Push this repo to GitHub (public).
2. In Render, choose **New > Blueprint**, select the repo, and apply. [render.yaml](render.yaml) creates:
   - `seat-reservation`: a Docker web service with health check `/readyz`. `JWT_SECRET` and `ADMIN_API_KEY`
     are generated, and `DATABASE_URL` is wired to the database.
   - `seat-reservation-db`: Render Postgres.
3. Copy `ADMIN_API_KEY` from the service's **Environment** tab, then run
   `ADMIN_KEY=... ./burst.sh https://seat-reservation-t3ml.onrender.com`.

Plan notes:
- The **free** web plan sleeps after 15 idle minutes. An explicit suspend/resume test reached readiness after 50.07 seconds on revision `2d73e8d`; ordinary idle wake-up time can differ. Public burst capacity must be measured; local results do not establish free-tier capacity.
- The provisioned free database expires on **November 4, 2026**, as shown in the Render dashboard.
  No paid upgrade has been configured. See [Render free-service limits](https://render.com/docs/free).

The app reads `DATABASE_URL` in Render/Heroku form (`postgres://user:pass@host[:port]/db`), so it also runs
unchanged on Railway, Fly or Neon.

### Bounded ingress in the Docker image

The container runs HAProxy in front of the same Spring service. Up to 24,000 client connections can wait
at ingress, while at most 64 write requests enter Spring at once. Health/metrics and GET/HEAD requests use
separate upstream capacity, so reservation queues do not consume their connection slots. All requests still
use the existing authentication, application handlers, and PostgreSQL transactions; the proxy never invents
reservation outcomes and does not retry requests.

Waiting writes have a bounded 480-second queue deadline, plus a 60-second upstream response timeout.
The strict client retains its 600-second deadline. This is a latency tradeoff for a fractional-CPU host,
not a reduction of the 20,000-attempt client concurrency. Frontend and application keep-alive timeouts are
120 seconds to avoid closing reusable connections before Render's proxy expects them to close.

Java and HAProxy share the container's memory/CPU budget. The JVM heap is capped at 35% of container memory;
HAProxy uses 4 KB HTTP/1.1 buffers, which also bound request-header size. Normal issued-token/admin headers
fit this bound; bodies (including large show creation and show-state responses) stream through the proxy.
Render continues to terminate public TLS/HTTP2. Spring binds only to loopback port 18080 in Docker.
`deploy/start.sh` terminates both processes if either exits, and forwards container shutdown to both.

For the constrained local configuration:

```bash
docker compose -p paytm-stress -f docker-compose.yml -f deploy/compose.stress.yml up --build -d
# Java + proxy share 512 MB and 0.1 CPU; PostgreSQL is a separate container.
docker run --rm --network paytm-stress_default --memory=2g -e STRICT=true \
  -v "$PWD/scripts:/scripts:ro" eclipse-temurin:21-jdk \
  java -Xmx1536m -Xss512k -XX:+UseParallelGC /scripts/Burst.java http://app:8080
```

## Configuration

| Env | Default | |
|---|---|---|
| `DATABASE_URL` | (none) | `postgres://...`; otherwise `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` |
| `DB_SSLMODE` | `prefer` | used when `DATABASE_URL` has no query string |
| `HTTP_MAX_CONNECTIONS` | 2048 | internal Tomcat bound; Docker ingress handles the large waiting queue |
| `HTTP_ACCEPT_COUNT` | 20000 | requested OS connection backlog (subject to host limits) |
| `DB_POOL_SIZE` | 20 | Hikari max pool |
| `DB_CONNECTION_TIMEOUT_MS` | 30000 | pool borrow wait |
| `ADMISSION_MAX_CONCURRENT` | 64 | write requests admitted to the DB at once |
| `ADMISSION_MAX_WAIT` | 25s | queue time before `429` |
| `JWT_SECRET` | dev value | HS256 key material |
| `ADMIN_API_KEY` | `dev-admin-key` | admin secret |
| `PORT` | 8080 | public container listener |
| `APP_PORT` | 18080 | loopback Spring listener inside Docker |
| `JAVA_TOOL_OPTIONS` | see Dockerfile | C1-only JIT and AppCDS for small CPU shares; drop `-XX:TieredStopAtLevel=1` on bigger plans |

## Tests

```bash
make test         # Maven + Testcontainers inside Docker (no local JDK)
make test-local   # ./mvnw test with a local JDK 21
make test-burst   # negative checks for the burst verifier, in normal and strict modes
./mvnw -Dtest.postgres.image=postgres:18-alpine test  # match the provisioned Render database

# Colima: Ryuk needs the Docker socket path inside the Linux VM
TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock make test-local
```
[ReservationConcurrencyTest](src/test/java/com/paytm/seats/ReservationConcurrencyTest.java) drives the real HTTP
API against a real Postgres. It covers a 300-user hot-seat storm, overlapping multi-seat requests in random order
(checking all-or-nothing and no deadlocks), the per-user limit under 10 parallel requests, a 30-way same-key race,
same key with different seats, spoofed `user_id`, owner-only cancel, cancel-then-rebook, no resurrection on a
repeat cancel, the invariant polled during a burst, and clean 4xx validation and auth errors.

## Layout

```
src/main/java/com/paytm/seats/
  reservation/   ReservationService (the atomic flow), ReservationRepository (guarded SQL), AdmissionControl
  show/          show creation and the single-snapshot state view
  auth/          HS256 tokens, AuthUser resolver (identity from token only)
  observability/ request-id + access log filter, business counters, DB-backed seat gauges
  health/        fail-closed DB readiness indicator
src/main/resources/db/migration/V1__init.sql   schema + constraints
scripts/Burst.java                              zero-dependency burst program
```
