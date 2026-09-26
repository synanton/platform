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

## Flake policy (from `live-test-policy.md`)

- Live suite runs on **every PR** (shared container keeps it ~40s).
- **No auto-retry.** Rerun once manually; still red = real failure until proven
  infra (attach container logs).
- 3 consecutive infra-attributed failures → ticket against the runner; suite
  stays gating meanwhile.
