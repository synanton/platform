# Extraction baseline report — deterministic regex rule vs frozen gold

**Status:** Baseline
**Last reviewed:** 2026-11-08
**Issue:** #558
**Gold:** `evaluation/gold/` (frozen; `MANIFEST.sha256`)
**Corpus:** `evaluation/corpus/` (5 documents)
**Extractor under test:** the only deterministic producer in `synflux` —
`SynfluxConfig.annotationRules()` → `KeywordAnnotationRule` (`payment-detection`, v4,
`TAG`, keywords `invoice number`, `payment reference`, `payment terms`).

## Gold dataset

| Document | Entities | Relationships |
|---|---|---|
| `halcyon-7-directive` | 10 | 7 |
| `e650-live-dense-v2` | 12 | 7 |
| `skiena-sorting` | 15 | 10 |
| `skiena-dynamic-programming` | 15 | 10 |
| `skiena-tsp-parsing` | 18 | 10 |
| **Total** | **70** | **44** |

- Resolution status: **68 resolved, 1 ambiguous, 1 unresolved**.
- Types: `Concept` 20, `Algorithm` 11, `Document` 7, `Component` 5, `Metric` 5,
  `DataStructure` 5, `Problem` 4, `MaintenanceSystem` 3, `Role` 2, `Measurement` 2,
  `Time` 2, `Procedure` 2, `Protocol` 1, `Event` 1.
- Offsets are Unicode code points into the UTF-8 corpus text; every
  `surface_form == text[char_start:char_end]` was verified at generation time.
- The gold was authored by reading `evaluation/corpus/*.txt` only; no Cassandra,
  Lucene, regex output, or model output was consulted. `annotate_gold.py` encodes
  those manual decisions and only resolves offsets.

## Baseline metrics

`python3 evaluation/eval_extraction.py` (manifest verified, 10 files):

| Metric | Value |
|---|---|
| Entity precision (span overlap) | **0.000** |
| Entity recall | **0.000** |
| Entity F1 | **0.000** |
| Resolution accuracy | **N/A** (no matched mentions) |
| Relationship precision | **N/A** |
| Relationship recall | **0.000** |
| Evidence coverage | **N/A** |
| Predicted mentions | **0 / 70** |

Per document: 0 predicted mentions; entity recall 0.00; relationship recall 0.00.

## Why the baseline is zero

This is a capability boundary, not an evaluation bug.

1. **The only deterministic producer is a three-keyword tag rule.** `payment-detection`
   matches `invoice number`, `payment reference`, or `payment terms` in a chunk and emits
   one whole-chunk `TAG` annotation. None of the five documents contains those keywords,
   so there are 0 matches.
2. **No span extraction.** The rule returns `Match(value, confidence)`; the annotation row
   is keyed by `chunk:<contentRef>:<ordinal>`. There are no `char_start`/`char_end` offsets.
3. **No entity typing.** The output type is the rule's `TAG`, not `MaintenanceSystem`,
   `Component`, `Algorithm`, etc.
4. **No resolution.** The rule cannot distinguish a resolved entity, an ambiguous alias
   (`the platform`), or an unresolved one (`Technicians`).
5. **No relationships or evidence.** Nothing in the deterministic path emits edges or
   evidence spans; relationship recall is 0 by construction.
6. **Off by default.** The annotation stage is enabled only with
   `synflux.pipeline.annotation-enabled=true`; the demo/benchmark configuration leaves it
   off, so the rule is not even exercised during normal ingestion.

## Worked failure examples (from the frozen corpus)

- **e650** — `the platform` is an ambiguous alias of `Zephyr-9`; `Technicians` is an
  unresolved role; `Zephyr-9 protocol → requires → helium leak check` spans sentences.
  A keyword rule resolves none of these.
- **halcyon** — `relief valve on the secondary manifold` needs a `part_of` relation and
  nested component resolution, not keyword presence.
- **Skiena** — `heapsort is_a sorting algorithms` needs type hierarchy; `Dynamic
  programming combines greedy and exhaustive search` needs cross-sentence relation
  extraction with evidence.

## What the RAKG/LLM pipeline must add

Acceptance targets for the next producer, measured on this same frozen gold:

1. Span-level mentions with character offsets (baseline F1 0.000 → non-zero).
2. Typed entities, not only a `TAG`.
3. Resolution status (`resolved`/`ambiguous`/`unresolved`) with canonical ids.
4. Relationship extraction with evidence spans.
5. Alias/pronoun resolution and cross-sentence relations.
6. No regression on exact/identifier mentions when adding semantic extraction.

## Reproduce

```bash
sha256sum -c evaluation/gold/MANIFEST.sha256
python3 evaluation/eval_extraction.py          # verifies the manifest, then scores
python3 evaluation/annotate_gold.py            # only to regenerate (gold is frozen)
```

`--skip-manifest` exists for debugging only; a reportable run must verify the golden
hashes.

## Caveats

- Five documents / 70 mentions is a smoke-scale baseline, not a statistically powered
  benchmark; there is no inter-annotator agreement yet.
- The gold is a single-annotator artifact; the manifest freezes it against silent drift.
- The brief’s “regex extractor (synflux / syntology)” maps to the repo’s only deterministic
  producer above. There is no generic NER/RE regex pipeline to benchmark; `syntology`
  manages ontology schemas, not text extraction.

## Artifacts

- `docs/planning/extraction-gold-schema.md` — schema and annotation protocol.
- `evaluation/corpus/*.txt`, `evaluation/gold/doc-*.json`, `evaluation/gold/MANIFEST.sha256`.
- `evaluation/annotate_gold.py`, `evaluation/eval_extraction.py`, `evaluation/baseline-result.json`.
