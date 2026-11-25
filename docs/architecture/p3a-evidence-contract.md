# P3a evidence contract — offset spaces and binding

**Status:** accepted scope decision after P1 exit evidence (#903/#909).
**Inputs:** P1 structural contract (#904/#905), P0 compiled IT Books release, A2 independent fixture.
**Implementation contract:** `docs/architecture/p3a-evidence-binding-implementation.md` (`p3a-evidence-binding-v1`).

## Three coordinate spaces

| Space | Offsets index | Owner | Required identity |
|---|---|---|---|
| `STRUCTURAL_DOCUMENT` | Canonical normalized text for `documentId` + `normalizationVersion` | P1 structural contract | source/version/document IDs, normalization, parser, structural-artifact digest |
| `STRUCTURAL_CHUNK` | Text within one `chunkId` | P1 structural contract | chunkId, chunkingConfig, structural-artifact digest |
| `EXTRACTION_INPUT` | Text actually assembled and sent to the model (trimmed, re-chunked, prompt-wrapped, reordered) | Extraction stage | extraction text digest, template digest/compilationId, provider/model/config |

A bare offset is ambiguous. Every evidence record must declare `space`.

## Evidence record

Minimum shape:

```json
{
  "space": "STRUCTURAL_DOCUMENT|STRUCTURAL_CHUNK|EXTRACTION_INPUT",
  "startOffset": 0,
  "endOffset": 0,
  "documentId": "...",
  "sourceVersionId": "...",
  "chunkId": null,
  "elementIds": [],
  "normalizationVersion": "...",
  "geometryStatus": "AVAILABLE|UNAVAILABLE|NOT_APPLICABLE",
  "boundingBox": null,
  "templateDigest": "...",
  "compilationId": "...",
  "mappingDigest": "..."
}
```

`UNAVAILABLE`/`NOT_APPLICABLE` never mean “no evidence”; they mean evidence exists but has no
usable geometry in that adapter/space. Markdown spans are fully mappable in offset space and only
geometry-less.

## Mapping chain

```text
EXTRACTION_INPUT
  → extraction-input span record (text digest + offsets)
  → template/segment identity (templateDigest + compilationId)
  → STRUCTURAL_CHUNK or STRUCTURAL_DOCUMENT
  → elementId(s) + geometry status/bbox
```

P3a must persist a per-extraction-run mapping artifact containing the extraction-input digest, the
template/compilation identity, the chunking configuration, the structural-artifact digest, and the
mapping digest. The mapping is reproducible; it is not inferred later from a retried run.

## Failure outcomes

- `RESOLVED`: span maps to a structural element/chunk with valid offsets.
- `UNMAPPED`: no mapping entry exists for the extraction-input span.
- `MISBOUND`: mapping exists but violates offsets/element/chunk invariants.
- `UNJUDGED`: no expected annotation/A2 judgment exists.

Unmappable spans fail closed. There is no silent drop and no best-effort nearest-element guess.

## Derived scopes

**P3a:** structural/chunk-space unit tests; space-tagged evidence records; mapping artifact;
fail-closed `UNMAPPED`/`MISBOUND` outcomes.

**P3b/P4:** extraction-input-space candidates mapped through the chain; geometry metrics only where
the adapter reports `AVAILABLE`; Markdown reports no bbox metrics but still reports span mapping;
provenance completeness is required before scoring; PDF line-level/table-figure limits are explicit
scope boundaries.

**A2:** expected annotations use `STRUCTURAL_DOCUMENT`/`STRUCTURAL_CHUNK` spaces by default, the
three geometry states, and P0’s 11-class/8-relation scope. Any extra/omitted type needs explicit
reconciliation.

**P2:** not justified by current evidence; no candidate-graph fusion issue.
