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

Pinned server `16.15` has no verified pullable image in this environment yet. Local PoC
runs on **`postgres:16.4`** (also mirrored as `local-registry:5000/postgres:16.4`;
both already pulled). Accepted for throwaway-scope PoC runs; minor-release upgrades
require no dump/restore (stop, swap binaries, restart), so re-pinning to the exact
`16.15` image before any production-track claim (Phase 6) is cheap and mandatory.

pgvector/pg_search images are not yet pulled here. PG-POC-003 records the exact
extension image (or build recipe) once pulled; until then the pin is the source
tag above, not a running image.

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
