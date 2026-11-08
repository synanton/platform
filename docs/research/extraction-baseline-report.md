# Extraction baseline report — Phase A regex adapter vs frozen gold

**Status:** Baseline + Phase B prompt tuning
**Last reviewed:** 2026-11-08
**Issue:** #558 (gold + baseline), RAKG epic (follow-up)
**Gold:** `evaluation/gold/` (frozen; `MANIFEST.sha256`)
**Corpus:** `evaluation/corpus/` (5 documents)
**Extractor:** `java/extraction-engine` — `ExtractionEngineAdapter` port with
`RegexExtractorAdapter` (Phase A). Phase B tuning runs Qwen3-4B-Instruct-2507
(`qwen3-extraction` on the GPU plane) through the prompt-tuning harness; the Java
`LlmExtractionAdapter` is the next implementation gate (ADR-022).

## What changed since the 0.000 run

The first baseline ran the only deterministic producer in `synflux`: a single
`payment-detection` keyword rule (3 payment keywords, whole-chunk, no spans,
no relations). It matched nothing in the five documents → **0.000 across the
board**. That result is retained as the pre-contract history.

Phase A now runs behind the same **ports & adapters** boundary as the search
engine:

- `org.synanton.extraction.engine`: `TextSegment`, `ExtractionContext`,
  `PreEntity`, `RelationCandidate`, `ExtractionResult`,
  `ExtractionEngineAdapter`.
- `RegexExtractorAdapter`: generic identifiers, metrics, quoted phrases,
  capitalized terms, and cue-based relations (`requires`, `uses`, `is`, `part of`,
  `discussed in`, …). No LLM, no resolution.
- `LlmExtractionAdapter`: the Phase B adapter (in progress). The tuning harness
  calls the same OpenAI-compatible vLLM endpoint and is the reference for the
  Java offset-recovery/endpoint-synthesis behavior.
- `ExtractionCli`: batch bridge (`--corpus-dir … --out …`) so
  `evaluation/eval_extraction.py` exercises the Java interface, not a Python
  reimplementation.

## Baseline metrics (Phase A)

`python3 evaluation/eval_extraction.py` (manifest verified, 5 docs). The
evaluator runs both the document-level baseline and the RAKG Phase 0
sentence-chunked shape; the document-level numbers are below and the chunked
comparison follows.

| Metric | Value |
|---|---|
| Entity precision (exact span) | 0.058 |
| Entity recall (exact span) | 0.114 |
| Entity F1 (exact span) | 0.077 |
| **Entity precision (overlap)** | **0.137** |
| **Entity recall (overlap)** | **0.271** |
| **Entity F1 (overlap)** | **0.182** |
| Type accuracy (matched mentions) | 0.147 |
| Resolution accuracy | **N/A** — Phase A performs no resolution |
| Relationship precision | 0.000 |
| Relationship recall | 0.000 |
| Evidence coverage | **N/A** — no gold relation matched |
| Predicted mentions / relations | 139 / 34 (gold: 70 / 44) |

Per document (overlap F1): halcyon 0.190, e650 0.364, Skiena sorting 0.122,
dynamic programming 0.189, TSP/parsing 0.156.

## Sentence-boundary chunking (RAKG Phase 0)

`synflux` now exposes `SYNFLUX_INGEST_CHUNK_STRATEGY=sentence` backed by a
shared `SentenceChunker` (`java/extraction-engine`): whole sentences (or blank
lines), grouped to ≤ 512 tokens (chars/4), one-sentence overlap, oversize
sentence kept whole. `synflux` and the evaluator CLI share the implementation,
so the chunk offsets are document-relative and the gold spans align unchanged.

`eval_extraction.py` also runs the regex adapter per sentence chunk (the shape
Phase B will consume) and dedupes overlap mentions by span:

| Mode | Entity P | Entity R | Entity F1 | Type | Rel F1 | Pred e/r |
|---|---|---|---|---|---|---|
| document-level | 0.137 | 0.271 | **0.182** | 0.147 | 0.000 | 139 / 34 |
| sentence-chunked (512, overlap 1) | 0.137 | 0.271 | **0.182** | 0.147 | 0.000 | 139 / 30 |

**Reading:** entity F1 is unchanged — the regex adapter is span-local and does
not use the extra context, so chunking neither helps nor hurts mention
precision/recall. The only difference is four relation candidates that crossed
a chunk boundary beyond the one-sentence overlap. That is a real constraint for
Phase B: relation extraction must either widen the overlap or resolve edges
across chunks; the intended chunking win is LLM context, not a regex F1 lift.
The Phase B acceptance target therefore stays **entity F1 (overlap) > 0.182**.

## Honest failure analysis

1. **The regex finds capitalized/identifier surface forms, not entities.** It
   recovers `Halcyon-7`, `Zephyr-9`, `Dynamic Programming`, `TSP`, section
   numbers, etc., but misses lowercase domain terms that dominate the gold:
   `auxiliary coolant loop`, `relief valve`, `secondary manifold`, `optimization`,
   `recurrence relations`, `edit sequences`.
2. **Precision is low by construction.** Maximal capitalized phrases produce
   overlapping candidates (full phrase, 2-grams, hyphenated tokens): 139
   predictions for 70 gold mentions (precision 0.137). The adapter makes no type
   decision beyond `Concept`/`Identifier`/`Metric` (type accuracy 0.147).
3. **No resolution.** The contract's `PreEntity` is intentionally pre-resolution;
   `the platform` and `Technicians` remain unresolved. Phase C adds retrieval-
   assisted resolution via the `SearchEngineAdapter`.
4. **Relationship extraction is 0.000.** The cue-based relations are only as good
   as the endpoints: most gold objects are lowercase terms the regex never emits
   (e.g. `Zephyr-9 protocol requires helium leak check`), and the predicate
   vocabulary differs (`has_component`, `tested_every`, `signs`, `records`). 34
   candidate relations were produced; 0 aligned to a gold edge.
5. **No evidence coverage yet** because no relation matched.

## Phase B prompt tuning (2026-11-08)

Qwen3-4B-Instruct-2507 (`qwen3-extraction`, vLLM on node3) was run over the same
sentence chunks through `evaluation/llm_tuning.py`. The model returns surface
forms with unreliable char offsets; the harness recovers spans by matching the
surface form (evidence-quote anchored), synthetically adds relation endpoints
missing from `preEntities`, and scores with the same frozen-gold functions.

| Metric | Regex (Phase A) | Qwen3-4B tuning |
|---|---|---|
| Entity P / R / F1 (overlap) | 0.137 / 0.271 / **0.182** | 0.636 / 0.600 / **0.618** |
| Entity exact F1 | 0.077 | 0.515 |
| Type accuracy | 0.147 | 0.717 |
| Relationship P / R / F1 | 0.000 / 0.000 / **0.000** | 0.086 / 0.068 / **0.076** |
| Predicted mentions / relations | 139 / 34 | 66 / 35 |
| Evidence coverage | N/A (0 matched) | 1.0 (3 matched) |
| Resolution accuracy | N/A | N/A |

Per-document entity F1: halcyon 0.842, e650 0.737, sorting 0.714, dynamic
programming 0.519, TSP/parsing 0.465. Per-document relationship F1: 0.400, 0.167,
0.000, 0.000, 0.000.

**Reading:** both Phase B targets are met in the tuning harness (entity F1 > 0.182,
relationship F1 > 0). The remaining relationship gap is docs 3–5: the Skiena text
states implicit algorithmic relations, and the model's predicate/endpoint choices
diverge from the gold. Do not hide that in adapter heuristics; prompt v2 and
resolution are the next levers.

**Caveats:** the model's raw offsets are unusable (hence surface-form recovery);
synthetic endpoints slightly inflate recall; the harness is Python, not the Java
adapter; five documents is smoke-scale. The Java adapter must reproduce these
numbers before they are treated as production evidence.

## What the frozen gold measures next

Phase B met the first three targets in the tuning harness. The next gate is the
Java `LlmExtractionAdapter` reproducing them end-to-end, then:

- Entity F1 (overlap) **> 0.618** with precision above 0.8; type accuracy **> 0.717**.
- Relationship F1 **> 0.076**, with evidence spans that overlap the gold.
- Resolution accuracy becomes measurable (resolved / ambiguous / unresolved) and
  coreference/aliases are anchored rather than dropped.
- No regression on the capitalized/identifier mentions the regex already finds.

## Reproduce

```bash
sha256sum -c evaluation/gold/MANIFEST.sha256
python3 evaluation/eval_extraction.py      # regex baseline, both chunk modes
# Phase B tuning against the live GPU plane:
kubectl -n gpu-plane port-forward deploy/vllm-extraction 8000:8000
python3 evaluation/eval_extraction.py --llm-endpoint http://localhost:8000
python3 evaluation/eval_extraction.py --llm-endpoint http://localhost:8000 --only doc-5
```

`--skip-manifest` exists for debugging only; a reportable run must verify the
golden hashes. The evaluator writes `evaluation/regex-predictions.json`,
`evaluation/regex-predictions-chunked.json`, and `evaluation/baseline-result.json`
(both modes).

## Artifacts

- `java/extraction-engine/` — contract, regex adapter, LLM adapter, CLI, chunker, tests.
- `docs/planning/extraction-gold-schema.md` — schema and protocol.
- `evaluation/corpus/*.txt`, `evaluation/gold/doc-*.json`, `MANIFEST.sha256`.
- `evaluation/eval_extraction.py`, `evaluation/llm_tuning.py`,
  `evaluation/prompts/llm_extraction_system.txt`,
  `evaluation/regex-predictions.json`, `evaluation/regex-predictions-chunked.json`,
  `evaluation/baseline-result.json`, `evaluation/llm-predictions.json`,
  `evaluation/llm-baseline-result.json`.
- `docs/architecture/decisions/adr-022-extraction-engine-architecture.md` — port,
  provider flag, offset recovery, prompt versioning.

## Caveats

- Five documents / 70 mentions is a smoke-scale baseline, not a statistically
  powered benchmark; no inter-annotator agreement yet.
- The regex adapter is a baseline, not a candidate production extractor.
- The previous payment-keyword run (0.000) is superseded by this Phase A result;
  both are recorded in the git history of this report.
