# Consumer Compatibility Assessment — Partial Markdown Evidence

**Status:** Assessment and regression tests only. No production behavior change.  
**Scope:** Verify platform consumers safely accept the Markdown adapter payload with verified source spans and absent optional evidence.  
**Frozen:** PDF work, adapter migration, #20/#21, #950/#947, schema changes, benchmarks, baselines.

## 1. Consumer inventory

| Consumer | Responsibility | New contract fields used |
|---|---|---|
| `ExtractionStage` | Parse `DocumentPayload`, build `ParsedDocument` | None; retains flat text, metadata, payload, feature states, descriptor |
| `SemanticChunkStage` | Build chunks from `DocumentElement` structure | Element type, text, IDs, child IDs, level; no source locations/canonical/execution/projections |
| `PersistStage` | Persist input/chunk metadata | Existing parsed text/structure; no new source-location fields |
| Evaluation/tests | Historical evidence and chunk checks | Existing payload fields; focused tests added here |
| `EvidenceBinder` / `StructuralEvidenceResolver` | Historical evidence compatibility | Not in the Markdown production consumer path |

No production consumer currently reads `source_locations`, canonical text identity, execution attribution, or projections.

## 2. Compatibility matrix

| Contract behavior | Current consumer behavior | Assessment |
|---|---|---|
| Canonical identity absent | Ignored | Compatible; no identity fabricated |
| Execution attribution absent | Ignored | Compatible; no provenance inferred |
| Projections absent | Falls back to existing flat text behavior | Compatible; no lossless claim made |
| Multiple source locations | Ignored by chunker; retained in payload | Compatible for current chunking |
| `UNKNOWN` / `NONE_KNOWN` | Not consumed | No current inference; future evidence consumers must preserve distinction |
| Missing source identity | Markdown adapter fails closed | No payload emitted |
| Source offsets | Not used to reconstruct text or chunk boundaries | No reconstruction observed |
| Legacy `ElementLocation` | Existing behavior unchanged | Compatible |

## 3. Targeted regression coverage

Added `ConsumerEvidenceCompatibilityTest` to prove:

- A partial Markdown payload with source identity and source locations but no canonical/execution/projection data is accepted.
- Multiple source locations do not alter chunk content, ordering, or source-element attribution.
- Chunk output is identical with and without source locations; both payloads carry the same source descriptor so only locations differ.
- Absent optional evidence does not cause fabricated canonical identity, execution provenance, or projection data.
- Existing strict chunking behavior remains unchanged.

Boundary: this test constructs `ParsedDocument` directly to verify `SemanticChunkStage`. It does not prove the full `ExtractionStage` mapping path or opaque-payload preservation end to end; those remain inventory findings, not regression evidence.

## 4. Findings

### Compatible today

- `SemanticChunkStage` ignores new evidence fields.
- `ExtractionStage` preserves the opaque payload.
- Existing tests and evaluation paths continue to use legacy fields.

### Gaps

- No production consumer currently consumes verified source spans for evidence resolution.
- No consumer test yet proves behavior for future canonical/execution consumers.
- `UNKNOWN` vs `NONE_KNOWN` is not exercised because no consumer reads execution attribution.

## 5. Exit criteria

This assessment supports Markdown producer compatibility with current consumers when:

- Partial payloads are accepted.
- Optional fields remain absent without defaulting to exact/zero.
- Chunk boundaries and identities remain unchanged.
- No source offset is reconstructed from text.

Future consumer migration must preserve `UNKNOWN`/`NONE_KNOWN`, source-location multiplicity, and exact/approximate distinction.
