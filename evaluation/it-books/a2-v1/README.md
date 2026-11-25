# IT Books A2 annotated fixture — v1

**Status:** draft for review. This is a new A2 artifact; it is not accepted and does not start P3b/P4.
**Fixture version:** `it-books-a2-v1`
**Coordinate contract:** P1 NFC/UTF-16 structural text; P3a spaces and geometry states.

## Sources

| Source | Original | License | Restrictions |
|---|---|---|---|
| Think Python 2e, v2.4.1 | `samples/it-books/books/think-python-2e.pdf` | CC BY-NC 3.0 | Attribution; non-commercial |
| Computer Networks, 6e v6.1 | `samples/it-books/books/computer-networks-systems-approach-6.1.pdf` | CC BY 4.0 | Attribution |

Only two short derived excerpts are committed under `sources/`. They carry SPDX identifiers and
attribution. Original PDFs remain under `samples/it-books/`; `manifest.json` pins their SHA-256,
edition, source URL, license URL and rights basis. Pro Git is not used.

## Contents

- `manifest.json` — source and compiler/evidence pins, license terms, coordinate contract.
- `annotations.json` — 22 entity mentions and 17 relation judgments.
- `sources/` — short attributed excerpts, not full books.
- `build_annotations.py` — deterministic artifact generator.
- `SHA256SUMS` — manifest/annotations/excerpt checksums.
- `A2FixtureValidationTest` in `java/synflux` — machine-readable validation.

## Annotation coverage

Extraction classes used: `Book`, `Author`, `Chapter`, `Section`, `Algorithm`, `Concept`, `Topic`,
`Definition`. All eight A1 predicates have at least one accepted and one rejected example:

| Predicate | Accepted | Rejected |
|---|---|---|
| AUTHORED_BY | Book → Author | Author → Book |
| HAS_CHAPTER | Book → Chapter | Chapter → Book |
| HAS_SECTION | Chapter → Section | Book → Section |
| COVERS_TOPIC | Book → Topic | Topic → Book |
| DEFINES | Section → Concept | Book → Concept |
| USES | Chapter → Algorithm | Author → Algorithm |
| DEPENDS_ON | RRP → HHP | Author → Concept |
| PART_OF | Section → Chapter | Book → Chapter |

One deliberately `UNJUDGED` relation is included. `judgedNegative` is true only for `REJECTED`;
unjudged cases are neither accepted nor judged negative.

## Evidence and geometry

Mentions carry source ID, structural element/chunk IDs, UTF-16 document/chunk offsets, source book
page, and P1 geometry state. Because the fixture is a Markdown excerpt, structural page numbers and
bboxes are explicitly `null` / `NOT_APPLICABLE`; source book page numbers are retained as
`sourcePage`. Markdown evidence is mappable in offset space; only geometry is unavailable.

## Validate

```bash
./gradlew :java:synflux:test --tests 'org.synanton.synflux.document.evidence.A2FixtureValidationTest'
python3 -m unittest discover -s evaluation/it-books/a2-v1 -p 'test_*.py'
```

The test verifies source hashes, excerpt hashes, license pins, element/chunk IDs, UTF-16 offsets,
surface text, geometry states, predicate coverage, accepted/rejected semantics, judged-negative vs
unjudged, and compiler/evidence pins.

Regenerate:

```bash
python3 evaluation/it-books/a2-v1/build_annotations.py
cd evaluation/it-books/a2-v1 && sha256sum -c SHA256SUMS
```

## Boundaries

- New A2 artifact only; no P3b/P4 evaluation.
- No extraction output was used to derive annotations.
- No canonical identity assignment, graph promotion, P2, Docling, or F1/gold/gate/profile/ADR-044 change.
- Non-commercial restrictions on Think Python remain; do not treat it as equivalent to CC BY 4.0.
