# 028b Closeout — Baseline Harness Replacement

**Status:** Complete (028b.1–028b.3, 028b.8 documented run green)
**Date:** 2026-09-28
**Branch:** `DESIGN-YDB-028b-3`

## Pre-ranking invariant (third instance — cross-layer rule)

Narrowing (truncation, filtering, limit) runs after eligibility, never
before. Instances: YDB Gate 0 (RLS × ANN), YDB P1-4 (metadata into YQL),
baseline fusion truncation (fuse full legs, filter after — fixed here after 5
hybrid legs returned empty despite matches at fused ranks 11–100). Applies to
retrieval legs, fusion, projection writes, pagination. **Pre-R3 review must
confirm 024B and PG fuse over full legs too** — baseline-vs-buggy-engine
overlap would misattribute to the adapter.

## Metadata preemption (B.2 defect class, caught forward)

B.2's `Map.of()` emptied 39 legs reactively. Here, stored `meta_*` passthrough
was verified write-side AND read-side before running. Same defect class,
opposite direction: mechanism checked, not outcome awaited.

## Structural properties (not incidental)

- CPU 115% vs B.2's 67%: multi-threaded Lucene build; full 160k in ~2 min
  (vs 58 min through the 024A adapter path — adapter overhead, not corpus).
- Test-scope direct invocation (BootJar boundary): RunLeg integration is a
  later convenience, not a prerequisite.
- Structural empties: exactly the 24 lexical-filtered legs (proven per
  family: 0/704 terms-in-scope; pred mismatch on the only 2 in-scope hits).
  Guard exemption is evidence-backed, and 5-hybrid-populate confirmed both
  diagnosis and guard in one run.
- Hybrid post-fix measures fusion over full legs (pre-fix: top-10). Timing
  topology changed with the fix; build.json carries the post-fix number only.

## Outputs

- `runs/baseline-v1.json` (120 queries, 96 non-empty, timing_scope ×120)
- `runs/baseline-v1.build.json` (load ms, index bytes, adapter params)
