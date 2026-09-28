# 028b Implementer Tasks — Replacement of BaselineBench

**Owner:** andreminin (YDB search-track)
**Branch:** `DESIGN-YDB-028b-tasks` (task lists; implementation branch TBD)
**Spec:** `028a-format-spec-corpus.md` + 028a generator output
**Estimate:** ~10–12 hr (re-scoped: loader/query/emit/validate/determinism
consumed from shared module — see consumed list below)

Each task maps below. Acceptance is per-task, cumulative. No task closes on a
documented claim; each needs evidence in the same commit.

## Consumed from shared module (struck from original scope — do not rebuild)

- 028b.2 loader → A.2 streaming loader (this file re-scopes 028b.2 as wiring)
- 028b.4 query execution → A.3 port-based executor
- 028b.5 Q3 emitter → A.4 serializer
- 028b.6 ground-truth validation → A.5 validator
- 028b.7 determinism → A.6 harness

Remaining unique to 028b: 028b.1b (wrapper prerequisite), 028b.3 (tenant
index — the substantive unique work), 028b.8 (documented run via RunLeg),
028b.9 (closeout).

## 028b.1b — BaselineSynquestEngine wrapper (prerequisite)

`BaselineSynquestEngine` does not exist — 028b.2 has nothing to wire to until
it does. Wrapper presenting the baseline's `HybridSearcher` + `RrfFusion` as
a `SynquestEngine` port implementation.
Acceptance: wrapper compiles against the port; delegates lexical/vector/
hybrid legs to production components. Estimate: 3 hr. Depends on: 028b.1.
Evidence: scaffold commit; delegation test on mini fixture.

## 028b.1 — Module scaffold and BaselineBench retirement decision

Description: Create the multi-tenant benchmark module. Decide BaselineBench's
fate: delete, archive, or leave deprecated.
Acceptance: new module builds, suite green; BaselineBench status documented
(deleted / archived / deprecated-with-notice); production `HybridSearcher` and
`RrfFusion` reused (frozen baseline definition, 006). Estimate: 2 hr.
Depends on: —. Evidence: skeleton commit; retirement commit or deprecation note.

## 028b.2 — Wire shared corpus loader to baseline engine (re-scoped)

Original 028b.2 ("build a multi-tenant corpus loader") is subsumed by A.2 —
rebuilding it would duplicate the shared module and risk an incompatible
loader. This task wires the existing loader instead.

Description: consume the shared streaming loader (`synanton-bench-emitter`,
A.2) to feed the baseline engine's per-tenant index. Loader streams rows;
indexer consumes; nothing holds 160k×384 in memory. Manifest-first
fail-fast inherited (no baseline-specific error path); corpus_version
exposed for Q3 passthrough.
Acceptance: baseline loads v1 via shared loader; peak heap ≤ 1g (subprocess
pattern, full corpus); manifest version + row count logged; missing manifest
surfaces the shared loader's named error. Test:
`baselineLoadsV1CorpusStreamingViaSharedLoaderAtOneGigabyteHeap`.
Estimate: 3 hr. Depends on: shared emitter (main), 028b.1b.
Evidence: green test at -Xmx1g on full v1; negative test; version+count log.
Explicitly not: new loader, in-memory corpus, manifest redefinition,
 CorpusIo/Manifest reimplementation.

## 028b.3 — Tenant-scoped index construction

Description: Build per-tenant Lucene indexes (multi-tenant partitioning).
Acceptance: 50 tenant indexes, Zipf-distributed; no cross-tenant leakage at
build; index size/build time recorded for the Phase 4 cost model.
Adjacent acceptance (pinned now, executed here): wrapped baseline produces
identical top-K and scores to BaselineBench on a small fixture (equivalence
boundary — must not be dropped).
Estimate: 6 hr. Depends on: 028b.2.
Evidence: index inventory test; per-tenant counts match Zipf; equivalence test.

## 028b.4 — Query execution over the corpus

Cross-branch question (emitters fan out per tenant — single-tenant port
contract; see `QueryExecutor` on `DESIGN-emitter-shared`): does the baseline
fan out over per-tenant indexes the same way, or query once? If the same,
timings are comparable. If not, R3's timing column carries a topology
annotation (`timing_scope`: `single` vs `summed_fanout_N`, now a Q3 field).
Resolve before the baseline run commits, not at R3.


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
