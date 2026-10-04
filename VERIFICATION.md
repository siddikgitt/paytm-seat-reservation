# Verification evidence

Submission status: **not ready for HR**. The public service is deployed, but the strict capacity gate has not passed.
No local result establishes public capacity.

## Northflank migration and HTTP/2 repair

The current candidate is https://p01--seat-reservation--wlpk4pp6vqpm.code.run,
on Northflank's Free Developer Sandbox in London. Application and PostgreSQL 18
use separate 0.2 shared-vCPU / 512 MB allocations; PostgreSQL has 6 GB persistent
storage and private TLS networking. No paid upgrade was used.

- Revision `0b5b4d8`, public HTTP/1.1 upstream: [functional burst](evidence/northflank-functional.txt)
  **PASS**, 1,000 configured concurrency, 23,034 reservation attempts, zero 5xx,
  transport failures or 429s. All counters and the client ledger reconciled;
  127 live snapshots passed. Main stampede: 182.645 seconds, about 110 requests/second.
- Same revision: [strict burst](evidence/northflank-strict.txt) **FAIL**,
  20,000 concurrent attempts, retries disabled, peak 20,001 including a state poll.
  Across 23,034 reservation attempts, **410 HTTP 503s**, zero transport failures and
  zero 429s. All 410 lacked the ingress marker and reported `server: istio-envoy`;
  exact provider failure cause is not proven. State, client ledger and business
  counters reconciled. Main stampede: 188.376 seconds. These errors remain failures.
- Revision `6923616` makes HAProxy's buffer size and pool limit configurable.
  The failed experiment used HTTP/2 upstream, with 16 KB buffers and an 8,192-buffer
  limit (approximately 128 MiB plus overhead). The default HTTP/1.1 settings remain
  unchanged. A 4 KB buffer reproduced HTTP/2 framing failures locally;
  [protocol checks](evidence/northflank-http2-protocol.json) verify readiness, a new
  reservation and identical idempotent replay over both protocols with the fix.
- [Redeployment persistence](evidence/northflank-redeploy-persistence.txt): **PASS**.
  The original reservation and key survived deployment to `6923616`; replay was
  identical and seat counts remained reconciled.
- [Public HTTP/2 strict experiment](evidence/northflank-h2-strict-aborted.txt):
  **FAILED AND ABORTED** at `6923616`, run `2je1nh`. Gateway 503s occurred and
  readiness stalled. Direct container liveness timed out after 3, 5 and 10 seconds;
  the platform probe reported 0/1 passing. Container memory events showed no OOM.
  No final totals or reconciliation are claimed for this incomplete run.
- [Uncapped local HTTP/2 diagnostic](evidence/northflank-h2-memory-diagnostic.txt):
  **FAIL**, the 512 MiB / 0.2 CPU container was OOM-killed during 20,000 requests.
  This was a focused HTTP/2 capacity diagnostic, not the complete burst verifier.
  It was not deployed publicly. The supported configuration returns to HTTP/1.1
  upstream with 4 KB buffers and no hard buffer-count cap.
- [HTTP/2 experiment log recording](evidence/northflank-h2-live-logs.mp4):
  20 seconds at one frame per second during run `2je1nh`, showing actual structured
  request IDs, reservations and declines. It does not establish a passing run.
- [Rollback and application restart](evidence/northflank-configuration-recovery.json):
  readiness and liveness **UP**, with the original reservation and identical
  [idempotent replay preserved](evidence/northflank-restored-persistence.txt).
  This is restart recovery, not an idle-wake measurement. No reliable duration
  is claimed because the initial local Python timing probe lacked trusted CA roots;
  the final checks used curl with normal certificate verification.
- Both public load clients use Java 21.0.8 on macOS with `JAVA_TOOL_OPTIONS=-Xmx2g`.
  HTTP/2 compatibility checks alone do not establish the strict capacity gate.
- [Deployment reproduction](deploy/northflank.md) documents the exact free resources
  and protocol settings. Earlier Render results below are retained as history.

## Application regression suite

- Commit: `f69bded` (`fix: use active database for readiness and reject decimal money`).
- Command: `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -B test`
- Environment: local Java 21.0.8, Docker/Colima, disposable PostgreSQL 16 Testcontainer.
- Result: **12 tests passed, 0 failures, 0 errors**. Re-run at application revision `a743ce1` also passed
  ([final summary](evidence/final-app-tests-summary.txt)).
- Includes hot-seat and overlapping multi-seat races, quotas, same-key races, spoofing,
  cancellation/rebooking, reconciliation, decimal rejection, and readiness during a paused database
  followed by recovery. Liveness remained healthy during the database outage.

## Burst verifier regressions

- Commit: `0e94a15` (`test: enforce burst reconciliation and strict concurrency grading`).
- Command: `./test-burst.sh`
- Result: **24 checks passed in normal mode and 24 in strict mode**. Re-run with client `1e343f2` passed
  ([final summary](evidence/final-verifier-tests-summary.txt)).
- Negative cases include missing metrics, every required counter missing or inconsistent,
  gauge mismatch, invalid state responses, failed polls, invalid reserve responses, and strict 429 rejection.
- Strict settings override attempts to lower concurrency or enable overload retries.
- Every reservation attempt, including a 429 preceding a successful retry, remains counted.

## Earlier Render deployment

- Public repository: https://github.com/siddikgitt/paytm-seat-reservation (original incremental history preserved).
- Service: https://seat-reservation-t3ml.onrender.com
- Render web service: Free, Singapore; no paid upgrade.
- Render database: Free PostgreSQL 18, provisioned October 5, 2026 (Asia/Kolkata).
- Database expiry shown by Render: **November 4, 2026**.
- Latest application revision: `2d73e8d`; see the follow-up ingress repair below. Earlier failed runs are retained.
  Load-generator implementation is unchanged from `1e343f2`.
- Fresh public checkout Docker builds passed at `d8b26cb` and `238f9ac`.
- A clean clone at `1e343f2` also built and ran with unmodified Docker Compose; `/readyz` returned UP.
  See [clean-checkout proof](evidence/clean-checkout-docker.txt).
- `make test` passed all 12 application tests inside Docker.
- All 12 application tests also passed against PostgreSQL 18 using
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock ./mvnw -Dtest.postgres.image=postgres:18-alpine test`.
- [Live-log recording](evidence/live-logs.mp4): 20 seconds, captured at one frame per second from the Render
  live tail during public burst `2d8y30`. Contains synthetic users and no deployment secrets.
- [Restart persistence proof](evidence/restart-persistence.txt): a committed reservation and its idempotency key
  survived deployment from `d8b26cb` to `238f9ac`; replay returned the identical reservation with HTTP 200.

## Retained load results

- [Local functional burst](evidence/local-functional.txt), `d8b26cb`: PASS, 23,034 reservation attempts,
  1,000 configured concurrency, zero 5xx/transport failures, metrics and state reconciled. Unconstrained local app.
- [Initial public functional burst](evidence/live-functional-initial.txt), `d8b26cb`: FAIL overall.
  All 23,034 reservation attempts completed without 5xx; seat state and business counters reconciled.
  Token setup experienced **65 HTTP 502 responses**, which remain counted despite retries.
- Initial strict local in-network run, `d8b26cb`: application was OOM-killed. The app had no container memory limit;
  it competed for the VM's available memory. This is a failed capacity test, not a passing load result.
- [Bounded local strict burst](evidence/local-strict-bounded.txt), `238f9ac`: FAIL, **93 transport errors**.
  App limited to 512 MB and 0.5 CPU, client limited to 2 GB. App stayed running at approximately 376 MB;
  final seat state, counters, and quotas reconciled. Peak client attempts outstanding: 17,842.
  A common gate released 20,000 tasks; that does not mean all reached the server simultaneously.
- The earlier strict run through the Mac port forwarder broke its forwarding connection; that run is not service
  capacity evidence. The socket forwarding was restored without restarting unrelated containers.

These failures led to a bounded connection count, an OS backlog, modest token setup concurrency, and complete
failure reporting in the verifier. The 512-connection bound delayed health probes under normal load, so the final
application uses 2,048 connections. A [local 512 MB / 0.5 CPU functional run](evidence/local-functional-2048.txt)
passed with zero 5xx/transport errors and full reconciliation at that setting (about 425 MB resident memory).
The HTTP/1.1 fallback also [passed locally](evidence/local-http1-fallback.txt).

A first HTTP/2 public run using one client connection failed with 22,702 transport errors, while final state
and business counters reconciled ([output](evidence/live-functional-single-http2.txt)). A focused warm-connection
diagnostic reproduced `too many concurrent streams`: 400 of 500 concurrent requests failed before the server
could process them. The pooled client at `1e343f2` passed that diagnostic with zero transport failures
([diagnostic](evidence/http2-client-diagnostic.txt)). It allows 64 active requests per client connection and
provides more total slots than the requested concurrency. It does not reduce the strict 20,000-attempt limit.
Failed iterations are retained rather than relabeled as passing results.

## Public acceptance gate and remaining checks

- [Public strict burst](evidence/live-strict.txt), deployed `238f9ac`: **FAIL**.
  23,034 reservation attempts across all phases: 731 confirmed, 49 replays, 4,005 seat-taken declines,
  7 quota declines, 1 key-reuse decline, **6,181 HTTP 502 responses and 12,060 transport failures**.
  Including state probes and setup, the run recorded 6,220 HTTP 502 responses (18,280 total 5xx/transport failures).
  Peak outstanding client attempts was 16,370 after releasing 20,000 stampede tasks together.
  Final API counts summed correctly (1,162 available + 838 confirmed = 2,000), but the client ledger accounted
  for 832 confirmed seats and counter deltas did not reconcile. This is a failed acceptance test.
- Render Events reported an HTTP health-check timeout after five seconds at approximately 01:29 Asia/Kolkata,
  followed by service recovery. Counters reset during the run. No claim of zero errors or full reconciliation
  is made for this deployment at strict load.
- [Final public functional burst](evidence/live-functional-final.txt), app `a743ce1`, client `1e343f2`: **FAIL**.
  Java 21.0.8 client on macOS, `JAVA_TOOL_OPTIONS=-Xmx2g ./burst.sh <live-url>`, default 1,000 in flight.
  23,034 reservation attempts: 1,669 confirmations, 98 replays, 21,244 seat-taken declines, 21 quota declines,
  1 key-reuse decline, **1 HTTP 502**, zero transport failures, zero 429s.
  The run checked 129 live state snapshots. Final state: 154 available + 1,846 confirmed = 2,000;
  the client ledger and every required business counter matched. Peak outstanding attempts: 1,001 including
  a concurrent state probe. All 26,178 responses (including readiness) used HTTP/2.
  This is not a passing submission because the zero-5xx requirement was violated.
- [Final public strict burst](evidence/live-strict-final.txt), app `a743ce1`, client `1e343f2`: **FAIL**.
  Java 21.0.8 on macOS, `JAVA_TOOL_OPTIONS=-Xmx2g STRICT=true ./burst.sh <live-url>`.
  20,000 stampede tasks released together; retries disabled. Peak outstanding HTTP attempts: **20,001**
  including a state probe. All 26,372 responses (including readiness) negotiated HTTP/2; zero client transport failures.
  Across 23,034 reservation attempts: 60 confirmations, 21 replays, 2,994 seat-taken declines, 6 quota declines,
  1 key-reuse decline, **9,553 HTTP 429 and 10,399 HTTP 502**. Including probes, 9,611 HTTP 429 occurred.
  Final counts summed to 2,000 (1,919 available + 81 confirmed), but the client ledger covered only 69 seats.
  Counters reset, state polls failed, and all required counter deltas failed reconciliation. No passing
  zero-double-sale or full-reconciliation claim is made for this run.
  A separate diagnostic saw an HTML Cloudflare challenge in an HTTP 429 response
  ([edge observation](evidence/public-edge-challenge.txt)); this was not bypassed.
  Render Events reported a five-second HTTP health-check timeout and recovery at 01:57 Asia/Kolkata
  on October 5, 2026, during this run.
  This deployed configuration does not meet the assignment's public capacity requirement. No paid upgrade was used.
- [Cold-start recovery](evidence/cold-start-recovery.json): **PASS** on app `a743ce1`.
  Render UI explicitly suspended the web service while retaining PostgreSQL; `/readyz` returned HTTP 503.
  After clicking Resume, the first healthy readiness response arrived at **36.92 seconds**, and liveness was UP.
  This measures explicit suspend/resume, not a timed 15-minute idle wake.
- [Post-cold-start persistence](evidence/cold-start-persistence.txt): **PASS**. The original reservation,
  amount, user, seats, and idempotency key survived; replay returned the identical HTTP 200 response with
  `Idempotent-Replayed: true`. Counts remained one available and one confirmed out of two seats.

## Earlier Render submission decision

**Do not submit as a completed assignment.** Code regressions, Docker startup, log evidence, restart persistence,
and explicit cold-start recovery are verified. The repaired free public deployment passes the functional gate but still fails the required strict load gate. The strict result cannot be replaced by the smaller local or functional runs.
No paid resources or upgrade were authorized or used. The HR draft and generated admin credential are in the
ignored local `private-submission/` directory; no email was sent.


The previous README's claimed startup and throughput measurements had no retained evidence in this checkout
and were removed rather than presented as verified results.

## Follow-up ingress repair — application revision `2d73e8d`

The user requested another repair pass after the initial failed submission gate.
The Docker image now queues requests in HAProxy before allocating Spring request state, isolates health/read
connection capacity from writes, and aligns keep-alive timeouts with Render's proxy. SQL, endpoints, tokens,
quota enforcement, and idempotency semantics are unchanged.

- Application regression suite after the configuration change: **12 passed**, no failures/errors.
- Database outage through the proxy: readiness **503 in 2.10 seconds**, liveness **200**, then readiness recovered.
- Prototype with 180-second queue, C1 JVM, combined **512 MB / 0.1 CPU**: FAIL, 8,267 queue-expiry HTTP 503s.
  State and counters reconciled, no OOM; kernel peak memory 459,857,920 bytes (438.55 MiB).
  External monitoring saw two failed health probes out of 76.
- Same prototype with full tiered compilation: FAIL, 13,520 queue-expiry HTTP 503s.
  State/counters reconciled; sampled peak 465.6 MiB; seven failed health probes out of 99.
  Cold startup increased from about 34 to about 98 seconds, so C1 was retained.
- Revised queue deadline: 480 seconds, within the existing strict client deadline; no retries added.
  The earlier prototypes are not passing capacity evidence.
- [Final constrained strict run](evidence/local-queued-strict.txt): PASS with combined **512 MiB / 0.1 CPU**.
  All 20,000 stampede tasks released together, retries disabled; peak outstanding attempts 19,918.
  Zero 5xx, transport errors or 429s; all business counters and seat state reconciled.
  Slowest reservation 331.45 seconds. Kernel memory peak 428,097,536 bytes (408.27 MiB), no OOM events.
  Separate external readiness monitoring saw **6 failures in 107 probes** (five 5-second timeouts, one 503).
  This remains a health-latency limitation under the tight local CPU cap, despite the burst passing.
  See [resource samples](evidence/local-queued-resource-samples.json) and [cgroup counters](evidence/local-queued-memory.txt).
- [Public functional run](evidence/live-queued-functional.txt): **PASS**, default 1,000 in flight, 20,000-request stampede.
  23,034 reservation attempts; 1,674 confirmations, 101 replays, 21,240 seat-taken declines, 18 quota declines,
  one key-reuse decline. Zero 5xx/transport failures/429s across setup and load.
  215 live snapshots reconciled; final 153 available + 1,847 confirmed = 2,000 and all counter deltas matched.
  The main stampede completed in 219.07 seconds (91 requests/second). Reported client latency includes
  local concurrency-slot waiting in functional mode; it is not pure server processing latency.
- [Clean-checkout Docker checks](evidence/queued-docker-checks.json): build/start passed at `2d73e8d`;
  a 188,956-byte show request and 789,162-byte response streamed correctly through the proxy.
  All 20,000 seats persisted when the application was replaced with the clean-checkout image.
- [Public strict run](evidence/live-queued-strict.txt): **FAIL** at `2d73e8d`, Java 21.0.8 client on macOS,
  `JAVA_TOOL_OPTIONS=-Xmx2g STRICT=true ./burst.sh <live-url>`. All 20,000 stampede tasks released together;
  retries disabled; peak outstanding HTTP attempts **20,001** including a state probe.
  Across 23,034 reservation attempts: 1,441 confirmations, 83 replays, 9,848 seat-taken declines,
  13 quota declines, one key-reuse decline, **10,434 HTTP 429, 1,213 HTTP 502 and one HTTP 520**.
  Zero transport failures. Setup/probes added 22 more HTTP 429 responses; failed state polls remain failures.
  Final API state and client ledger agreed at 1,610 confirmed + 390 available = 2,000, but the seat-taken
  counter was 9,849 versus 9,848 observed declines. One response was therefore unaccounted for by that counter check.
- [Hosting observations](evidence/queued-hosting-observations.txt): Render showed no new restart or failed
  instance event during this repaired run. Process uptime spanned both bursts. Application `requests_shed_total`
  remained **zero**, so these public 429s did not come from the application's overload response.
  The public proxy/hosting path remains the blocker; precise attribution of every gateway error is unproven.
  Ten of 18 independent public readiness probes failed during strict load ([samples](evidence/live-queued-health.json)).
- [Updated log recording](evidence/queued-live-logs.mp4): 20 seconds at one frame per second, captured from
  Render Live Tail during strict run `2g8lh4`, including real confirmations, replays and declines.
- The standard public workload now passes; strict local capacity passes with the health caveat above.
  **The strict public acceptance gate still fails.** No retries, smaller concurrency, or synthetic outcomes
  were substituted for it. Further completion requires resolving the public hosting-path behavior and repeating
  the same strict public test. No paid upgrade or bypass of platform protection was attempted.

- Revised-image [cold-start recovery](evidence/queued-cold-start-recovery.json): PASS. Explicit Render
  suspend/resume with PostgreSQL retained; suspended readiness returned 503, then ready/live returned UP
  after **50.07 seconds**. This is an explicit resume measurement, not a timed 15-minute idle wake.
- Revised-image [persistence replay](evidence/queued-restart-persistence.txt): PASS. The original committed
  reservation and idempotency key survived; replay was identical with HTTP 200 and the replay header.


## Response attribution and load-client deadline repair

- Application/diagnostic revision `a96bf0f`: `X-Seat-Ingress: haproxy` marks responses passing through
  the container's HTTP response processing, including proxy-generated HTTP errors. Healthy public replies
  carry the marker. Very early malformed HTTP can precede this rule, so absence alone is not absolute attribution.
- [Public run `2got2u`](evidence/live-attribution-incomplete.txt): **FAILED AND ABORTED, incomplete**.
  Strict concurrency 20,000, retries zero. Captured 429s with `cf-mitigated: challenge`, HTML challenge content,
  and no ingress marker. Other plain-text 429s had `Retry-After: 1`; 502/520 responses also lacked the marker.
  This confirms Cloudflare challenge interference; exact attribution of other upstream failures awaits provider diagnosis.
  Response metadata is allowlisted; no credentials, cookies, request bodies, or complete error HTML are logged.
- [Stalled-client evidence](evidence/attribution-incomplete-run.json): seven `Burst.send` calls remained parked
  after more than twelve minutes. The run was stopped explicitly; no final outcome counts or passing reconciliation
  are invented for an incomplete run. The earlier complete failed runs remain the public capacity evidence.
- Local Java 21 reproduction: a 150 ms `HttpRequest.timeout` did not end a partial response body;
  the request waited 2,040 ms until the peer closed the socket. Revision `9de12de` uses a timed asynchronous
  response future and cancels the exchange on expiry, so the existing 600-second strict deadline covers the
  complete response body. A stalled response is still a counted transport failure, never a success or retry.
- [Verifier regression checks](evidence/body-deadline-verifier-tests.txt): **27 normal + 27 strict passed**,
  including a real partial-response socket fixture and diagnostic redaction checks.
- [Mid-run metrics](evidence/attribution-midrun-metrics.txt): the application was idle with zero overload shedding
  while the public client still awaited responses. This supports, but does not fully localize, the upstream failure.
- Render support was contacted through the signed-in dashboard with user authorization. Request title:
  **Free plan load testing issues**. Correlation IDs, test details and the free-only constraint were included;
  no credentials were sent. A provider answer is pending. No further public strict bursts are being repeated
  against the confirmed challenge until there is a supported path forward.

- [Revised-client local strict run](evidence/local-body-deadline-strict.txt), client/image `9de12de`: **PASS**.
  Java 21 Docker client with 1,536 MiB heap; application and HAProxy share **512 MiB / 0.1 CPU**; PostgreSQL 18.
  Peak outstanding attempts **20,000**; all 23,034 reservation attempts accounted for, retries disabled.
  Zero 5xx/transport errors/429s after readiness. Fourteen expected readiness 503s occurred during container
  startup before the burst and are reported separately. Final 151 available + 1,849 confirmed = 2,000;
  every required counter matched. Maximum reservation latency 339.52 seconds.
  [Kernel peak memory](evidence/local-body-deadline-memory.txt): 453,767,168 bytes (432.75 MiB), no OOM events.

- [Revised-client public functional run](evidence/live-body-deadline-functional.txt): **PASS** against server
  `a96bf0f`, client `9de12de`, Java 21.0.8/macOS with 2 GiB heap, 1,000 in flight and 20,000 stampede requests.
  Across 23,034 reservation attempts: 1,669 confirmations, 102 replays, 21,243 seat-taken declines,
  19 quota declines and one key-reuse decline. Zero 5xx/transport failures/429s, including setup and state probes.
  Final state 155 available + 1,845 confirmed = 2,000; all business counters matched. Peak outstanding attempts
  1,001 including a probe; all 26,258 HTTP responses used HTTP/2. Functional client latency includes waiting
  for the client's concurrency slot. This verifies the revised client, not strict public capacity.
