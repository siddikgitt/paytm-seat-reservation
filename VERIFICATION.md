# Verification evidence

Submission status: **not yet ready**. Public deployment, public burst runs, cold-start measurement,
and a live-log recording remain pending. No local result establishes public capacity.

## Application regression suite

- Commit: `f69bded` (`fix: use active database for readiness and reject decimal money`).
- Command: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B test`
- Environment: local Java 21.0.8, Docker/Colima, disposable PostgreSQL 16 Testcontainer.
- Result: **12 tests passed, 0 failures, 0 errors**.
- Includes hot-seat and overlapping multi-seat races, quotas, same-key races, spoofing,
  cancellation/rebooking, reconciliation, decimal rejection, and readiness during a paused database
  followed by recovery. Liveness remained healthy during the database outage.

## Burst verifier regressions

- Commit: `0e94a15` (`test: enforce burst reconciliation and strict concurrency grading`).
- Command: `./test-burst.sh`
- Result: **24 checks passed in normal mode and 24 in strict mode**.
- Negative cases include missing metrics, every required counter missing or inconsistent,
  gauge mismatch, invalid state responses, failed polls, invalid reserve responses, and strict 429 rejection.
- Strict settings override attempts to lower concurrency or enable overload retries.
- Every reservation attempt, including a 429 preceding a successful retry, remains counted.

## Pending deployment evidence

- Public repository and clean-clone Docker build: pending.
- Public Render URL and endpoint checks: pending.
- Normal functional burst and strict 20,000-concurrency burst: pending.
- Public cold-start recovery and persistence across restart: pending.
- Database creation and expiry dates: pending provisioning.
- Public live-log recording: pending.

The previous README's claimed startup and throughput measurements had no retained evidence in this checkout
and were removed rather than presented as verified results.
