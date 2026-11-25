# P1 exit conformance report

**Parent:** #903 (reopened for formal exit evidence) · **Structural slice:** #904 / `1b1a90e7`
**Extraction integration:** #905 / `15c88641`

This report records the formal P1 exit run on the synthetic, CC0-licensed IT Books fixture. It is
**not** the independent A2 annotated evaluation fixture and makes no quality/entity judgments.

## Pinned fixture and command

- Fixture: `java/synflux/src/test/resources/fixtures/p1-cc0-it-books.md`
  - SHA-256: `10f4e5e0515ca8591b06b1d52dbcaf5405a24a0e0cb576b3058e5159a6aba2f6`
- Expected spans: `java/synflux/src/test/resources/fixtures/p1-cc0-conformance.json`
  - SHA-256: `649409d7d13c5c65c1c86f43ac9392c7cb00ee992efe66d46a9d215f1259bd07`
- Structural artifact digest (`structural-artifact-v1`):
  `f6137a158d6a4f5fd45bca4b75033e675435fe6bd00098f478d6cada6cb63147`
- Clean-state command:

```bash
./scripts/it-books/p1-exit-check.sh
```

The command is also directly runnable as:
`./gradlew :java:synflux:test --tests 'org.synanton.synflux.document.P1ExitConformanceTest'`

**Framing:** 4/4 resolved spans is a contract smoke test, not a coverage or quality claim.
Coverage authority remains the independent A2 fixture.

## Results

| Exit output | Result |
|---|---|
| Evidence spans resolved | 4 / 4 (100%; smoke test only) |
| Element/chunk identity stable across two runs | Yes |
| UTF-16 offsets index canonical text | 100% of elements/chunks |
| Parser provenance present per element | 100% |
| Structural artifact digest stable | Yes: `f6137a15...b63147` |
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

## Durable P3/P4 hand-off outputs

The four required outputs are recorded here and in the linked tests/knowledge artifacts:

1. Span conformance rate: 4/4 on the pinned CC0 smoke fixture; A2 remains coverage authority.
2. Geometry coverage: PDF `AVAILABLE`; Markdown `NOT_APPLICABLE`.
3. Parser/extraction provenance completeness: 100% of fixture elements and extraction metadata.
4. Adapter limits: PDF line-level elements/page chunks; PDF tables/figures deferred; Markdown
   heading-section chunks with no geometry.

P3a/P3b/P4 scope is derived in `docs/architecture/p3a-evidence-contract.md`.

## Scope boundaries

- A2 remains independent and is required for P3b/P4 evaluation.
- PDF table/figure structure remains deferred under R0.1.
- R0.3 bbox float canonicalization is still required before P3 persists bbox evidence.
- No P2/P3/P4 implementation, extraction execution, UI, Docling dependency, or
  F1/gold/gate/profile/ADR-044 change.
