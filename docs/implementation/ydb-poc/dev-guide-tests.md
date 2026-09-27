# Developer guide — running the YDB PoC tests

## Prerequisites

- JDK 21, Docker **≥ 26** (daemon API must satisfy testcontainers ≥ 1.21;
  Docker 29.x requires client API ≥ 1.40 — older testcontainers cannot talk to it).
- Image `cassandra:4.1` — pinned explicitly in `CassandraTestBase` (no floating
  tags). Pre-pull to avoid first-run waits: `docker pull cassandra:4.1`.
- `testcontainers` 1.21.4 (`gradle/libs.versions.toml`, test-scope only).
  Bumped from 1.20.3 because its docker-java speaks API 1.32, which Docker 29.x
  rejects with `client version 1.32 is too old`.

## Suites

```bash
# Fast unit suite (ports, contracts, gating, validators — no Docker)
./gradlew :java:storage-contract:test :java:synvault-api:test :java:synquest-api:test \
  :java:synvault-inmemory:test :java:synquest-inmemory:test :java:storage-provider:test

# Live Cassandra suite (12 tests, one shared container per JVM, ~40s)
./gradlew :java:synvault-cassandra:test

# Opt-in baseline benchmark (never runs in PR CI; prints the BENCH line)
./gradlew :java:synquest:test --tests "org.synanton.synquest.service.BaselineBench" \
  -Dydb.bench=true -Dydb.bench.docs=20000 -Dydb.bench.chunks=8
```

Boundary tests (`ApiBoundaryTest`, `ProviderBoundaryTest`,
`ProviderIsolationTest`) run inside the unit suite — they enforce the
no-provider-imports rule until the ArchUnit rule (012) lands.

## YDB PoC container (021/024B)

```bash
docker run -d --name ydb-poc -h localhost -p 2135:2135 -p 2136:2136 -p 8765:8765 \
  ydbplatform/local-ydb:stable-26-3-1-path-aliases
docker cp ydb-poc:/ydb_certs/ca.pem /tmp/ydb-ca.pem   # gRPCS-only image (YDB_CA_PATH)
# NOTE: /tmp is cleaned by the OS — re-copy after reboot/cleanup or if YDB tests
# fail with NoSuchFileException on the CA. YDB_CA_PATH env overrides the location.
# Lookup chain (all YDB suites): YDB_CA_PATH → -Dydb.ca.path → /tmp/ydb-ca.pem →
# ~/.config/ydb-ca.pem, else a clear error naming the docker cp command. CI
# provisions the container + CA + flags (see .github/workflows/gradle-build.yml).
# Required cluster flags (see 002-schema-validation.md for why each):
docker exec ydb-poc sed -i \
  -e 's/^table_service_config:/table_service_config:\n  enable_hybrid_search: true/' \
  -e 's/^feature_flags:/feature_flags:\n  enable_fulltext_index_row_id: true\n  enable_fulltext_index_prefix: true\n  enable_add_unique_index: true\n  enable_online_add_unique_index: true/' \
  /ydb_data/cluster/kikimr_configs/config.yaml
docker restart ydb-poc
```

## YDB test-table lifecycle (quota-safe)

Fixed per-class prefixes (`t_quest_*`, `t_vault_*`, `t_migrate`, `t_relay_*`),
idempotent ensure + `TRUNCATE` per run — never random prefixes (10k path
quota) and never inline DDL copies. Verified: full-suite reruns show zero path
growth. Historical debris (~250 pre-hygiene random-prefix paths) remains in
`/local` with ~97% quota headroom; drop it if pressure returns. Probes
(`*Probe`, `*Diag`, gated `-Dydb.probe`) keep random names and self-clean
where cheap; they run manually, not in CI.

Related discipline: a check that doesn't run must say so. Guards that skip
(e.g. quota guard off-container) skip visibly via assumptions, never silently —
a green suite that never executed its guards is another silent-pass variant of
the same class.

This section generalizes beyond YDB: test resources must be reclaimed
synchronously by the harness, on every backend. Audited: Cassandra is immune
by lifecycle (one container per JVM, stopped via shutdown hook, Ryuk-reaped —
removal destroys everything, nothing accumulates). Postgres PoC inherits this
section from day one: table lifecycle policy goes in the schema-deployment
ticket, not discovered via failure.

## Teardown discipline (second silent-failure class)

Best-effort catches on teardown must log at WARN, never swallow silently. Two
proven instances: SchemaInstaller split-on-`;` (fixed) and DROP-failure
swallowing during the quota incident (paths grew to 10k unnoticed). A teardown
that fails quietly accumulates resources until a hard limit surfaces them —
when the origin is hardest to trace. `YdbQuotaGuardTest` asserts path count
below threshold as the permanent guard.

## Harness pattern: stable store identity in lifecycle tests (false-negative guard)
Audit 2026-09-26: every suite test holds one store identity per test — except the
restart-recovery test, which briefly recreated its store mid-test with a fresh
namespace and then "passed" by reading empty state from the wrong keyspace.
Rule: **lifecycle tests (restart, replay, multi-phase) must hold store identity
stable across phases**; per-test randomness belongs in setup, never between
write and verify. A green assertion against the wrong identity is a
false-negative factory. Verified by container-start-time cross-check when a
lifecycle boundary is involved.

## Evidence-scope rule (institutional — fires on scope mismatch, not every test)Every green result states what contract scope it establishes: test name,
conformance entry, and tracker status use the same scope. A scoped test is
never promoted into a broader claim by shared naming (tenant isolation ≠ full
eligibility; correct results ≠ right path; exercised paths only — a test is
green solely for the code paths it traverses). Calibration: the rule fires
when the contract name is broader than the evidence. Origin: three instances —
restart-identity, eligibility-scope, annotations-never-exercised.

## DDL-in-one-place rule (convention — flagged for eventual enforcement)

Schema DDL lives in exactly one place per backend (`YdbSchema`,
`YdbSearchSchema`, `SchemaInstaller`); tests call it, never copy it. Enforced
by convention only: no suite test may contain `CREATE TABLE` / `ADD INDEX`
literals outside those modules (gated `-Dydb.probe` diagnostics are exempt —
throwaway by design, never blocking). A source-scan or ArchUnit enforcement is
wanted but not built — the next engineer adding "just one inline DDL" to a
suite test will restart the drift this rule was created to stop.

## Flake policy (from `live-test-policy.md`)

- Live suite runs on **every PR** (shared container keeps it ~40s).
- **No auto-retry.** Rerun once manually; still red = real failure until proven
  infra (attach container logs).
- 3 consecutive infra-attributed failures → ticket against the runner; suite
  stays gating meanwhile.
