# First Verified-Source-Span Consumer Migration Proposal

**Status:** Proposal only. No production implementation, schema change, adapter change, migration, benchmark, or baseline change.  
**Issue:** #954. Related tracker: #947.  
**Scope:** Select exactly one first consumer of verified Markdown source spans and define its acceptance contract.

## 1. Selected first consumer

**Consumer:** `EvidenceBinder` in the P1/A2/A4 evaluation evidence path.

**Concrete call paths inspected:**

- `RegexBaselineA4` → `EvidenceBinder.bind(...)`
- `GpuSemanticEvaluation` → `EvidenceBinder.bind(...)`
- `A2FixtureValidationTest` → `EvidenceBinder.bind(...)`
- `EvidenceBinderTest` → `EvidenceBinder.bind(...)`
- P1 conformance path → `StructuralEvidenceResolver.resolveDocumentSpan(...)` remains a separate canonical-offset path for this milestone.

`EvidenceBinder` currently accepts:

- `DocumentStructure` (canonical structural artifact)
- extraction text
- `Mapping`
- `Candidate` tagged as `STRUCTURAL_DOCUMENT`, `STRUCTURAL_CHUNK`, or `EXTRACTION_INPUT`

It produces `Evidence` with canonical document/chunk offsets, element IDs, geometry, and grounding.

### Why this consumer first

- Evaluation-only: no production graph writes, no ingestion behavior change, no Platform consumer migration.
- Already models `EXACT`, `APPROXIMATE`, and document-scope evidence, so the new source-span path can preserve existing evidence semantics.
- Frozen P1/A2/A4 artifacts can be compared without changing scoring or denominators.
- It exercises the producer evidence boundary without touching `SemanticChunkStage` or graph persistence.
- It is safer than `GraphWriter`, which is a production graph-evidence sink and reconstructs document offsets from chunk offsets.

### Not selected for this milestone

- `GraphWriter`: production graph writes; higher blast radius; requires chunk-to-source mapping.
- `SemanticChunkStage`: already confirmed to ignore source spans without changing chunking; not an evidence resolver.
- `PersistStage`: carries payload metadata; not an evidence-resolution consumer.
- Full #947 migration of all historical consumers: too broad for one milestone.

## 2. Proposed source-span path

Add a narrow evaluation-facing resolver input, without changing existing `EvidenceBinder` spaces:

1. Build a `SourceEvidenceIndex` from the Markdown `DocumentPayload`:
   - document ID and source descriptor;
   - element ID → ordered `SourceLocation` records;
   - canonical source bytes access for test/evaluation fixtures.
2. Add an evaluation method:
   ```text
   EvidenceBinder.bindSourceSpan(
       SourceEvidenceIndex source,
       SourceSpanCandidate candidate)
   ```
   or an equivalent evaluation-only adapter, returning the existing `EvidenceBinder.Result`.
3. Keep all existing `bind(DocumentStructure, ...)` behavior unchanged.
4. Do not pass source spans through `GraphWriter` or production persistence in this milestone.

The source-span method reads only:

- source identity/digest/version;
- element ID;
- `SourceLocation` with `SOURCE_TEXT_LAYER`/`ORIGINAL_SOURCE` as applicable;
- precision, reproducibility, unit, coordinate space, and range.

It does not search parser text, flattened text, normalized text, or rendered output.

## 3. Evidence outcome contract

### EXACT

Return resolved evidence only when:

- source identity is present and matches the fixture bytes;
- element ID resolves;
- location precision is `LOCATION_PRECISION_EXACT`;
- reproducibility is `REPRODUCIBILITY_EXACT_FROM_SOURCE` or a documented equivalent;
- unit is `UTF8_BYTE`;
- coordinate space is `ORIGINAL_SOURCE` (Markdown) or a valid derived text layer;
- start/end are in-bounds, half-open, non-empty, and slice the expected source bytes;
- source digest/version match.

### APPROXIMATE

Return approximate evidence only when:

- the producer explicitly marks precision `APPROXIMATE`; or
- reproducibility is explicitly `DEPENDS_ON_PARSER`/`DEPENDS_ON_NORMALIZATION` and the source range is still present and in-bounds.

Approximate evidence must retain the grounding/limitation; it must not be relabeled exact.

### UNAVAILABLE

Return unavailable/unmapped when:

- source identity is missing;
- no source location exists for the element;
- location precision is `UNAVAILABLE`;
- the range is out-of-bounds, empty, or unsupported coordinate unit/space;
- the source digest/version does not match.

### Rejection rules

Reject rather than downgrade or infer when:

- a canonical-text offset is supplied as a source offset;
- a range is derived from parser/flattened/normalized text;
- the element has multiple source locations and the candidate does not identify one unambiguously;
- source identity is missing while a location claims `INHERIT_DOCUMENT`;
- digest/version mismatch occurs;
- a text range is requested for a format/source lacking a verified text layer.

Absent evidence, unknown provenance, and known absence remain distinct:

- absent source location → unavailable;
- unknown provenance → not evidence;
- `NONE_KNOWN` provenance → known absence, not unavailable;
- unresolved execution/log range references → invalid, not unknown.

## 4. Evaluation compatibility

Affected frozen paths:

- P1 structural/evidence conformance tests.
- A2 fixture validation and `EvidenceBinderTest`.
- A4 `RegexBaselineA4` and A4.1 inspection artifacts.
- A8/A8.1 and A9 outputs only where evidence consumers are reused.

Compatibility rules:

- Existing `EvidenceBinder.bind(...)` outputs remain byte-for-byte/structurally unchanged for frozen fixtures.
- New source-span evidence is additive and evaluation-scoped.
- Compare old canonical evidence and new source evidence in a separate report; do not replace old outputs.
- Record exact vs approximate vs unavailable explicitly.
- Do not change A2 annotations, A4 baseline, A4.1 artifacts, A8/A8.1, A9, ontology releases, evaluation gates, scoring, or denominators.
- No benchmark or semantic-extraction rerun is required for this proposal.

## 5. No reconstruction invariant

**Source offsets must never be reconstructed by searching parser text, flattened text, normalized text, or rendered output.**

Source evidence must originate from producer-verified `SourceLocation` records and be validated against the immutable source identity. A range that cannot be tied to a producer-verified location is unavailable, not inferred.

## 6. Focused validation plan

### Unit/integration tests

1. Exact Markdown heading/paragraph/list/code source span resolves.
2. CRLF/CR source preserves original bytes while parser text remains normalized.
3. Supplementary Unicode and combining marks preserve byte boundaries.
4. Approximate location returns approximate grounding and retains limitation.
5. Unavailable location returns unavailable/unmapped.
6. Missing source identity is rejected.
7. Out-of-bounds, empty, reversed, or unsupported unit/space ranges are rejected.
8. Canonical offset supplied as source offset is rejected.
9. Multiple locations require explicit unambiguous selection.
10. Text search cannot recover a missing source range.

### Regression checks

- Existing `EvidenceBinder` canonical-space tests unchanged.
- P1/A2/A4 frozen comparisons unchanged.
- Chunk identities and source-element attribution unchanged.
- No evaluation denominator, score, or artifact changes.

## 7. Implementation boundary for next milestone

A subsequent implementation PR may contain:

- evaluation-only `SourceEvidenceIndex`/resolver code;
- focused exact/approximate/unavailable tests;
- fixture-backed source-byte comparisons;
- documentation.

It must not contain:

- PDF work or PDF adapter parity;
- schema or Markdown producer changes;
- production graph writes or consumer migration;
- #947 full migration;
- benchmark reruns or baseline edits.

## 8. Exit criteria

- One consumer is selected and exercised against verified Markdown source spans.
- Exact/approximate/unavailable outcomes are explicit and tested.
- Existing P1/A2/A4 evaluation outputs remain reproducible.
- No source offset is reconstructed from text.
- The implementation scope is narrow enough for a single coherent PR.
