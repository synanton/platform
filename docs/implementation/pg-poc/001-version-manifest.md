# PG-POC-001 — Pinned Postgres, pgvector, and BM25 Extension Versions

**Status:** Closed (Phase 0A)
**Date:** 2026-09-27
**Sources:** https://www.postgresql.org/support/versioning/ (PG 16.15 current minor),
`git ls-remote --tags https://github.com/pgvector/pgvector` (v0.8.6 latest),
`git ls-remote --tags https://github.com/paradedb/paradedb` (v0.26.0 latest)

## Manifest

| Component | Pinned version | Channel | Checked |
|---|---|---|---|
| PostgreSQL server | `16.15` | Current minor, supported to 2028-11-09 | 2026-09-27 |
| pgvector extension | `v0.8.6` | Latest tag | 2026-09-27 |
| BM25 extension (candidate) | `pg_search` (ParadeDB `v0.26.0` latest tag) **or** in-core `tsvector`+GIN fallback | TBD in PG-POC-002 | 2026-09-27 |
| JDBC driver (`org.postgresql:postgresql`) | `42.7.4` | Already in `gradle/libs.versions.toml` | 2026-09-27 |
| Testcontainers (`testcontainers-bom`) | `1.21.4` | Already in `gradle/libs.versions.toml` | 2026-09-27 |

## Image deviation (recorded 2026-09-27)

No deviation: **`pgvector/pgvector:0.8.6-pg16`** (digest
`sha256:ccc6e83d6e35e931dc7c5def2022729d5a6c370318d099181995567ff1fb4d6b`,
pulled 2026-09-27) ships **PostgreSQL 16.15** — exact match to the authoritative
pin. PG-POC-003's Testcontainers setup uses this tag verbatim (never floating
`pg16`); the row here and the test must agree (003 acceptance).

The plain `postgres:16.4` images also present locally are NOT used by the PoC.

## Version-coupled assumptions

Single list lives here (same rule as YDB `version-coupled-assumptions.md`).
Do not add version-coupled notes elsewhere.

- `vector(384)` dimension is fixed at schema DDL (Phase 0, proposal §8.1). An embedding-model migration changes DDL, not config.
- `app.tenant_id` session setting shape (proposal §8.1 RLS policy) is coupled to the PG 16 `current_setting(..., true)` semantics.
- HNSW vs IVFFlat index choice is coupled to the pgvector pin; re-evaluate if the pin changes.

## Notes

- The proposal candidate is PostgreSQL 16+, so the server pin follows the 16 line at its current minor (`16.15`). PG 16 is supported; PG 14 goes EOL 2026-11-12 — the 16 line is the correct multi-year PoC base.
- **Risk carried to PG-POC-002/016:** pgvector is `0.x`-versioned and pg_search/ParadeDB moves fast (v0.26.0). Both are PoC-valid; either is a production risk until the stability inventory clears it.
- Re-validation rule: if any pin changes mid-PoC, PG-POC-001 reopens and the affected benchmark tickets re-run.
