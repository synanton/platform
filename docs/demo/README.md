# Demo notes

The demo is the proven BM25 path: Lucentrix upload → `/v1/ingestion-runs` →
extraction → canonical chunks → synquest BM25 → UI search and provenance.

## What is proven

- P0 PDF through the real Lucentrix crawler, with durable extraction rows
  (`extraction_operations` / `extraction_results` / `extraction_idempotency`).
- Duplicate gateway key returns the stored operation; duplicate platform key
  with a different source/resource returns 409 (#344).
- Search returns the expected top hit; provenance includes `source_sha256`,
  `chunk_sha256`, `processing_run_id`, and `source_version_ids`.
- The five-check passes all criteria, including per-fixture `PARTIAL` (#345).
- `tenant_id` in the body and `X-Tenant-ID` are rejected per ADR 0007 (#347).
- Uploads up to the 100 MB ingest limit are accepted (#358).

## Known limitations

| Area | State | Tracker |
|---|---|---|
| Demo auth | Placeholder; `DEMO_AUTH_*` config; missing config → 503 | #340 |
| Vector leg | BM25 only; dense leg gated on gpu-runtime | #23 (deadline 2026-10-31) |
| EPUB | Extraction completes; platform publication fails on Cassandra `Batch too large` for the tested file | #359 |
| Page ranges | Canonical data carries them; public `/v1` contract does not expose them | #338 |
| Full library crawl | Curated subset only (552 files/7.6 GB deferred) | C3 follow-up |

## Operating notes

- Demo credentials never live in code. Use `DEMO_AUTH_USER`,
  `DEMO_AUTH_PASSWORD`, `DEMO_AUTH_TENANT`, `SYNANTON_JWT_SECRET`. The password
  is not a `VITE_*` variable.
- The UI sends no tenant header or body field; the tenant comes from the signed
  token.
- Keep the cluster images pinned and recorded in the walkthrough.
- The synquest deployment sets `SYNQUEST_SEED_DEMO_TENANT=demo`, so a pod
  restart reloads the persisted projection instead of serving 503 until a
  manual rebuild.
- The vector feature must remain off (`SEARCH_DENSE_ENABLED=false` when it
  exists) for the demo window.

See [demo-script.md](./demo-script.md) for the walkthrough.
