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
| 010a | `010-gate-status.md` — gate determination (1.27/1.32 unfrozen → PoC throwaway-scoped) | Closed |
| 037 | `java/storage-contract/` — shared port-types module (see its README) | Closed |
| 008 | `008-cassandra-revision-decision.md` — option (b): Cassandra non-conforming for revision/delete; Outcome 4 qualified; 039 rejects Cassandra where revision semantics required | Closed |
| 024 | `024-scope-split.md` — 024A (Cassandra engine, new) / 024B (YDB engine); baseline = ingestion-cache path for 004/006 | Recorded |
| 011 | `011-cql-exception.md` — transitional CQL-in-ingestion-cache exception | Open → 040 |
| 040 | `040-call-site-rewire.md` — owner: Platform Eng YDB-PoC workstream (named human countersigns at Phase-0 exit); target 021/024 close | Open |
| 033 | `YdbLoadTest` green: 5 workers, 15 rounds, **0 violations**, final state consistent | Closed (YDB path) |
| 034 | `034-cost-write-side.md` — write inputs frozen, search side + totals pending 028 | Partial |
| 035 | `CassandraToYdbMigrator` round-trip green (dry-run, checksums, rollback); `SchemaInventoryTest` permanent guard; short-circuit paths covered by non-empty contract assertions | Closed |
| 021 | `YdbSynvaultStore` closed: 4 tables, full atomic revision path (positive + injected-failure + concurrent-contention evidence), OCC both layers, pagination, `supportsStorageRevisions=true` w/ evidence, 039-accepts-YDB test; deviations in `001-version-manifest.md` | Closed |
| 004+006 | `004-parity-matrix.md` (frozen: current Lucene behavior per Must row, meet-or-justify flags, tie-break rule) + `006-baseline-thresholds.md` (protocol + rules frozen, preliminary operating points measured, absolutes gate on v1-corpus run) + `BaselineBench` (`-Dydb.bench=true`, excluded from PR) — landed as one unit | Closed |
| 038 | `038-observability-contract.md` — taxonomy + wiring (metrics default-on everywhere incl. Cassandra per Option A symmetry; Noop reserved for metrics-disabled profiles; freshness types for 029; Micrometer deferred) | Closed |
| 010b | `010-escalation.md` — **resolved throwaway-scoped (2026-09-26)**; 021/024 run evaluation-only behind provisional file | Closed |
| — | 004 gating extended to unmeasured metrics (vector Recall@10 ≥ 0.95 absolute); 006 hybrid re-measured as full concurrent pipeline (combine-only number void) | Done |
| — | `benchmark-execution-plan.md` — three-legged spec (baseline vs 024A vs 024B); conforming/non-conforming separation; three outputs (gates, adjusted perf, capability cost); five excluded failure modes. Phase 1/2 open throwaway-scoped (010 resolved) | Specified |
| 020 | Machine-readable `ConformanceMatrix`/`Conformant` in `storage-contract`; per-adapter matrices (Cassandra revision/delete UNSUPPORTED per 008); gating suites green (flags vs matrix vs loadable evidence) | Closed |
| 039 | `java/storage-provider` — config selection + `StartupValidator` (incl. 008 fail-fast with provider+capability+reason); `describe()` for 038 shape; contract tests green | Closed |
| — | `ActiveProviders` typed snapshot in `storage-contract` (038/039 shared; mixing explicitly supported per Outcome 3, tested); live-test policy (`live-test-policy.md`: PR cadence, shared container, no auto-retry, runner pin); 008 appendix (flag was unused) | Done |
| 011 | Ports extracted: `synvault-api`, `synquest-api`, `storage-testkit`, `synvault-inmemory`, `synquest-inmemory`, `synvault-cassandra`; 34/34 tests green (incl. 8 live-Cassandra). Provisional surfaces tracked in `011-provisional-followup.md`. `synquest-cassandra` deferred to 024 (no pre-existing Cassandra search impl; current search is Lucene). Existing manifest/index service call sites stay on `ingestion-cache` until 021/024 rewire (port surface itself is Cassandra-free). | Closed |
| — | Build fix: `testcontainers` 1.20.3 → 1.21.4 (`gradle/libs.versions.toml`; test-scope only). 1.20.3's docker-java 3.4.0 speaks API 1.32; local Docker 29.x requires ≥1.40. | Done |

Phase 0D (004-matrix freeze after 011, 006-baseline) and Phase 0B/0C are not part of this batch.
