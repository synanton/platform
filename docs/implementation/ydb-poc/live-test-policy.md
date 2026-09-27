# Live-Cassandra test policy (12 tests, Testcontainers)

**Decision:** run on **every PR**. One shared container per JVM
(`CassandraTestBase`; isolation via per-store namespaces, no schema churn), so the
marginal cost is a single Cassandra 4.1 startup (~30–60 s), not one per class.

## Cadence and retries

- Every PR runs `:java:synvault-cassandra:test` (contract + gating + 008 fail-fast).
- **No automatic retries.** A red live test fails the PR. Rerun once manually; if
  still red, treat as a real failure until proven infra (container logs attached).
- **Quarantine rule:** 3 consecutive infra-attributed failures (daemon/image, not
  assertions) → ticket to fix the runner, suite stays gating in the meantime.

## Runner pin (learned from the 1.21.4 upgrade)

- Runner Docker **≥ 26** (client↔daemon API compatibility; Docker 29.x requires
  client API ≥ 1.40, which needs testcontainers ≥ 1.21 / docker-java ≥ 3.6).
- `testcontainers` catalog version is the single pin (`gradle/libs.versions.toml`);
  bumping it requires re-running this suite before merge.
- Image `cassandra:4.1` is explicit in `CassandraTestBase` — no floating tags.
