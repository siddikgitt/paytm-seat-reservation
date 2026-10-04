# Write-up

## 1. The atomic decision

The seat row is the unit of truth: `seats(show_id, label)` is the primary key, and the row has a single
`reservation_id` and `user_id`. A seat structurally *cannot* belong to two reservations. The only open question is
who gets to write that row, and a single guarded statement decides it:

```sql
UPDATE seats SET status='confirmed', reservation_id=:rid, user_id=:uid
WHERE show_id=:show AND label = ANY(:seats) AND status='available'
```

It runs inside one READ COMMITTED transaction, after the rows are locked with
`SELECT ... ORDER BY label COLLATE "C" FOR UPDATE`.

Why it is race-free:
- For the 500 people on A12, the first transaction takes A12's row lock. The other 499 block on that lock.
  When the winner commits, Postgres re-evaluates each waiter's row against the latest version (EvalPlanQual),
  sees `status='confirmed'`, and the waiter declines with `409 seat_taken`. No code path reads and then decides;
  the predicate is evaluated under the lock.
- Table CHECKs make the impossible states unrepresentable. A seat is `available` if and only if
  `reservation_id IS NULL AND user_id IS NULL`, and `user_show_quota.seats_held >= 0`.
- There is a **fast decline path** before the transaction: one statement reads the seat states and any existing
  reservation for this idempotency key. It is advisory. It can only *refuse* (the seat is visibly taken), never
  grant, so racing it is harmless. Because it is a single statement, it reads a single snapshot. If a concurrent
  same-key request already committed the seat, the same snapshot also contains that reservation, so the retry is
  reported as a replay rather than as "seat taken". This keeps the 499 losers off row locks entirely. In the hot
  storm they cost one indexed read.

**Multi-seat requests are all-or-nothing.** If any requested seat isn't available, the transaction rolls back and
nothing is booked. That holds under concurrency because the locks are held until commit. **Deadlock freedom:**
every transaction acquires locks in one global order:
1. the reservation row, through the idempotency unique index
2. the user's quota row
3. seat rows sorted by label in byte order

`FOR UPDATE` with `ORDER BY` locks rows in sort order. Because every transaction acquires locks in the same order,
none can wait for a lock held by a transaction that is itself waiting on it, so a cycle can't form. Cancel uses the
same order: reservation, then quota, then seats. The test suite fires 200 requests for random orderings of
overlapping seat pairs and triples and checks for no 5xx, no deadlock errors, and all-or-nothing per reservation.
Deadlock and serialization errors are still caught and retried up to 3 times as defence in depth.

**Per-user limit.** This is one conditional upsert on `user_show_quota(show_id, user_id)`:

```sql
INSERT ... VALUES (:show, :user, :n)
ON CONFLICT DO UPDATE SET seats_held = seats_held + :n WHERE seats_held + :n <= :limit
```

Zero rows affected means `409 per_user_limit`. The quota row lock serializes one user's parallel requests, and each
one re-evaluates the predicate against the committed count. Ten parallel reserves on a limit-4 show give exactly
4 seats; this is tested, and also checked by the burst. Cancel decrements the quota in the same transaction that
frees the seats.

## 2. Idempotency

- **Where the key lives:** the reservation itself. `reservations` has `UNIQUE(user_id, show_id, idempotency_key)`
  plus `request_hash`, the SHA-256 of the sorted seat set. Keys are scoped per user, so one user can't collide with
  or probe another user's keys, and per show.
- **Exactly once:** the first statement of the reserving transaction is `INSERT ... ON CONFLICT DO NOTHING` on that
  unique key. When two requests with the same key race, the second insert blocks on the unique index until the
  first transaction ends. If the first committed, the insert does nothing; the request reads the committed row and
  returns it (`200`, `Idempotent-Replayed: true`). If the first rolled back (for example, the seat was taken), the
  second simply proceeds as a fresh attempt. The reservation and its seats commit atomically, so a key is never
  "used" without its seats, or the reverse. A 30-way same-key race gives one `201`, 29 `200`s with the same
  `reservation_id`, and one row in the database.
- **Same key, different body:** the replay path compares `request_hash`. A different seat set returns
  `409 idempotency_key_reused`. Seat order is canonicalised, so `["A13","A12"]` is the same request as
  `["A12","A13"]`.
- **Status codes:** a replay is `200`, not `201`. A storm that includes retries therefore still shows exactly one
  `201` per seat.
- **Declines are not cached.** A key whose first attempt was declined has no row, so a retry re-evaluates and may
  now succeed. That is the useful behaviour for "seat taken, retry later", and it can never double-book.
- Replaying a key whose reservation was later cancelled returns the original reservation with
  `status: "cancelled"`. It does not re-book.

## 3. Holds and expiry

The implementation uses **immediate confirmation plus an explicit owner-only cancel**. The assignment's reserve response is
`status: "confirmed"`, and there is no payment step to wait on, so a TTL hold would add a state with no work
behind it. The schema already has `status='held'`, and the API reports `held` counts, so adding holds later
doesn't need a migration of the state model.

Cancel safety:
- `SELECT ... FROM reservations WHERE id=:id AND user_id=:token_user FOR UPDATE`. Ownership is part of the
  predicate, so another user's reservation looks exactly like a missing one (`404`).
- The release is guarded on ownership, not on the seat label:
  `UPDATE seats SET status='available', reservation_id=NULL, user_id=NULL WHERE reservation_id=:id`.
  A cancel can therefore never free a seat that has since been confirmed to someone else. This is tested: cancel,
  someone else rebooks, then a repeat cancel leaves the seat with the new owner.
- Cancel is idempotent (`status='cancelled'` returns `200` and does nothing), and it returns the quota in the same
  transaction.

A possible TTL-hold extension would work as follows:
- `reserve` writes `status='held', held_until=now()+ttl`.
- `POST /reservations/{id}/confirm` does
  `UPDATE ... WHERE reservation_id=:id AND status='held' AND held_until > now()`.
- Expiry would be **lazy**, through the claim predicate:
  `WHERE status='available' OR (status='held' AND held_until < now())`. Correctness never depends on a background
  job, but the claimer must also decrement the expired holder's quota in the same transaction.
- A sweeper would also run `UPDATE ... WHERE status='held' AND held_until < now()` in small batches with
  `FOR UPDATE SKIP LOCKED`, so the counts in `GET /shows` converge.
- Confirm and expiry contend on the same row lock, so a seat is either confirmed before its deadline or released,
  never both.

## 4. Consistency vs availability under a partition

This is a single Postgres primary and it is deliberately **CP**. Every grant goes through a row lock on the
primary. Nothing that could grant a seat is cached, replicated asynchronously, or decided in memory: the only
in-process caches are immutable show metadata and verified JWTs.
- If the app loses the database, its independent readiness probe fails closed with `503` (the isolated test
  checks within six seconds), and writes fail with `503` and `Retry-After`. Platform removal from routing
  additionally depends on health-check frequency and thresholds. The app never "assumes available".
- An idempotency key makes those client retries safe.
- Under overload, the admission bulkhead sheds with `429` *before* any read or write. Selling is unavailable for
  the shed requests, which is the right trade for a system of record: a refused buyer can retry, but a double-sold
  seat requires a human apology.
- The proposed tradeoff prioritizes write consistency over availability. `GET /shows` could be served
  from a replica or a short cache with a staleness bound, but reserve must always hit the primary.
- Scaling out keeps this property: app instances are stateless, so N instances still serialise on the same rows.
  The counters are per-instance and Prometheus `sum()`s them. The seat gauges are read from the DB.

## 5. Observability: what pages at 2am

Page (wakes someone):
- `seats_reconciliation_drift != 0` for any show. This should be impossible given the constraints, so if it
  fires, data is wrong. Stop sales.
- Any sustained `5xx`: `rate(http_server_requests_seconds_count{status=~"5.."}[2m]) > 0`. Declines are 4xx by
  design, so a 5xx is always a bug or a dependency failure.
- `/readyz` failing, or the DB health indicator DOWN, for more than 1 minute.
- `hikaricp_connections_pending > 0` sustained for 2 minutes together with reserve p99 above 2s. This means the
  database is the bottleneck during an on-sale.

Ticket or dashboard (business hours):
- `rate(requests_shed_total)` > 0: capacity is undersized for the on-sale. Scale up before the next one.
- `reservations_declined_total{reason="idempotency_key_reused"}` rising: a client bug that is reusing keys.
- `per_user_limit` declines spiking: bots or scalpers.
- Gap between `seats_confirmed_total` and the `show_seats{status="confirmed"}` gauge after restarts (expected,
  since counters reset), versus any gap between the gauge and `GET /shows` (unexpected).

Access logs carry `request_id` and, when applicable, `user_id`, `show_id` and `outcome/reason`. "Why did my booking fail?" is
answered with one query on the request id the client got back.

## 6. AI usage (directed vs decided)

This was an AI-led implementation. AI coding assistance produced the initial service, SQL design, tests,
load generator, deployment configuration, and draft documentation. The existing project used Java 21,
Spring Boot, PostgreSQL, and a Render blueprint. I am not claiming to have independently designed the locking
or idempotency mechanism, or personally made every decision in the initial implementation.

For the completion pass I asked OpenAI Codex to compare the repository against the assignment, repair gaps,
verify the running service, preserve incremental commits, and prepare the submission. I selected GitHub + Render,
a free-only hosting budget, and an explicit AI-led disclosure.

Codex identified a readiness failure caused by reading datasource properties instead of the active connection
details, decimal-to-integer JSON coercion, and burst verification that could overlook missing or inconsistent metrics.
It implemented focused fixes, database outage/recovery and numeric validation tests, and a strict burst mode that
cannot hide 429s with retries. The retained results and deployment limitations are in VERIFICATION.md.

AI also drafted the correctness explanation and operational recommendations in this document. These are design
arguments to review against the code, not a claim that I implemented or verified them without assistance.
The interview will require me to explain and extend this work; AI-generated documentation is not a substitute
for that understanding. No unsupported performance result or personal design contribution is claimed here.

### Deployment investigation and measured result

After Render's public load failures, I asked Codex to try alternative free hosting
and completed the account onboarding, card verification and GitHub access steps.
Codex tested Northflank, diagnosed the HTTP/2 buffer requirement, and designed the
separate-proxy deployment. These were AI-led decisions and implementation work.

The final topology uses Northflank's two included free services: a dedicated
HAProxy gateway and the Java application, each with 0.2 shared vCPU / 512 MB, plus
private PostgreSQL 18. The gateway accepts HTTP/2 and queues before forwarding
at most 64 writes concurrently. Read and health connections have separate limits.
It forwards authentication and API responses unchanged, performs no retries, and
never decides or fabricates a reservation outcome. The embedded application proxy
keeps its original defaults. The database transaction still makes every sale.

This separation followed measured failures: a combined Java/HTTP/2 container
stalled with a capped buffer pool, and an uncapped local version was OOM-killed.
A separate proxy's local peak was about 323 MiB, while the application used about
222 MiB. The final public strict run at `fb52455` released 20,000 attempts together
with retries disabled and passed with zero 5xx, transport failures or 429s.
Every hot seat had one winner, quotas and idempotency held, and 109 live snapshots,
final state, client ledger and business counters reconciled. The measured peak
outstanding client attempts was 19,999. The stampede took 123.865 seconds; this
trades latency for bounded memory and is not a claim of 20,000 requests per second.

The final restart check exposed a stale service address in HAProxy. Codex added
runtime DNS resolution and configured the full private service hostname, then
tested recovery with a changed application IP and an unchanged gateway.

The same work hardened Java's complete-response deadline after a partial body
outlived the request timeout, and added safe gateway-response attribution.
Earlier failed and incomplete experiments remain in [VERIFICATION.md](VERIFICATION.md)
with exact environments, commands, outcomes, readiness and persistence checks.
No paid capacity or platform-protection bypass was used.

## 7. Possible extensions

1. TTL holds with confirm and payment as described in section 3, including lazy expiry in the claim predicate and
   an expiry sweeper.
2. A virtual waiting room for on-sales larger than one primary can absorb: hand out admission tokens at a fixed
   rate, so the database sees a steady stream instead of a cliff.
3. Partition `seats` by `show_id` (hash) once the table has millions of shows. Hot-seat contention is per row, so
   partitioning doesn't change the locking story.
4. OpenTelemetry tracing (request id becomes trace id) and a Grafana dashboard plus alert rules from section 5,
   checked into the repo.
5. Turn the burst into a CI gate against an ephemeral environment, and add a chaos test that kills the DB
   mid-burst and asserts no 5xx except `503` with `Retry-After`, plus reconciliation afterwards.
6. A real identity provider (OIDC/JWKS) replacing `/auth/token`, and per-user rate limits in front of the bulkhead.
