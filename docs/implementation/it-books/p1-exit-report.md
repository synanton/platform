# P1 exit conformance report

**Parent:** #903 (reopened for formal exit evidence) · **Structural slice:** #904 / `1b1a90e7`
**Extraction integration:** #905 / `15c88641`

This report records the formal P1 exit run on the synthetic, CC0-licensed IT Books fixture. It is
**not** the independent A2 annotated evaluation fixture and makes no quality/entity judgments.

## Fixture and command

- Fixture: `java/synflux/src/test/resources/fixtures/p1-cc0-it-books.md`
- Expected spans: `java/synflux/src/test/resources/fixtures/p1-cc0-conformance.json`
- Run: `./gradlew :java:synflux:test --tests 'org.synanton.synflux.document.P1ExitConformanceTest'`

## Results

| Exit output | Result |
|---|---|
| Evidence spans resolved | 4 / 4 (100%) |
| Element/chunk identity stable across two runs | Yes |
| UTF-16 offsets index canonical text | 100% of elements/chunks |
| Parser provenance present per element | 100% |
| Extraction boundary retains structure | Yes; parser/normalization/chunking metadata present |
| Markdown geometry | 100% `NOT_APPLICABLE`; no accidental null-as-available |
| PDF geometry (generated fixture) | 100% `AVAILABLE`; page + bbox + declared POINT/top-left coordinates |
| Unsupported formats | Explicit `structural_status=UNSUPPORTED` |

## Adapter characteristics

- **PDF:** line-level elements, page chunks, available bboxes; heading/table/figure structure is
  deferred and not promised.
- **Markdown:** heading-section chunks, headings/paragraphs/lists/tables/code; geometry is
  intentionally `NOT_APPLICABLE`.
- **Extraction text projection:** existing semantic chunks still operate on extraction text, not
  structural chunk IDs. The projection is recorded with text hashes and
  `text_projection=EXTRACTION_TEXT`; P3 must use the structural artifact/evidence resolver until a
  chunk-mapping layer exists.

## Scope boundaries

- A2 remains independent and is required for P3b/P4 evaluation.
- PDF table/figure structure remains deferred under R0.1.
- R0.3 bbox float canonicalization is still required before P3 persists bbox evidence.
- No P2/P3/P4 implementation, extraction execution, UI, Docling dependency, or
  F1/gold/gate/profile/ADR-044 change.
