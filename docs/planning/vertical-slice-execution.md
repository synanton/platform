# Vertical slice — execution plan

Reference acceptance case: **`refresher-algebra-calculus.pdf`** — one
representative document uploaded through the UI, ingested, searchable, and
returning provenance with page + section. This replaces the synthetic
Acme/Globex corpus as the demo's reference case; the synthetic corpus is retired
from the demo path (see #508).

## Status dimensions

Each issue is tracked against three independent statuses:

- **Demo Complete** — the happy path works live in the room.
- **Architecture Complete** — contracts frozen, design coherent, no known
  structural gaps.
- **Operationally Complete** — survives pod restart, node drain, and failure
  injection.

The slice is Demo Complete; this plan drives it to Operationally Complete.

**Milestone — Vertical Slice: Demo Complete (achieved 2026-11-06).** Live hybrid
retrieval verified end to end: a newly uploaded PDF returns `BM25 #1 · Dense #1 ·
RRF #1`; provenance renders source/chunk SHA-256 plus page/section; the ontology
panel loads 273 entities with `df` badges. Evidence:
`docs/acceptance/live-dense-2026-11-06.md`.

## P0 — Baseline (Demo Complete)

Path: Source → Ingestion run → Operation → Source version → Search projection →
Search → Provenance.

- [x] Upload one representative document (`refresher-algebra-calculus.pdf`).
- [x] Ingestion run + operation state visible.
- [x] Search returns the uploaded document's chunks on both legs (BM25 + live Dense via TEI).
- [x] Provenance renders page range + section breadcrumb (no "#338" placeholder).
- [x] Ontology panel renders (273 total / 31 graph-useful), df counts as badges.
- [ ] **#537** — automated vertical-slice regression (upload → dense search → provenance).

## P1 — Failure & operability

- [x] **#506** — synquest `ReadWriteOnce` PVC + `RollingUpdate` deadlocks on image
  change; set `strategy: Recreate`. (Done — `synquest.yaml` sets `Recreate`.)
- [x] **EWP-2 Migration 3** (eventing) steps 2–6: Cassandra schema columns, typed
  payloads, write path, config gate, e2e (#527, #530–#533; tracker #528 closed).
- [ ] **#536** — outbox consumer idempotency & recovery (kill mid-projection, restart, no duplicates).
- [ ] Projection lag and retrieval-failure visibility in the UI.
- [ ] **#539** — tenant isolation negative tests for live search + provenance.
- [x] **UX polish (503)** — `SearchPage.tsx` catches the projection-rebuild `503`
  and renders a friendly "Index updating…" banner instead of a red error (#525).
- [x] **Live Dense leg** — verified 2026-11-06; see the dedicated subsection below.
- [ ] **#538** — route demo TEI via gateway/Envoy; drop the `demo-allow-synanton-tei`
  NetworkPolicy exception (prod TODO in the manifest).

### P1: Live Dense Leg & GPU Integration — DONE (2026-11-06)

- **Config:** `VLLM_EMBED_BASE_URL` (synquest) and `EMBED_BASE_URL` (synflux — note
  synflux does **not** read `VLLM_EMBED_BASE_URL`) point at
  `http://tei-embedding.gpu-plane.svc.cluster.local:8000/v1`;
  `SYNFLUX_PIPELINE_EMBEDDING_ENABLED=true`.
- **Code:** `DemoSearchService` embeds uncached queries through `QueryEmbedder` (TEI)
  and reads chunk vectors from `embedding_content_cache` (model-filtered, shape-fit).
  The static `dense-v2.json` is now a query cache only.
- **Infra:** additive demo NetworkPolicy `demo-allow-synanton-tei` (prod TODO: route via
  Envoy); synquest uses `strategy: Recreate` for its RWO index PVC.
- **Verified:** new PDF upload → manifest `EMBEDDED` → canonical chunk vector → projection
  → `BM25 #1 · Dense #1 · RRF #1`. A mixed query shows lexical hits (`Dense —`) fused with
  semantic hits (`BM25 — · Dense #N`) by RRF.
- **Trade-off:** the 58 legacy manifests were ingested with embedding disabled and have no
  canonical vectors, so they remain BM25-only. New uploads get full hybrid retrieval;
  corpus backfill is intentionally deferred (would trigger 58 TEI jobs + reindexes).

## P2 — Focused graph exploration (re-scoped #508)

- [ ] **#508** — bounded neighbor expansion from a single selected entity
  (open a graph from an entity, expand ≤ ~200 nodes). Full 20k-chunk graph
  visualization is **not** a prerequisite.

## P3 — Operational reality

- [ ] Survives pod restart / node drain / redeploy.
- [ ] `stop.sh` / `start.sh` cleanly quiesce + resume the demo stack. *(stop.sh
  now covers security/ui/platform; start.sh resumes.)*

## Current issue mapping

| Issue / track | Status | P-level |
|---|---|---|
| #508 (graph) | Open — bounded expansion | P2 |
| #506 (RWO deadlock) | Closed — `Recreate` deployed (#534) | — |
| #500 (platform crashloop) | Open — out of demo path | deferred |
| EWP-2 Migration 3 (#528) | Closed — steps 1–6 complete | — |
| #536 (outbox idempotency & recovery) | Open | P1 |
| #537 (automated vertical-slice E2E) | Open | P0 |
| #538 (TEI via gateway/Envoy) | Open | P1 |
| #539 (tenant isolation negative tests) | Open | P1 |
| #507 | Closed (invalid — store populated) | — |

## Operation

- Demo bring-up: `./scripts/deploy-demo.sh` (data layer + `local-real-demo` overlay + htpasswd).
- Resume after stop: `./scripts/start.sh`; quiesce: `./scripts/stop.sh`.
- Browser: `http://<worker>:30080/search` and `/ontology`.
