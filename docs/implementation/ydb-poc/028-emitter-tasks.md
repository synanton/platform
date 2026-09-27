# Emitter Tasks — Shared Template (028c / 028d / 028e)

**Owners:** 028c PG workstream (024A over ingestion-cache/Lucene) · 028d YDB
workstream (024B) · 028e PG workstream (PG leg)
**Estimate:** ~13 hr / ~1.5 days per emitter; all three build in parallel
**Gate:** Blocked on 028a corpus + `EmitterContractTest` green (already green).
**Contract source:** the comparator's parser (`synanton-bench-convergence`
`RunOutput`) — not this file. This template is procedure, not schema.

## 028[c/d/e].1 — Read corpus + manifest

Description: Load v1 corpus. Read manifest version. Fail fast on missing manifest.
Acceptance: load succeeds; missing-manifest negative test.
Estimate: 2 hr. Depends on: 028a corpus, `EmitterContractTest`.
Evidence: load test + negative test.

## 028[c/d/e].2 — Execute golden queries

Description: 120 golden queries against the adapter. Query vectors read from
manifest, never recomputed.
Acceptance: coverage matrix matches baseline; vectors-from-manifest logged.
Estimate: 3 hr. Depends on: 028[c/d/e].1.
Evidence: coverage matrix.

## 028[c/d/e].3 — Q3 emitter

Description: Emit Q3 JSON per parser schema. Pass `EmitterContractTest`.
Acceptance: contract test green; sample output committed.
Estimate: 3 hr. Depends on: 028[c/d/e].2.
Evidence: contract test green + sample Q3.

## 028[c/d/e].4 — Ground-truth validation

Description: Same rule as 028b.6 — validate `eligible_set` against generator
ground truth before the comparator (the leakage barrier).
Acceptance: validation green; corrupted-set negative test fails.
Estimate: 2 hr. Depends on: 028[c/d/e].3.
Evidence: validation + negative control.

## 028[c/d/e].5 — Determinism check

Description: Two runs, same corpus → identical top-K sets.
Acceptance: determinism test green.
Estimate: 2 hr. Depends on: 028[c/d/e].4.
Evidence: determinism test.

## 028[c/d/e].6 — Documented emitter run

Description: Produce `[024a|024b|pg]-v1.json` for R2/R3 with env metadata.
Acceptance: output + checksum + env metadata committed.
Estimate: 1 hr. Depends on: 028[c/d/e].5.
Evidence: committed output.

## What implementers must not do

- Don't start before §8 signatures (028a-gated tasks) or `EmitterContractTest`
  green (emitters — already green).
- Don't recompute query vectors — read from manifest.
- Don't emit Q3 that fails the parser — the contract test catches this pre-R3.
- Don't skip ground-truth validation of `eligible_set`.
- Don't modify thresholds in code — config only (006 re-freeze procedure).
