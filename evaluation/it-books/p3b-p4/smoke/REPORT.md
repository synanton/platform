# Regex baseline smoke — report

**Run ID:** `regex-baseline-smoke-v1`
**Status:** SMOKE COMPLETE · **full P3b/P4 evaluation NOT started**
**Authorization:** `evaluation/it-books/p3b-p4/smoke/AUTHORIZATION.md`
**Provider:** `RegexExtractorAdapter` only
**Configuration:** `EXTRACTION_PROVIDER=regex`
**Input:** A2 `it-books-a2-v1`, P1 `STRUCTURAL_CHUNK`
**Evidence space:** `STRUCTURAL_CHUNK` via P3a `EvidenceBinder`

## Result

| Metric | Result |
|---|---:|
| Structural chunks processed | 10 |
| Entity candidates emitted | 70 |
| Relation candidates emitted | 3 |
| Evidence spans resolved | 73 |
| UNMAPPED | 0 |
| MISBOUND | 0 |
| Unsupported entity types | `Identifier`: 9 |
| Unsupported predicates | `is_a`: 3 |

Emitted entity types: `Concept` 61, `Identifier` 9. Emitted relation predicate: `is_a` 3.
Every emitted entity and relation evidence span was passed through P3a and resolved to a structural
chunk/element. No document-scope fallback was used.

## A2 context

A2 remains the independent judgment authority. This smoke did **not** score precision/recall or
match regex output to A2 accepted/rejected relations. A2 counts are included only as context:

- accepted: 8
- rejected: 8
- unjudged: 1

Missing regex output is not a negative judgment. Unsupported types/predicates are reported
separately and are not converted into ontology judgments.

## Reproducibility

Two independent executions produced byte-identical `results.json` and the same
`resultsSha256=116dbe015c91ba7f15579cf82a45f5805f7ffab3dca62df3a4733cdb4c605201`.
The run manifest records the implementation commit, A2/P0/P1/P3a identities, input bytes, runtime
and JVM max memory. Runtime/timestamp are intentionally outside `results.json`.

## Limitations

- Regex emits only Identifier/Metric/Concept and cue-derived predicates; it cannot demonstrate
  A1 class or predicate coverage.
- `is_a` is not one of the eight A1 predicates; all emitted relations are unsupported for A2
  relation evaluation.
- The fixture is Markdown, so geometry is `NOT_APPLICABLE`; this smoke does not establish PDF bbox
  behavior.
- The run did not use an LLM, GPU/Gateway, automatic fallback, graph writes, canonical assignment,
  P2, Docling, or full P3b/P4 evaluation.
- The harness is evaluation tooling, not a new extraction backend.

## Files and commands

Artifacts: `results.json`, `run-manifest.json`, `authorization.json`, `AUTHORIZATION.md`.
Results/run hashes: `SHA256SUMS`.

```bash
./gradlew :java:synflux:regexBaselineSmoke \
  --args="--out evaluation/it-books/p3b-p4/smoke"
./gradlew :java:synflux:test \
  --tests 'org.synanton.synflux.document.evidence.A2FixtureValidationTest' --rerun-tasks
cd evaluation/it-books/p3b-p4/smoke && sha256sum -c SHA256SUMS
```
