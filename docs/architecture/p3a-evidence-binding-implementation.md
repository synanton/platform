# P3a evidence binding implementation

**Contract version:** `p3a-evidence-binding-v1`
**Parent:** #912 · **Prerequisite:** accepted P1 `#903` · **Companion:** `docs/architecture/p3a-evidence-contract.md`

Implementation: `org.synanton.synflux.document.evidence.EvidenceBinder`.

## Inputs

- `DocumentStructure` — the P1 artifact, including parser/normalization/chunking provenance.
- `extractionText` — the exact text sent to the model for this run.
- `Mapping` — a pinned extraction-input → structural-chunk mapping segment list.
- `Candidate` — an explicitly space-tagged candidate span.

Every evidence record declares one of `STRUCTURAL_DOCUMENT`, `STRUCTURAL_CHUNK`, or
`EXTRACTION_INPUT`, plus `EXACT`, `APPROXIMATE`, or `DOCUMENT_SCOPE` grounding.

## Mapping artifact

`Mapping` carries:

- `extractionTextSha256`
- `structuralArtifactDigest`
- `chunkingConfig`
- `templateDigest`
- `compilationId`
- ordered, non-overlapping `Segment`s: input offsets → chunkId + chunk-relative offsets + exact/approximate quality

`mappingDigest` is SHA-256 over the canonical `extraction-mapping-v1` record and segments. The
binder rejects a structural/extraction/template/compilation identity mismatch as `MISBOUND`.

The mapping is supplied per extraction run; the binder never guesses alignment from text and never
falls back to nearest-element matching.

## Outcomes

| Outcome | Meaning |
|---|---|
| `RESOLVED` | Span maps to canonical text, chunk(s), element(s), and provenance. |
| `UNMAPPED` | No/partial extraction-input mapping, or no structural element. |
| `MISBOUND` | Identity mismatch, out-of-range offsets, invalid chunk, non-contiguous/ambiguous mapping, or missing geometry provenance. |

`DOCUMENT_SCOPE` is honored only when the candidate explicitly declares it. A failed span binding
never becomes document-scope evidence.

## Geometry

Bounding boxes are emitted as fixed-scale decimal strings, never raw floats:

- scale: 6
- rounding: `HALF_UP`
- `-0.0` canonicalizes to `0.000000`
- NaN and infinities are rejected
- `AVAILABLE`, `UNAVAILABLE`, and `NOT_APPLICABLE` remain distinct
- page number, coordinate system and unit come from the P1 page record
- cross-element evidence on one page uses the deterministic bbox union; cross-page or mixed
  geometry becomes `UNAVAILABLE`

`NOT_APPLICABLE` (e.g. Markdown) means no geometry, not no evidence.

## Metrics

`EvidenceBinder.Report` records total/resolved/unmapped/misbound, exact/approximate/document-scope,
explicit failure reasons, `misbindingRate`, `silentDropRate`, and `silentDrops`. The binder accounts
for every candidate; it has no silent-drop path.

## Boundary

- No LLM calls.
- No canonical entity assignment, relation acceptance, graph fusion, promotion, or new ledger.
- No extraction execution or backend integration.
- No Docling runtime/dependency.
- No F1/gold/gate/profile/ADR-044 change.
