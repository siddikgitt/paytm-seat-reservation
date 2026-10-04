# Northflank deployment

The deployment uses Northflank's Free Developer Sandbox in London (`europe-west`):
**two free services and one free PostgreSQL addon**. Each runtime has 0.2 shared
vCPU and 512 MB RAM. PostgreSQL 18 has 6 GB persistent storage, TLS enabled, and
private networking only. No paid upgrade is required or configured.

The public entrypoint is `https://p01--seat-gateway--wlpk4pp6vqpm.code.run`.
`seat-gateway` runs HAProxy with HTTP/2 and forwards to the existing
`seat-reservation:8080` private service address. The application service runs the
root Docker image. All API paths, authentication, transactions and metrics remain
in the same application instance. The gateway does not retry requests or generate
reservation outcomes.

## Reproduce the deployment

1. Create a free project. Add PostgreSQL 18 with TLS and private networking.
2. Create `seat-reservation` from this GitHub repository, branch `main`, root
   `Dockerfile`, build context `/`, and default entrypoint. Choose `nf-compute-20`
   (0.2 shared vCPU / 512 MB), one instance. Configure port 8080 as HTTP.
3. Set application runtime variables privately:
   - `DATABASE_URL`: the addon's ordinary `POSTGRES_URI`, not its admin URI.
   - `DB_SSLMODE=require`.
   - `ADMIN_API_KEY`: an independently generated 32-byte random secret.
   - `JWT_SECRET`: an independently generated 48-byte random secret.
   - Leave the embedded proxy defaults: `HAPROXY_BUFFER_SIZE=4096`,
     `HAPROXY_BUFFER_LIMIT=0`, `APP_HOST=127.0.0.1`, `APP_PORT=18080`.
4. Configure an HTTP readiness probe for `/readyz` on port 8080. Build and wait
   for readiness. Flyway applies the existing schema migrations.
5. Create the second free service, `seat-gateway`, from the same repository.
   Use `/deploy/gateway.Dockerfile`, context `/`, and the default entrypoint.
   Choose `nf-compute-20`, one instance, and expose port 8080 as **HTTP/2**.
   The image supplies `APP_HOST=seat-reservation`, `APP_PORT=8080`,
   `HAPROXY_BUFFER_SIZE=16384` and `HAPROXY_BUFFER_LIMIT=0`. If the application
   service has another name, change only this gateway's `APP_HOST` accordingly.
   On Northflank, override `APP_HOST` with the full private DNS name:
   `seat-reservation.ns-wlpk4pp6vqpm.svc.cluster.local` for this deployment.
   For another project, obtain its canonical name with `getent hosts seat-reservation`
   in the gateway shell. HAProxy refreshes DNS at runtime; Kubernetes search
   suffixes are not applied by its runtime resolver. This allows recovery when
   the application service address changes after pause/resume.
   No JWT, admin or database secrets are needed in the gateway.
6. Add the gateway's HTTP readiness probe: `/readyz`, port 8080. This forwards
   the application's actual dependency check. Use the gateway's public HTTPS
   URL for the evaluator and burst tests.

The original application's public URL remains available for diagnosis, but it
is not the grading entrypoint: that direct HTTP/1.1 ingress failed strict load.
Use provider secret linking when rotating database credentials, or update the
copied `DATABASE_URL`. Do not expose PostgreSQL publicly for HTTP tests.

## Why a separate proxy?

HTTP/2 requires at least 16 KB HAProxy buffers. Keeping that queue and Java in
one 512 MB container failed: the capped buffer pool stalled under load, and an
uncapped local experiment was OOM-killed. A separate free service gives the queue
its own memory allocation. A focused local 20,000-request HTTP/2 test measured
approximately 323 MiB peak for the proxy and 222 MiB for the application, with
one hot-seat winner and 19,999 declines. This diagnostic alone is not the full
public acceptance test; see [VERIFICATION.md](../VERIFICATION.md).

## Local reproduction

Use a fresh Compose project to reproduce PostgreSQL 18 and both services:

```bash
COMPOSE_PROJECT_NAME=paytm-gateway docker compose -f docker-compose.yml -f compose.gateway.yml up --build -d
curl -f http://localhost:8081/readyz
ADMIN_KEY=dev-admin-key ./burst.sh http://localhost:8081
```

The root Compose setup remains the simpler one-container application startup.
Local plain HTTP protocol negotiation can differ from public HTTPS; the strict
public result must be verified against the public URL.

## Public verification

Run the modes separately, with no deployment or other reservation traffic:

```bash
export BASE_URL=https://p01--seat-gateway--wlpk4pp6vqpm.code.run
# Export ADMIN_KEY privately with the application's deployed ADMIN_API_KEY.
JAVA_TOOL_OPTIONS=-Xmx2g ./burst.sh "$BASE_URL"
JAVA_TOOL_OPTIONS=-Xmx2g STRICT=true ./burst.sh "$BASE_URL"
```

The strict test retains 20,000 concurrent attempts and disables retries.
Unexpected responses, overload, failed state polls or counter discrepancies fail
verification. Business counters come from the single application instance.
Structured request logs are in **seat-reservation > Logs**, not the gateway's
startup log stream. Recordings use synthetic test users only.

Sandbox compute is described as always on. An explicit stop/resume measurement
is not an idle-wake test. No 30-day database expiry is shown for this deployment;
free-plan availability can change. Check health and the dashboard before sharing
the submission. Select only resources shown as free; card verification does not
authorize an upgrade.

Sources: [Northflank pricing](https://northflank.com/pricing),
[billing and verification](https://northflank.com/docs/v1/application/billing/pricing-on-northflank),
[port protocols](https://northflank.com/docs/v1/application/network/configure-ports),
[HAProxy buffer configuration](https://docs.haproxy.org/3.2/configuration.html#tune.bufsize).
