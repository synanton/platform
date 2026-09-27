# 028a Implementer Tasks

**Owner:** TBD (branch author acting until named; name required before implementation starts — see §8 gate)
**Branch:** `DESIGN-YDB-028a`
**Spec:** `028a-format-spec-corpus.md` (signed under §8)
**Estimate:** ~44 hr / 5.5 days (slight overrun vs 3–5 days — reasonable for this spec)
**Gate:** Do not start until §8 shows three signatures.

Each task maps to a spec section. Acceptance is per-task, cumulative. No task
closes on a documented claim; each needs evidence in the same commit.

> Note: `Spec:` references below were aligned to the spec's actual section
> numbers at commit time (§1 output, §2 embeddings, §3 Zipf, §4 queries,
> §5 eligible derivation, §6/§6b/§6c determinism, §7 emitters, §8 gate).

## 028a.1 — Module scaffold

Spec: §1 (corpus output). Description: Create the generator module
(synanton-bench-corpus or consistent equivalent). Pin dependencies. No logic yet.
Acceptance: module builds, `:test` green; no CQL/SQL/adapter imports (standalone);
version manifest entry created. Depends on: —.
Evidence: skeleton commit; empty suite green.

## 028a.2 — Deterministic dealing (Knuth-hash, no RNG state)

Spec: §3 (Zipf layout). Description: Knuth-hash tenant assignment; exact hash
documented in a code comment with a reference snippet (reproducible in any language).
Acceptance: same seed → byte-identical distribution across two runs; no
`java.util.Random`/`SecureRandom`/hidden state; ids `tenant_00`…`tenant_49`.
Depends on: 028a.1. Evidence: distribution test + documented hash.

## 028a.3 — Document generation (20k docs)

Spec: §1 (exact counts), §3 (Zipf `w(i)=1/(i+1)`, s=1.0). Description: exactly
20,000 documents, Zipf tenant assignment, stable ids + tenants.
Acceptance: `documents.jsonl` exactly 20,000 lines; distribution matches Zipf
within tolerance; ids stable across seed-42 regenerations. Depends on: 028a.2.
Evidence: count assertion, distribution histogram in test output.

## 028a.4 — Chunk generation (160k chunks)

Spec: §1 (160k chunks), §2 (planted text correlated with embedding).
Description: exactly 160,000 chunks; planted text deterministic, correlated
with assigned embedding (validated by 028a.7).
Acceptance: `chunks.jsonl` exactly 160,000 lines; stable ids; planted text
deterministic. Depends on: 028a.3. Evidence: count assertion, sample text.

## 028a.5 — Embedding generation (384-d, correlated)

Spec: §2 (384-d float32-LE base64, L2-normalized, correlated). Description:
per-chunk embedding; correlation verified by 028a.7.
Acceptance: dim exactly 384; base64 of float32-LE; norm ≈ 1.0; byte-identical
across seed-42 regenerations; no decimal rendering. Depends on: 028a.4.
Evidence: norm/dimension assertions, byte-identical regeneration test.

## 028a.6 — Golden query generation (120 queries)

Spec: §4 (120 queries), §5 (relevant vs eligible distinct). Description: 120
queries with query_id, mode, filter, `relevant_chunk_ids`, `eligible_chunk_ids`,
centroid-derived base64 query vectors (shipped, never recomputed — §2).
Acceptance: exactly 120; relevant/eligible never conflated; filters cover
none/tenant/metadata/eligibility; eligibility legs cover 0.1%/1%/10%/100%.
Depends on: 028a.5. Evidence: count, filter-coverage matrix, centroid unit test.

## 028a.7 — Correlation test (§6b)

Spec: §6b (top-K overlap with `relevant_chunk_ids` ≥ 0.7). Description: query
corpus embeddings with shipped query vectors; assert overlap. Fails at
generation time on uncorrelated output.
Acceptance: in `:test`, green; negative control (one corrupted embedding)
fails with "correlation" in the message. Depends on: 028a.6.
Evidence: green test + failing negative control.

## 028a.8 — Determinism test (§6c)

Spec: §6c (seed-42 byte-identical regeneration). Description: generate twice,
byte-compare all four outputs.
Acceptance: in standard suite; failure names file + offset. Depends on: 028a.6.
Evidence: green test; checksums in manifest.

## 028a.9 — Manifest and version

Spec: §6c (stability) + §7 (emitter rule). Description: emit `manifest.json`
with deterministic version; generator-logic change forces version bump.
Acceptance: manifest emitted; version seed-42 stable; version-bump test proves
the rule. Depends on: 028a.8. Evidence: manifest + bump test.

## 028a.10 — Corpus emission to consumable location

Spec: §7. Description: emit manifest + documents + chunks + queries to a
documented tree, reproducible from one command
(`./gradlew :<module>:generateCorpus` or equivalent).
Acceptance: tree documented in README; one-command generation.
Depends on: 028a.9. Evidence: run log + README.

## 028a.11 — Documentation and closeout

Spec: all. Description: `028a-closeout.md` mapping spec sections to tasks,
deviations with rationale, correlation result, determinism checksums,
cross-reference to PG-POC-018 draft as first consumer.
Acceptance: all §§1–8 covered; deviations none-or-explained.
Depends on: 028a.10. Evidence: committed doc.

## Sequencing

```text
028a.1 → 028a.2 → 028a.3 → 028a.4 → 028a.5 → 028a.6
                                              ├── 028a.7 (correlation)
                                              ├── 028a.8 (determinism)
                                              └── 028a.9 (manifest)
                                                    └── 028a.10 (emission)
                                                          └── 028a.11 (closeout)
```

Critical path: 1 → 2 → 3 → 4 → 5 → 6 → 10 → 11. Tasks 7, 8, 9 parallel after 6.

| ID | Title | Est (hr) | Depends on | Owner |
|---|---|---|---|---|
| 028a.1 | Module scaffold | 2 | — | TBD |
| 028a.2 | Deterministic dealing | 4 | 028a.1 | TBD |
| 028a.3 | Document generation | 4 | 028a.2 | TBD |
| 028a.4 | Chunk generation | 4 | 028a.3 | TBD |
| 028a.5 | Embedding generation | 8 | 028a.4 | TBD |
| 028a.6 | Golden query generation | 6 | 028a.5 | TBD |
| 028a.7 | Correlation test | 4 | 028a.6 | TBD |
| 028a.8 | Determinism test | 3 | 028a.6 | TBD |
| 028a.9 | Manifest + version | 2 | 028a.8 | TBD |
| 028a.10 | Emission + runnability | 3 | 028a.9 | TBD |
| 028a.11 | Documentation + closeout | 4 | 028a.10 | TBD |

## What the implementer must not do

- Do not start until §8 has three signatures.
- Do not recompute query vectors in emitters — generator ships them (§2).
- Do not use RNG state for tenant assignment — Knuth-hash only (§3).
- Do not conflate `relevant_chunk_ids` and `eligible_chunk_ids` (§5).
- Do not modify the spec to make implementation easier — surface discrepancies, never silently adjust.
