# Northflank deployment

The assignment can run as one Docker service and one PostgreSQL addon in a
Northflank Free Developer Sandbox project. The deployed project is in London
(`europe-west`). Both runtime resources use `nf-compute-20`: 0.2 shared vCPU and
512 MB RAM. The PostgreSQL 18 addon has 6 GB persistent storage, TLS enabled,
and private networking only. The dashboard labels both resources **Free**.

## Reproduce the deployment

1. Create a free project. Add PostgreSQL 18 with TLS and private networking.
2. Create a combined build/deployment service from this repository, branch `main`.
   Select the root `Dockerfile`, build context `/`, and default Docker entrypoint.
3. Select the free 512 MB compute allocation and one instance. Expose container
   port 8080 as HTTP; Northflank supplies an HTTPS `code.run` domain. This
   selects the proxy-to-container protocol, independently of the client's TLS.
4. Set runtime variables (never commit their values):
   - `DATABASE_URL`: the addon's ordinary `POSTGRES_URI`, not its admin URI.
   - `DB_SSLMODE=require`.
   - `ADMIN_API_KEY`: an independently generated 32-byte random secret.
   - `JWT_SECRET`: an independently generated 48-byte random secret.
   - `HAPROXY_BUFFER_SIZE=4096` and `HAPROXY_BUFFER_LIMIT=0` (image defaults).
     Do not select HTTP/2 upstream with this buffer size. The 16 KB HTTP/2
     experiment stalled under strict load; removing its buffer cap exhausted
     the local 512 MiB container. Those failed experiments are retained in the evidence.
5. Configure an HTTP readiness probe for `/readyz`, port 8080. The Docker
   entrypoint runs HAProxy on 8080 and Spring on loopback port 18080.
6. Build and wait for readiness. Flyway applies the existing schema migrations.
   No provider-specific application changes or additional migrations are needed.

Use the provider's secret-linking facility if rotating database credentials;
otherwise update the copied runtime `DATABASE_URL` when rotating them. The
application uses a connection pool of 20 by default. Do not expose the database
publicly to run the HTTP tests.

## Acceptance and evidence

Run the two modes separately, with no other reservation traffic or deployments
during either run:

```bash
export BASE_URL=https://p01--seat-reservation--wlpk4pp6vqpm.code.run
# Export ADMIN_KEY privately with the deployed ADMIN_API_KEY value.
JAVA_TOOL_OPTIONS=-Xmx2g ./burst.sh "$BASE_URL"
JAVA_TOOL_OPTIONS=-Xmx2g STRICT=true ./burst.sh "$BASE_URL"
```

The strict test retains 20,000 concurrent attempts and disables retries. A
provider error, overload response, failed state poll, or counter discrepancy
fails verification. Hosting elsewhere is not itself evidence of a passing run.
See [VERIFICATION.md](../VERIFICATION.md) for the measured results and tested
commit. Logs are available under service **Logs**; only synthetic user data is
used in the recordings.

Northflank describes its Sandbox compute as always on. Explicit restart or
suspend/resume tests should be distinguished from an idle-wake test. Free-plan
availability and limits can change; inspect the dashboard before provisioning
and select only resources shown as free. Card verification does not authorize a
paid plan or resource upgrade.

Sources: [pricing](https://northflank.com/pricing),
[free-plan and verification requirements](https://northflank.com/docs/v1/application/billing/pricing-on-northflank).
