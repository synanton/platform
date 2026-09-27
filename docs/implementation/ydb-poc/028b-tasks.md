# 028b Implementer Tasks — Replacement of BaselineBench

**Owner:** andreminin (YDB search-track)
**Branch:** `DESIGN-YDB-028b-tasks` (task lists; implementation branch TBD)
**Spec:** `028a-format-spec-corpus.md` + 028a generator output
**Estimate:** ~38 hr / ~5 days
**Gate:** Blocked on 028a (corpus format + generator output). D9: replacement,
not extension — BaselineBench is retired, not adapted.
**Depends on:** 028a corpus; `EmitterContractTest` green (already green on main)

Each task maps below. Acceptance is per-task, cumulative. No task closes on a
documented claim; each needs evidence in the same commit.

## 028b.1 — Module scaffold and BaselineBench retirement decision

Description: Create the multi-tenant benchmark module. Decide BaselineBench's
fate: delete, archive, or leave deprecated.
Acceptance: new module builds, suite green; BaselineBench status documented
(deleted / archived / deprecated-with-notice); production `HybridSearcher` and
`RrfFusion` reused (frozen baseline definition, 006). Estimate: 2 hr.
Depends on: —. Evidence: skeleton commit; retirement commit or deprecation note.

## 028b.2 — Multi-tenant corpus loader

Description: Load v1 corpus (documents, chunks, embeddings, manifest). Verify
manifest version matches expected. Fail fast on missing manifest (§7 emitter rule).
Streaming constraint (phantom-SKIP lesson from 028a.8): stream rows
(embed-then-write per row); 2g heap is margin, 1g must succeed.
Acceptance: 20k docs / 160k chunks loaded; manifest version asserted;
missing-manifest negative test. Estimate: 4 hr. Depends on: 028b.1.
Evidence: load test with count assertion + negative test.

## 028b.3 — Tenant-scoped index construction

Description: Build per-tenant Lucene indexes (multi-tenant partitioning).
Acceptance: 50 tenant indexes, Zipf-distributed; no cross-tenant leakage at
build; index size/build time recorded for the Phase 4 cost model.
Estimate: 6 hr. Depends on: 028b.2.
Evidence: index inventory test; per-tenant counts match Zipf.

## 028b.4 — Query execution over the corpus

Description: Execute the 120 golden queries (lexical/vector/hybrid; none/
tenant/metadata/eligibility). Query vectors read from the corpus manifest —
never recomputed.
Acceptance: 4 modes × 4 filters covered; vectors read from manifest;
eligibility legs at 0.1%/1%/10%/100%. Estimate: 8 hr. Depends on: 028b.3.
Evidence: coverage matrix; log showing vectors read from manifest.

## 028b.5 — Q3 emitter

Description: Emit Q3 JSON per the parser's strict schema (§7 emitter rule:
manifest-read, populate, fail-missing). Pass `EmitterContractTest` before close.
Acceptance: `EmitterContractTest` green; run_id/corpus/queries[] with
top_k[]/eligible_set[]/timing_ms; schema mismatches fail with filename.
Estimate: 6 hr. Depends on: 028b.4.
Evidence: contract test green; sample Q3 committed.

## 028b.6 — Ground-truth validation

Description: Pre-comparator validation of `eligible_set` against generator
ground truth (spec §5 derivation rule). Reject the run on any mismatch.
Acceptance: runs as pre-comparator step; mismatch fails naming query + chunk
ids; corrupted-set negative test fails as expected. Estimate: 4 hr.
Depends on: 028b.5. Evidence: validation test + negative control.

## 028b.7 — Determinism check

Description: Two runs, same corpus; byte-identical Q3 (tie-order excluded —
comparator-normalized).
Acceptance: identical top-K sets; timings may vary, results must not;
nondeterminism fails naming the query id. Estimate: 3 hr. Depends on: 028b.6.
Evidence: determinism test green.

## 028b.8 — Documented baseline run

Description: One baseline execution against v1; Q3 saved for R1 with env metadata.
Acceptance: `baseline-v1.json` produced; env metadata captured; checksum in
manifest. Estimate: 2 hr. Depends on: 028b.7.
Evidence: committed output + checksum + env metadata.

## 028b.9 — Closeout documentation

Description: `028b-closeout.md` mapping tasks to outcomes; deviations,
timing observations, surprises; BaselineBench retirement note;
cross-reference 006 re-freeze as next step.
Acceptance: all tasks covered; deviations none-or-explained.
Estimate: 3 hr. Depends on: 028b.8. Evidence: committed doc.

## Sequencing

```text
028b.1 → 028b.2 → 028b.3 → 028b.4 → 028b.5 → 028b.6 → 028b.7 → 028b.8 → 028b.9
```

Serial chain; no parallel sub-tasks (single index build order).
