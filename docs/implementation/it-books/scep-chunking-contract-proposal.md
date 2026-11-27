# SCEP Chunking Contract Proposal

**Status:** Specification only. No implementation, benchmark rerun, or frozen-artifact change.  
**Basis:** run-v2 (`synthetic-ingestion-v1-run2`) and its diagnosis.  
**Related:** #936, #966, PR #968.  
**Decision labels:** **SUPPORTED BY EXISTING CONTRACT**, **PROPOSED**, **REQUIRES APPROVAL**.

## 1. Evidence baseline

Run-v2 produced 12 chunks against 14 gold chunks:

- Missing container paths: book, Chapter 1, Chapter 2.
- Appendix A emitted as both TABLE and SUBSECTION.
- All chunk document offsets are `-1`.
- Chunk text contains a breadcrumb prefix and repeated heading.
- SCEP payload had 51 elements, 51 original-source locations, and 0 canonical ranges.
- 37 element IDs appear in chunk provenance; 14 IDs are absent, consistent with heading
  elements not entering `section.elements()`.

The raw 51-element payload was not retained, so exact element-level replay remains unavailable.
This proposal relies on chunk captures plus the current producer/chunker code.

## 2. Heading and container chunks

### Decisions

- **SUPPORTED BY EXISTING CONTRACT:** every heading defines a structural boundary; no chunk may
  cross that boundary. See `docs/implementation/it-books/p1-structural-contract.md` and the frozen
  gold contract in `evaluation/it-books/synthetic-ingestion-v1/gold/CONTRACT.md`.
- **PROPOSED:** emit one chunk for every heading that is a content section or a structural
  container.
  - A structural container is a heading with one or more descendant headings.
  - A content section is a heading with direct non-heading content.
  - The chunk includes the heading text once, followed by its direct body, and stops before the
    next heading at any level.
- **PROPOSED:** an empty leaf section (no body, no children) does not emit a semantic content
  chunk. It is recorded as an explicit empty-section diagnostic, not silently dropped.
- **REQUIRES APPROVAL:** if the benchmark requires a chunk for every heading including empty
  leaves, the empty-leaf rule must change before implementation. This proposal does not silently
  invent empty chunks.
- **PROPOSED:** heading provenance is retained:
  - the heading element ID is included in `sourceElements`;
  - the heading's source/canonical range contributes to the chunk boundary;
  - structural container chunks use the existing `SemanticChunk.ChunkType.HEADING`
    (`chunk_type="HEADING"` after persistence);
  - content-section chunks remain `SECTION` or `SUBSECTION`;
  - empty leaves emit no semantic chunk and are recorded only in evaluation diagnostics.
  No new enum value, field, or persistence schema is required.

### Run-v2 mapping

Book, Chapter 1, and Chapter 2 are structural containers. They currently produce no chunk because
`DocumentStructureBuilder` puts headings in `SectionNode.heading` rather than
`section.elements()`, and `SemanticChunker.flushBatch()` emits nothing for an empty batch.

References:
- `java/synflux/src/main/java/org/synanton/synflux/chunking/DocumentStructureBuilder.java:33-53`
- `java/synflux/src/main/java/org/synanton/synflux/chunking/SemanticChunker.java:124-136`

## 3. Atomic table handling

### Decisions

- **SUPPORTED BY EXISTING CONTRACT:** SCEP `DocumentElement.child_ids` establishes table
  hierarchy. The Markdown producer sets TABLE child IDs to its TABLE_ROW IDs, and each TABLE_ROW
  child IDs to its TABLE_CELL IDs.
- **SUPPORTED BY EXISTING CONTRACT:** the gateway propagates `child_ids` unchanged into the
  payload descriptor elements.
- **PROPOSED:** table atomicity is defined by descendant identity, not list adjacency:
  - resolve the TABLE element's complete descendant closure from `child_ids`;
  - emit exactly one TABLE chunk for the TABLE element;
  - consume TABLE_ROW and TABLE_CELL descendants from normal paragraph/batch emission;
  - never infer table membership from "the next row/cell-shaped element".
- **PROPOSED:** a subsequent independent TABLE remains independent even if it immediately follows
  another table or has no intervening paragraph.
- **PROPOSED:** table source evidence retains the TABLE element ID plus its descendant IDs; the
  chunk's contiguous range, if available, is the table's declared span.

References:
- `content_extractor/java/adapter-document-text/src/main/java/org/synanton/extraction/adapter/document/text/TextModalityAdapter.java:279-330`
- `content_extractor/java/extraction-gateway/src/main/java/org/synanton/extraction/adapter/in/grpc/ResultMapper.java:227`
- `java/synflux/src/main/java/org/synanton/synflux/chunking/SemanticChunker.java:63-70`

### Run-v2 mapping

Appendix A's TABLE (`e24`) emits one chunk; its row/cell descendants (`e25-e48`) are then treated
as ordinary batch content, producing the duplicate SUBSECTION chunk. The fix must use the explicit
child closure and must not assume that all later row/cell elements belong to the prior table.

## 4. Source offsets and evidence

### Coordinate space

- **SUPPORTED BY EXISTING CONTRACT:** chunk document offsets use UTF-16 offsets into the canonical
  structural text, not raw original-source bytes.
- **SUPPORTED BY EXISTING CONTRACT:** the frozen gold offsets match the P1 structural text.
  `SyntheticFixtureValidationTest.goldManifestMatchesP1StructuralTextAndEvidence` passes against
  the unchanged gold.
- The apparent 9–14 byte discrepancies versus source-byte slicing are explained by canonical-text
  normalization: NFC, line-ending normalization, and whitespace normalization. They are not a
  gold arithmetic error.

References:
- `docs/implementation/it-books/p1-structural-contract.md:39`
- `docs/implementation/it-books/p1-structural-contract.md:82`
- `docs/implementation/it-books/p1-structural-contract.md:96`
- `java/synflux/src/test/java/org/synanton/synflux/document/SyntheticFixtureValidationTest.java`

### Boundary semantics

- **PROPOSED:** a section chunk represents the canonical-text interval from the heading start to
  the start of the next heading at any level, trimmed only by the existing canonical-text
  normalization.
- **PROPOSED:** body-only ranges are not used for benchmark section chunks; heading inclusion is
  part of the boundary contract. A separate body-range field, if needed later, is additive.
- **REQUIRES APPROVAL:** if a consumer needs body-only offsets, define a distinct field/semantic
  rather than overloading `documentOffsetStart/End`.

### Discontiguous and missing evidence

- **PROPOSED:** emit one contiguous half-open range only when the complete chunk is contiguous in
  canonical text.
- **PROPOSED:** never manufacture a range from `min(start)`/`max(end)` across discontiguous
  elements. Record `-1/-1` plus the contributing element IDs and an explicit reason such as
  `DISCONTIGUOUS_SOURCE_EVIDENCE`.
- **PROPOSED:** missing, approximate, invalid, or unsupported evidence remains explicitly
  unmapped; it must not be silently upgraded to exact.
- **REQUIRES APPROVAL:** the current `SemanticChunk` shape has only one document range. If
  discontiguous evidence must be persisted losslessly, an additive evidence-list field or a
  separate projection is required.

### Producer gap for gold conformance

- **SUPPORTED BY EXISTING CONTRACT:** SCEP currently supplies original-source locations only;
  run-v2 had 0 canonical ranges.
- **SUPPORTED BY EXISTING CONTRACT:** P1/gold canonical text includes the Markdown metadata
  preamble and comments; SCEP Markdown extraction explicitly skips metadata lines and HTML
  comments.
- **REQUIRES APPROVAL:** exact gold `book` container conformance needs either:
  1. SCEP to expose canonical-text identity/ranges, including metadata preamble spans; or
  2. an approved benchmark-contract change defining that metadata is outside chunk text and a
     new gold version.

The chunker must not map original-source spans to canonical offsets by reparsing or searching
text.

References:
- `content_extractor/java/adapter-document-text/src/main/java/org/synanton/extraction/adapter/document/text/TextModalityAdapter.java:149-172`
- `java/synflux/src/main/java/org/synanton/synflux/document/adapter/MarkdownDocumentAdapter.java:44-49`
- `java/synflux/src/main/java/org/synanton/synflux/document/adapter/MarkdownDocumentAdapter.java:100-120`

## 5. Chunk text policy

### Decisions

- **SUPPORTED BY EXISTING CONTRACT:** production retrieval currently uses a section-path
  breadcrumb and heading prefix by default.
- **PROPOSED:** add a named benchmark-conformant text policy, without changing global retrieval
  defaults:
  - include the heading once;
  - do not prepend the section-path breadcrumb;
  - preserve element order;
  - preserve canonical whitespace rules;
  - apply the same heading policy to batch, list, table, and figure chunks.
- **PROPOSED:** the policy belongs in the existing chunking configuration/extension surface, not
  in a benchmark-only fork or a second chunker. The exact representation can be a named policy
  object or explicit `ChunkerConfig` construction, but production default construction must remain
  unchanged.
- **REQUIRES APPROVAL:** whether canonical metadata preamble text belongs in the benchmark `book`
  chunk. This is independent of whether metadata labels may become mentions.

### Run-v2 mapping

Current `ChunkerConfig.of(...)` sets `includeSectionPath=true` and
`includeHeadingInContent=true`. The batch path prepends the heading, then `prefixSectionPath()`
prepends the breadcrumb, producing both duplication symptoms.

References:
- `java/synflux/src/main/java/org/synanton/synflux/domain/ChunkerConfig.java:15-20`
- `java/synflux/src/main/java/org/synanton/synflux/chunking/SemanticChunker.java:53-55`
- `java/synflux/src/main/java/org/synanton/synflux/chunking/SemanticChunker.java:418-426`

## 6. Gold contract disposition

- **SUPPORTED BY EXISTING CONTRACT:** gold offsets are P1 canonical-text offsets, not source-byte
  offsets; the existing validation test proves this.
- **REQUIRES APPROVAL:** no gold correction is proposed now. The unresolved question is whether
  the benchmark expects metadata preamble content inside the `book` chunk, given SCEP currently
  excludes it.
- **PROPOSED:** keep run-v2 immutable and classify it PARTIAL. Any approved contract or gold change
  must be a separately reviewed versioned change.

## 7. Regression test matrix

| Area | Input | Expected | Failure condition |
|---|---|---|---|
| Hierarchy | Heading-only parent with child headings | Exactly one `HEADING` container chunk; heading provenance retained | No chunk, wrong `chunk_type`, or duplicate container chunk |
| Empty sections | Empty leaf and empty structural parent | Empty parent follows container rule; empty leaf follows explicit approved rule | Silent drop without diagnostic, or invented body |
| Provenance | Heading + body elements | Heading and body IDs retained; order preserved | Heading ID absent or body ID lost |
| Tables | TABLE with child rows/cells | Exactly one atomic table chunk | Extra row/cell subsection chunk |
| Table isolation | Two adjacent independent tables with explicit child IDs | Two table chunks; neither consumes the other | Second table missing or merged |
| Offsets | Valid contiguous canonical span | Correct half-open UTF-16 range | `-1`, wrong coordinate space, or non-half-open |
| Missing evidence | Missing/invalid range | Explicitly unmapped, no fabricated range | Silent contiguous offset |
| Multiple spans | Discontiguous contributing elements | No synthesized min/max range; explicit discontiguous outcome | Single invented contiguous range |
| Text policy | Benchmark profile | Heading once, no breadcrumb duplication | Repeated heading or breadcrumb |
| Retrieval compatibility | Existing production defaults | Byte-identical chunk text behavior | Default config changes |
| Fixture | Frozen IT Books fixture | Contractually defined 14-section-path result after approved contract | Any frozen input/gold mutation |

## 8. Compatibility and migration

- No persistence schema change is required for the proposed container/table/text-policy work.
- Offset semantics require SCEP canonical evidence or an approved contract change; do not backfill
  by reparsing.
- Benchmark policy must be opt-in and must not change retrieval defaults.
- Existing `-1` offsets remain the explicit representation for unavailable ranges until the
  canonical-evidence decision is approved.
- `extraction_schema_digest=""` is an independent metadata follow-up in `content_extractor`; it
  must not be bundled into the chunker work.

## 9. Open questions requiring approval

1. Does the benchmark `book` chunk include the metadata preamble, and if so, which component
   supplies canonical ranges for it?
2. Should every heading emit a chunk, or only content sections and structural containers? How is an
   empty leaf represented?
3. Is a single contiguous `documentOffsetStart/End` sufficient, or must discontiguous evidence be
   persisted losslessly?
4. Should a section chunk include the heading in its canonical range (recommended) or expose a
   separate body-only range?
5. Should the benchmark text policy be a named production policy, a benchmark-only configuration,
   or a new config field?
6. Who owns the SCEP `schema_digest` population and the raw-payload evidence improvement?

**Stop point:** specification complete; no implementation or benchmark change is authorized.
