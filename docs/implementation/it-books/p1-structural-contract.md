# IT Books P1 — Structural document contract

**Parent:** #903 · **Accepted architecture:** #900 / `0098f84b`

This is the first implementation slice of the R0.2 structural contract. It does not implement the P0 compiler, extraction prompt compilation, canonical entities, or promotion.

## Boundary

```text
bytes + source identity
        ↓
DocumentStructureService        local conformance/evaluation only
        ↓
DocumentFormatAdapter          PDFBox | native Markdown
        ↓
DocumentStructure              parser-neutral pages/elements/chunks
```

Public implementation: `org.synanton.synflux.document`.

## Library decision

| Candidate | Decision | Reason |
|---|---|---|
| Apache PDFBox 2.0.31 | Use for PDF structure | Already on the runtime classpath via Tika; exposes text positions for line-level bboxes and character offsets. Now pinned explicitly. |
| Native Markdown adapter | Use for Markdown | No parser dependency needed; headings, paragraphs, lists, tables and fenced code provide the required structure. |
| Apache Tika 2.9.2 | Keep for existing extraction/format breadth | Useful for text/metadata, but does not provide the structural coordinates this boundary requires. |
| OpenDataLoader PDF | Keep as an evaluation candidate | Existing content-extraction path; not selected for the structural boundary until it is compared on the PDF fixture. |

No OCR, advanced table reconstruction, figure extraction, or Docling runtime is introduced. PDF elements are extracted lines; PDF chunks are pages. Markdown chunks are heading sections.

## Identity and offsets

- `sourceId`, `sourceVersionId`, `documentId`, and media type are caller-supplied.
- Element and chunk IDs are deterministic UUIDs derived from `documentId`, kind, and ordinal.
- All offsets are UTF-16 character offsets into `DocumentStructure.text()`.
- PDF text is NFC-normalized with whitespace collapsed: `NFC_COLLAPSE_WHITESPACE_UTF16_V1`.
- Markdown text is NFC-normalized: `NFC_UTF16_V1`.
- Element offsets are both document-relative and chunk-relative. Chunk offsets are document-relative.

## Geometry semantics

- PDF pages use `PDF_TEXT_ADJUSTED_TOP_LEFT` in `POINT`.
- PDF elements carry an `AVAILABLE` bounding box from PDFBox adjusted text coordinates.
- Markdown has no page/bbox geometry: `pageNumber` is null and geometry is `NOT_APPLICABLE`.
- A future adapter that cannot supply geometry must use `UNAVAILABLE`, not omit the bbox or downgrade to document scope.

## Provenance

Every element records `parserName` and `parserVersion`; the document also records the adapter-level provenance and normalization version. PDFBox version is read from `org.apache.pdfbox.util.Version`.

## Known P1 limits

- PDF heading detection, tables, figures and multi-column reading order are deferred; PDF output is line-level and page-chunked.
- Markdown page/bbox evidence is intentionally unavailable, not approximated.
- `DocumentStructureService` remains a local conformance/evaluation tool. It is not part of the production extraction path.
- A2 remains the independent evidence authority for P3/P4; this code does not derive fixtures from extraction output.

## Verification

`./gradlew :java:synflux:test --tests 'org.synanton.synflux.document.*'`

Tests cover Markdown structure/offsets, deterministic IDs, generated-PDF pages/bboxes/offsets, media-type normalization, and unsupported formats.

## Production extraction and local conformance

Production `ExtractionStage` now consumes the canonical SCEP `DocumentPayload`. It does **not**
invoke `DocumentStructureService`, construct a local parser, or attach local structural metadata
to `ParsedDocument`. `ParsedDocument.structure()` remains null on the production path.

The local structure service remains available for deterministic P1/A2/A4 conformance and evidence
evaluation. It is not a production parser fallback.

Production structural metadata is therefore absent from the existing record. Future consumers must
use the canonical extraction payload and its evidence fields; they must not assume the legacy
structural record is populated. The P1 structural artifact remains the evaluation reference used by
the local conformance tests.

## Conformance evidence

- Synthetic CC0 IT Books excerpt: `java/synflux/src/test/resources/fixtures/p1-cc0-it-books.md`.
- Two-run identity, canonical-text offsets, chunk spans, parser provenance and Markdown geometry
  are asserted by `P1FixtureConformanceTest`.
- Generated-PDF conformance asserts page geometry, available bboxes, offsets and source provenance.
- `StructuralEvidenceResolver` resolves document/chunk spans to exact canonical text, element IDs,
  page/bbox and provenance.

This is **not** the A2 annotated evaluation fixture and makes no quality/entity judgments. A2
remains independent and is still required for P3b/P4 acceptance.

## P1 → P3/P4 hand-off

- Geometry coverage is adapter-specific: PDF `AVAILABLE`; Markdown `NOT_APPLICABLE`. P4 must not
  promise bbox metrics for Markdown.
- Parser provenance is complete per element/document for the implemented adapters.
- Evidence spans are UTF-16 offsets into the canonical structural text, with chunk identity from
  `chunkId` and the pinned chunking configuration.
- PDF heading/table/figure structure is deferred; this is a scope boundary, not a P4 regression.
- R0.3 float canonicalization/digest rules must be finalized before P3 persists bbox evidence.
