# Decision Record — Format Spec Is the First Deliverable

**Status:** Decided (2026-09-27)
**References:** `85f9b88` (D1–D3)
**Branch:** `DESIGN-PostgreSQL` (cross-track)

## D4 — 005 is implementable as-is → 028a starts

## D5 — Format frozen by agreement before code

First deliverable of 028a is not the generator — it is the format spec,
committed before any 028a–028e implementation. Required sections:

- Corpus output format (028a → 028b–e).
- Q3 query output format (emitters → comparator).
- Tenant distribution (50 Zipf layout).
- Golden query format (120 queries: mode, filter, selectivity, eligible set).
- Determinism contract (seed handling, ordering guarantees).
- Eligibility fixtures (cross-tenant boundaries).

## D6 — YDB workstream starts 028a; co-signers named

005 has no named author (single infra commit; no owner on record), so "005
author + 028a implementer" has only one party. Resolved:

- **028a implementer** (YDB workstream names one) — corpus half of the spec.
- **PG track, comparator owner** — Q3 output half; the strict parser in
  `synanton-bench-convergence` (`RunOutput`) is already the de facto schema
  and becomes the spec's normative reference for emitter output. No new
  document needed for that half — code is the spec.
- YDB workstream signs off before 028a implementation starts.

Without two named co-signers, "agreement" degrades to self-agreement and the
cross-artifact contract loses its second perspective. 028a implementation
does not start until both names are recorded here.
