# Demo notes

The demo is the proven BM25 path: **UI upload** (or Lucentix) →
`/v1/ingestion-runs` → extraction → canonical chunks → synquest BM25 → UI search
and provenance.

## What is proven

- P0 PDF through the real Lucentix crawler, with durable extraction rows
  (`extraction_operations` / `extraction_results` / `extraction_idempotency`).
- Browser upload of files/folders with sequential uploads, XHR progress, and
  operation polling (`SUCCEEDED`/`FAILED`); the P0 PDF and representative PDFs
  complete `FULL`, EPUBs `PARTIAL`.
- Duplicate gateway key returns the stored operation; duplicate platform key
  with a different source/resource returns 409 (#344).
- Search returns the expected top hit; provenance includes `source_sha256`,
  `chunk_sha256`, `processing_run_id`, and `source_version_ids`, expanded under
  the selected row.
- The five-check passes all criteria, including per-fixture `PARTIAL` (#345).
- `tenant_id` in the body and `X-Tenant-ID` are rejected per ADR 0007 (#347).
- Uploads up to the 100 MB ingest limit are accepted (#358).
- Large chunk writes are split into bounded Cassandra batches (#365); large PDFs
  and EPUBs no longer fail with `Batch too large`.

## Known limitations

| Area | State | Tracker |
|---|---|---|
| Demo auth | Placeholder; `DEMO_AUTH_*` config; missing config → 503 | #340 |
| Vector leg | BM25 only; dense leg gated on gpu-runtime | #23 (deadline 2026-10-31) |
| EPUB outcome | `PARTIAL` by design (text adapter does not support tables/embedded images) | — |
| New documents | Not searchable until `POST /v1/search-projection/rebuild` runs | — |
| Large files | Files above the extraction sync-size ceiling can fail before persistence | — |
| Page ranges | Canonical data carries them; public `/v1` contract does not expose them | #338 |
| Full library crawl | Curated subset only (552 files/7.6 GB deferred) | C3 follow-up |

## Operating notes

- Demo credentials never live in code. Use `DEMO_AUTH_USER`,
  `DEMO_AUTH_PASSWORD`, `DEMO_AUTH_TENANT`, `SYNANTON_JWT_SECRET`. The password
  is not a `VITE_*` variable.
- The UI sends no tenant header or body field; the tenant comes from the signed
  token.
- The Vite dev server proxies `/v1/sources`, `/v1/ingestion-runs`, and
  `/v1/operations` to `VITE_SYNFLUX_URL`, and `/v1/search`/`/v1/knowledge` to
  `VITE_SYNQUEST_URL`.
- Keep the cluster images pinned and recorded in the walkthrough.
- The synquest deployment sets `SYNQUEST_SEED_DEMO_TENANT=demo`, so a pod
  restart reloads the persisted projection instead of serving 503 until a
  manual rebuild.

See [demo-script.md](./demo-script.md) for the walkthrough.
