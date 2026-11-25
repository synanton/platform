# IT Books P1 — Structural document contract

**Parent:** #903 · **Accepted architecture:** #900 / `0098f84b`

This is the first implementation slice of the R0.2 structural contract. It does not implement the P0 compiler, extraction prompt compilation, canonical entities, or promotion.

## Boundary

```text
bytes + source identity
        ↓
DocumentStructureService        selects by media type
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
- The service is not yet wired into the existing extraction stage. Wiring and a rights-cleared fixture run remain P1 work.
- A2 remains the independent evidence authority for P3/P4; this code does not derive fixtures from extraction output.

## Verification

`./gradlew :java:synflux:test --tests 'org.synanton.synflux.document.*'`

Tests cover Markdown structure/offsets, deterministic IDs, generated-PDF pages/bboxes/offsets, media-type normalization, and unsupported formats.

## Extraction-stage integration

`ExtractionStage` now parses supported PDF/Markdown through `DocumentStructureService` and attaches
the parser-neutral `DocumentStructure` to `ParsedDocument`. Unsupported media types are explicit:
`structural_status=UNSUPPORTED`; no synthetic structure is invented. Supported-format structural
failure is fail-closed.

The extraction record carries:

- structural contract version (`R0.2`)
- source/document/version identity
- parser name/version and normalization version
- chunking configuration (`pdf-page-v1` or `markdown-heading-v1`)
- element/chunk counts
- canonical structural text SHA-256 and extraction text SHA-256
- `text_projection=EXTRACTION_TEXT` when downstream semantic chunking operates on extraction text

The hashes make the text projection explicit. Existing semantic chunk IDs are **not** structural
chunk IDs. P3 evidence binding must use `StructuralEvidenceResolver` and the structural artifact,
not assume semantic chunks are structural chunks. A future chunk-mapping layer can replace this
projection record without changing R0.2.

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
