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

## External-storage rule (stateful containers)

Any Docker container that persists non-trivial data (databases, index stores,
caches with disk persistence, brokers, log aggregators — anything that
fsyncs) must mount its data directory externally (bind mount on local
SSD/NVMe, or named volume). The container's writable layer is for runtime,
not application state: overlayfs fsync penalty (100–160× on sequential
writes) turns per-commit fsyncs into fixed metronomic costs that misdiagnose
as adapter/transaction problems.

Origin: YDB PoC 2026-09-29 — unmounted `/ydb_data` produced fixed ~35s batch
commits plus 104% idle burn; volume remount dropped idle to 2.8% and restored
fast commits. Rule of thumb: data beyond a few hundred MB, or expected to
survive restarts, gets mounted. One-sentence form: if a container fsyncs,
mount its data directory externally.

## Parameterization by default (scalar + collection)

All database interactions pass values as parameters, not as text-embedded
literals — scalar (`WHERE id = ?`) and collection (`List<Struct<...>>`)
forms. Text-embedded values are permitted only for DDL, dynamic identifiers,
and administrative operations. Security, correctness, and performance all
require it. Origin: YDB-041 (inlined embedding literals produced 221k AST
nodes per statement and a 250× commit-time penalty). AI-generated database
code follows the same rule (`.cursor/rules/db-parameterization.mdc`); both
surfaces cite the same origin.

Pre-flight (any stateful container):

- [ ] Data directory mounted externally (`docker inspect` shows the mount)
- [ ] Host path on local SSD/NVMe (not overlayfs, not NFS); space sufficient
- [ ] Container user has write access to the host path
- [ ] Idle CPU < 5% before measuring (rules out residual background work)

## Flake policy (from `live-test-policy.md`)

- Live suite runs on **every PR** (shared container keeps it ~40s).
- **No auto-retry.** Rerun once manually; still red = real failure until proven
  infra (attach container logs).
- 3 consecutive infra-attributed failures → ticket against the runner; suite
  stays gating meanwhile.

## Pause protocol (live tests vs stopped infrastructure)

A paused project (container stopped per pause checklist) must mark live-YDB
tests as expected-skip until resume — otherwise the suite goes red for a
stopped container, which reads as regression but is pause state. Pause
instructions and the test suite must agree: stopped infra ⇒ live tests skip
visibly, never fail. (Learned 2026-09-29: wiring test failed post-pause for
a stopped container; container restart + CA re-copy restored green.)

## AST-node discipline (YQL collection params)

Parameterize collections; never inline them. Inlined literals (e.g. 384
`CAST(... AS Float)` per embedding × rows × tables) cost ~2,210 AST nodes
per row against YDB's 1M cap and dominate commit latency (measured: 32s vs
~130ms per 100-row commit after switching to `List<Float>` params, ~250×).
Node count is a first-class YQL performance metric — estimate it before
sizing batches, and re-estimate at each dimension change.
