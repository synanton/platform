# Live Dense leg — acceptance evidence (2026-11-06)

Vertical slice final checklist, run against the local homelab cluster
(`synanton` + `gpu-plane`) with TEI on node1.

## Environment

- TEI `tei-embedding` (namespace `gpu-plane`, :8000), model `synanton-bge-base-embedding`,
  served 768-dim (`bge-base-en-v1.5`).
- synquest `VLLM_EMBED_BASE_URL`, synflux `EMBED_BASE_URL` both
  `http://tei-embedding.gpu-plane.svc.cluster.local:8000/v1`;
  `SYNFLUX_PIPELINE_EMBEDDING_ENABLED=true`.
- Demo-only NetworkPolicy `demo-allow-synanton-tei` (see the prod TODO in the manifest).

## Magic flow — brand-new PDF

Generated a new PDF (`halcyon.pdf`, one page, unique text) and uploaded it through the
demo backend (`POST /v1/ingestion-runs`, multipart) as
`upload://halcyon/docs/halcyon-7-directive.pdf`.

| Step | Result |
|------|--------|
| Ingestion run | `COMPLETED`, `changed=1`, `failed=0` |
| Manifest | `EMBEDDED` |
| Canonical embedding | `embedding_content_cache`: chunk 0, `bge-base-en-v1.5`, dim 768 |
| Projection | `/v1/demo/search` 503 → 200 (~40s outbox reindex) |

Query (uncached, so the query vector was embedded live by TEI):
`How often must the Halcyon-7 auxiliary coolant loop be pressure-tested?`

```
rank=1 chunk=f144d888-616… bm25=1 dense=1 rrf=0.09524 legs=['bm25','dense']
title="Project Halcyon-7 Maintenance Directive"
```

Previously this path produced `BM25 #N · Dense — · RRF #N`. Verified both directly
against synquest and through the deployed UI NodePort (`:30080` → nginx → synquest).

## Provenance

`GET /v1/knowledge/f144d888-616c-536a-a97c-0f56a0e9da2e:0/provenance` (rendered by the
UI "Provenance" expander) returns:

- `source_uri` = `upload://halcyon/docs/halcyon-7-directive.pdf`
- `content_ref_id` = `f144d888-616c-536a-a97c-0f56a0e9da2e`
- `source_version_ids`, `content_cache_reference`, `processing_run_id`
- `source_sha256` = `3dac441e…aa314`, `chunk_sha256` = `63d69898…ba6e56`
- pages 1–1 and section `Project Halcyon-7 Maintenance Directive`

## Ontology

`GET /v1/demo/ontology/all?df_band=all&entity_class=all` → **273 entities**, each with a
`df` value; UI renders the grey `df N` badges and band facets
(`>50`, `11-50`, `1-10`).

## 503 grace

Observed server-side during projection (503 → 200). The UI maps that status to the
"Search index is updating…" banner (`SearchPage.tsx`, covered by #525 tests).

## Screenshots

Captured with headless Chrome against the deployed UI
(`/home/aminin/workspace/synanton/screenshots/vertical-slice-2026-11-06/`):

- `search-live-dense.png` — Halcyon hit at `BM25 #1 · Dense #1 · RRF #1`.
- `search-provenance.png` — expanded provenance (both SHA-256s, page/section).
- `ontology-df-badges.png` — 273 entities with `df` badges.

## Known trade-off

The 58 pre-existing demo manifests were ingested while embedding was disabled and have
no canonical vectors, so they remain BM25-only. New uploads get full hybrid retrieval.
Corpus backfill is intentionally deferred (would trigger 58 TEI jobs + reindexes and risk
destabilising the live demo).
