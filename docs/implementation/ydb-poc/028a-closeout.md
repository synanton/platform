# 028a Closeout — v1 Corpus Generator

**Status:** Complete (028a.1–028a.11 green, full suite)
**Date:** 2026-09-27
**Branch:** `DESIGN-YDB-028a-impl`

## What this corpus is (30 seconds)

20,000 documents / 160,000 chunks (exactly 8/doc) / 120 golden queries across
50 Zipf tenants, 384-d correlated embeddings (token-direction construction,
σ=0.3), deterministic seed 42 with byte-identical output. Query vectors ship
as centroids; relevant and eligible ids are distinct fields. Generate with one
command (`CorpusEmit <outdir>`); consume via `manifest.json`.

## Spec deviations (resolved in implementation, not silent)

| Spec wording | Resolution | Where |
|---|---|---|
| §6b "top-K overlap ≥ 0.7" (reads as \|A∩B\|/max; unreachable at 0.5 ceiling) | Recall-style \|retrieved ∩ relevant\| / \|relevant\| | `ChunkEmbedding` javadoc; §6b clarification flagged |
| §3 cone σ unspecified numerically | 0.03 (cone), 0.3 (chunk); 4× term repetition as signal budget | `TokenDirections`, `ChunkRecord` |
| Chunk distribution "8 avg" | Exactly 8/doc (160k exact both ends) | `DocRecord.CHUNKS_PER_DOC` |
| `putPOJO` serialization assumption | `valueToTree` (in-memory inspection was size-0 while bytes were right) | `CorpusIo`, `CorpusManifest` |
| Heap assumption (default worker) | Streaming `CorpusIo`; 2g margin with 1g subprocess proof | `CorpusIo`, `DeterminismTest` |

## Accumulated notes (from running commentary)

- **Generous coherence:** recall 1.00 vs 0.7 gate on 20 unfiltered legs — the
  corpus is comfortably coherent, not at the edge. Do not tune σ upward on
  assumed slack.
- **§6b vs comparator formulas differ by design:** §6b = recall (corpus
  coherence); comparator = \|A∩B\|/max (candidate-vs-baseline similarity).
  Same word "overlap," different purposes — do not unify.
- **Phantom-SKIP extension:** a missing test result is infra fault until
  proven otherwise (heap OOM surfaced as silent SKIP). Applies suite-wide.
- **Streaming constraint:** all Q3 emitters stream rows; in task lists, not
  just here.
- **σ-first pin unused:** correct outcome — the pin directs failure, failure
  never came.
- **Joint-bump residual:** human keystroke not automatable; everything around
  it wired shut (single-sourced versions + sha tripwire).
- **Recall scope:** filtered legs carry no recall gate (relevant scatters
  across tenants); recall gates unfiltered lexical only.

## Task → evidence map

| Task | Evidence (all green) |
|---|---|
| 028a.1 scaffold | `ModuleWiringTest` |
| 028a.2 dealing | `TenantDealingTest` (max dev 0.023 vs 0.25) |
| 028a.3 documents | `DocRecordTest` (20k, stable ids, 50 tenants) |
| 028a.4 chunks | `ChunkRecordTest` (160k, 600 distinct planted, metadata shapes) |
| 028a.5 embeddings | `ChunkEmbeddingTest` (384/f32-LE/norm, cone sanity) |
| 028a.6 queries | `GoldenQueryTest` (120, centroid byte-identity, tiers) |
| 028a.7 correlation | `CorrelationTest` (recall 1.00, negative control) |
| 028a.8 determinism | `DeterminismTest` (cross-process shas match) |
| 028a.9 manifest | `ManifestTest` (schema, consumer fixture, tripwire) |
| 028a.10 emission | `CorpusEmissionTest` (round-trip paths+shas), README |

## First consumer

PG-POC-018 draft (`018-q3-emitter-draft.md`, PG branch) reads this corpus via
`manifest.json`. Corpus-half contract: `028a-format-spec-corpus.md` (§§1–9,
signed §8).
