# YDB PoC — Phase 0A artifacts

Definition-only outputs (no code except YDB-POC-037). All paths relative to the platform root.

| Ticket | Artifact | Status |
|---|---|---|
| 001 | `001-version-manifest.md` — YDB server `26.3.1.16` (pre-release) + SDK `v2.4.11` | Closed |
| 002 | `002-schema-validation.md` — §11.1 construct check; live-DDL follow-ups tracked | Closed with follow-ups |
| 003 | `003-stability-inventory.md` — per-feature GA/Preview table + risk flags | Closed (re-validated at Phase 6) |
| 005 | `005-corpus-definition.md` — `ydb-poc-corpus-v1` frozen spec | Closed |
| 007 | `007-cassandra-publog-scope.md` — separate track, ~16 d estimate | Closed |
| 009 | `009-relay-decision.md` — no cross-tenant relay; exception path defined | Closed |
| 010 | `010-gate-status.md` — 1.27/1.32 unfrozen → PoC throwaway-scoped | Closed |
| 037 | `java/storage-contract/` — shared port-types module (see its README) | Closed |
| 011 | Ports extracted: `synvault-api`, `synquest-api`, `storage-testkit`, `synvault-inmemory`, `synquest-inmemory`, `synvault-cassandra`; 34/34 tests green (incl. 8 live-Cassandra). Provisional surfaces tracked in `011-provisional-followup.md`. `synquest-cassandra` deferred to 024 (no pre-existing Cassandra search impl; current search is Lucene). Existing manifest/index service call sites stay on `ingestion-cache` until 021/024 rewire (port surface itself is Cassandra-free). | Closed |
| — | Build fix: `testcontainers` 1.20.3 → 1.21.4 (`gradle/libs.versions.toml`; test-scope only). 1.20.3's docker-java 3.4.0 speaks API 1.32; local Docker 29.x requires ≥1.40. | Done |

Phase 0D (004-matrix freeze after 011, 006-baseline) and Phase 0B/0C are not part of this batch.
