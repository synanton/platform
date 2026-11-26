# Synthetic Ingestion Benchmark v1 — gold contract decisions

**Status:** Contract design; fixture source/gold implementation not included
**Ticket:** #936
**Schema:** `manifest.schema.json`
**Validator:** `tools/it-books-synthetic/validate_gold.py`

## 1. Fixture identity

The gold manifest must pin:

- fixture version and document ID;
- source path and SHA-256;
- media type and synthetic/CC0 provenance;
- fixture-local IDs prefixed `gold:`, never A2 `m-*` IDs or production canonical IDs;
- gold schema version.

## 2. Metadata policy

| Input | Classification | Extraction rule |
|---|---|---|
| `**Author:** Alex Morgan` | document metadata field plus Author value | Author value may be a mention; the label `Author` must not be a Concept |
| `**Edition:** 1` | document metadata | not a mention |
| `**Document ID:** ...` | document metadata | not a mention |
| `<!-- SPDX... -->` | `METADATA_COMMENT` | not a mention |
| `<!-- Source... -->` | `METADATA_COMMENT` | not a mention |
| `<!-- Version... -->` | `METADATA_COMMENT` | not a mention |
| Unclassified comment | `UNKNOWN_COMMENT` | review-required; not silently suppressed |

Metadata fields are listed explicitly in the gold `metadataPolicy`; emphasis syntax alone does not
make a label an entity.

## 3. Structural and chunk policy

- Every heading starts a required chunk boundary.
- Exact boundary: the chunk begins at the heading text start and ends before the next heading.
- Allowed variation: leading/trailing whitespace and blank lines only.
- No chunk may cross a heading boundary.
- No overlap is required; overlap is not permitted unless the gold chunk explicitly sets
  `allowedOverlap` to a non-`NONE` value with a reason.
- Parent/child hierarchy: book → chapter → section; appendices are structural sections unless a
  reviewer assigns another allowed type.
- Heading typing uses structure plus text cues:
  - title heading → `Book`;
  - `Chapter N` heading → `Chapter`;
  - numbered section heading → `Section`;
  - `Preface`/`Appendix` headings are structural and must not be forced into `Concept`.
- Chunk semantic `requiredConcepts` are independent of exact boundary tokenization.

## 4. A1 predicate mapping

Only the pinned A1 predicates are allowed. Decisions:

| Source relationship | A1 predicate | Decision |
|---|---|---|
| Book title → Alex Morgan | `AUTHORED_BY` | accepted; Book → Author |
| Book → Chapter 1/2 | `HAS_CHAPTER` | accepted |
| Chapter → numbered sections | `HAS_SECTION` | accepted |
| Definitions in sections → defined concepts | `DEFINES` | accepted only for explicit definition sections/terms |
| Raft → leader election | `USES` | accepted if Raft is typed `Algorithm` and leader election is an allowed USES object |
| Raft → log replication | `USES` | accepted if Raft is typed `Algorithm` and log replication is an allowed USES object |
| Raft → fault tolerance | `DEPENDS_ON` | omitted; no explicit dependency statement |
| Replication → fault tolerance | `DEPENDS_ON` | omitted; the source explicitly denies a guarantee |
| Section → Chapter | `PART_OF` | omitted to avoid duplicating `HAS_SECTION`; fixture tests structural predicates without redundant inference |
| Book → distributed systems | `COVERS_TOPIC` | omitted; “covers” is not explicit in the source |
| Code arrows / table rows | none | omitted; formatting is not a semantic predicate |
| Raft → consensus algorithm | none | omitted; there is no `IS_A` predicate in A1 |

A relation marked `REQUIRES_MAPPING_REVIEW` in the gold is not emitted and does not count as
accepted. The ontology is not changed to make the fixture pass.

## 5. Negative assertions

The gold must contain machine-readable negative assertions:

- no mention from SPDX/source/version comments;
- no `Author`/`Edition`/`Document ID` labels as domain concepts;
- no metadata `RAFT`/`RPC` mention;
- no relation from `raft is a consensus algorithm`;
- no relation from `replication alone does not guarantee fault tolerance`;
- no relations from the code arrows or table formatting;
- no generic capitalized fragments from prose.

## 6. Retrieval

Retrieval is **conditional**. The gold declares the queries and expected supporting chunks, but
the runner may mark retrieval `NOT_RUN` until the existing retrieval harness is confirmed. Do not
introduce a second retrieval stack.

## 7. Non-goals

No A2/A4/A4.1/A8/A8.1 changes, no production canonical IDs, no extraction heuristics encoded in
gold, no ontology changes, no production graph writes, no semantic rerun, no CSO.

## Validation

```bash
python3 tools/it-books-synthetic/validate_gold.py \
  --manifest evaluation/it-books/synthetic-ingestion-v1/gold/examples/valid.json
python3 -m unittest discover -s tools/it-books-synthetic -p 'test_*.py'
```

`manifest.schema.json` defines the JSON shape; `validate_gold.py` additionally enforces A1
predicate/domain/range semantics, gold-local IDs, chunk references, metadata exclusions, negative
assertions and retrieval references.
