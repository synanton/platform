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

## P0 — Baseline (Demo Complete)

Path: Source → Ingestion run → Operation → Source version → Search projection →
Search → Provenance.

- [x] Upload one representative document (`refresher-algebra-calculus.pdf`).
- [x] Ingestion run + operation state visible.
- [x] Search returns the uploaded document's chunks (BM25; dense when cached).
- [x] Provenance renders page range + section breadcrumb (no "#338" placeholder).
- [x] Ontology panel renders (273 total / 31 graph-useful), df counts as badges.
- [ ] Automated regression test replaying the manual upload → search → provenance path.

## P1 — Failure & operability

- [ ] **#506** — synquest `ReadWriteOnce` PVC + `RollingUpdate` deadlocks on image
  change; set `strategy: Recreate`.
- [ ] **EWP-2 Migration 3** (eventing) steps 2–6: Cassandra schema columns, typed
  payloads, write path, config gate, e2e. Paused at step 1 (reader test-locking).
- [ ] Outbox delivery + idempotency under restart.
- [ ] Projection lag and retrieval-failure visibility in the UI.
- [ ] **Live Dense leg** — dynamic TEI embedding for uncached queries *and* new
  chunks, and read the demo Dense leg from Cassandra instead of the static
  `dense-v2.json`. Today the Dense leg is cache-only (uncached query → no vector;
  `SYNFLUX_PIPELINE_EMBEDDING_ENABLED=false` → new chunks unembedded), so live
  uploads show `BM25 #N · Dense — · RRF #N`.
- [ ] **UX polish (503)** — catch the projection-rebuild `503` in `SearchPage.tsx`
  and render a friendly "Index updating…" banner instead of a red error (the
  consumer's full reindex closes the searcher for ~40s).

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
| #508 (graph) | Open — re-scoped to bounded expansion | P2 |
| #506 (RWO deadlock) | Open — Recreate strategy | P1 |
| #500 (platform crashloop) | Open — out of demo path | deferred |
| EWP-2 Migration 3 | Paused at step 1 | P1 |
| #507 | Closed (invalid — store is populated) | — |

## Operation

- Demo bring-up: `./scripts/deploy-demo.sh` (data layer + `local-real-demo` overlay + htpasswd).
- Resume after stop: `./scripts/start.sh`; quiesce: `./scripts/stop.sh`.
- Browser: `http://<worker>:30080/search` and `/ontology`.
