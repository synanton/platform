# Regex baseline smoke authorization

**Authorized operation:** `regex-baseline-smoke`
**Provider:** `RegexExtractorAdapter`
**Configuration:** `EXTRACTION_PROVIDER=regex`
**Input:** pinned A2 fixture `it-books-a2-v1`
**Evidence space:** `STRUCTURAL_CHUNK`
**Environment:** isolated IT Books evaluation harness; no production/customer indexes
**Preflight:** PR #916 (preflight acceptance remains separate)

## Scope

This authorizes only the regex baseline smoke over P1 structural chunks. It does not authorize full
P3b/P4 semantic evaluation, LLM extraction, GPU/Gateway use, automatic fallback, graph writes, or
canonical entity assignment.

- LLM/GPU: prohibited
- Automatic fallback: prohibited
- Full evaluation: not authorized
- Graph writes/promotion: prohibited
- Production/customer index writes: prohibited
- P2/Docling: prohibited

## Required behavior

- Run `RegexExtractorAdapter` only.
- Feed each A2/P1 `STRUCTURAL_CHUNK` as `TextSegment(chunkId, chunk.text, 0)`.
- Bind every emitted entity and relation evidence span through `EvidenceBinder` in
  `STRUCTURAL_CHUNK` space.
- Preserve `RESOLVED`, `UNMAPPED`, and `MISBOUND` outcomes separately.
- Do not invent offsets, repair spans, or substitute document-scope evidence.
- Report unsupported entity types and predicates explicitly.
- Preserve A2 accepted/rejected/unjudged counts without treating missing regex output as a negative
  judgment.

## State

`extractionAuthorized` is scoped to this operation, not a blanket flag. `fullEvaluationAuthorized`
and `evaluationStarted` remain false until separate approval.
