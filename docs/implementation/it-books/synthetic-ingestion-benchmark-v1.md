# Synthetic Ingestion Benchmark v1 — proposal

**Status:** Proposed; no fixture or implementation committed by this document
**Scope:** IT Books ingestion path
**Relationship:** Separate from A2, A4/A4.1, A8/A8.1 and A9.2; does not replace or adjust their baselines.

## 1. Purpose

Create a small, fully controlled technical-book document and an independent gold contract that
exercise every ingestion stage:

`source → parse → chunk → mentions → typing → canonical resolution → relations → ontology validation → evidence → graph projection → retrieval`

The synthetic fixture tests structural and semantic behavior without relying on real-book
ambiguity or A2 annotations. It is not an extraction instruction set.

## 2. Three independent artifacts

| Artifact | Content | Rule |
|---|---|---|
| Source | Markdown book with headings, paragraphs, definitions, acronyms, lists, code, tables, metadata and deliberate noise | Consumed by the pipeline |
| Gold contract | Expected elements, chunks, mentions, types, canonical IDs, relations, validation, evidence, graph assertions, retrieval queries | Never supplied to the extractor |
| Actual results | Pipeline output for the source | Compared against gold independently |

The gold contract must have its own version, digest and checksums. It must not import A2 judgments
or derive extraction rules from A2.

## 3. Initial fixture size

- 1 book, 1 author.
- 2 chapters, 4 sections.
- 8–12 concepts.
- 5–8 explicit relations.
- 2 definitions.
- 2 acronym/expansion pairs.
- Metadata/licence comments, version strings, source paths and deliberate negative cases.

Suggested title: `Distributed Systems: A Practical Introduction`.
Suggested author: `Jane Example`.

The fixture should be CC0/synthetic; no copyrighted content.

## 4. Stage contract

| Stage | Gold expectations |
|---|---|
| Source ingestion | Document identity, format, checksum, licence metadata |
| Parsing | Elements, element types, reading order, original offsets, parent-child structure |
| Chunking | Exact boundaries where required, acceptable variations, overlap, section hierarchy |
| Mention extraction | Surface, exact span, expected type, source/element identity |
| Entity typing | Expected Book, Author, Chapter, Section, Concept, Definition, Algorithm |
| Canonical resolution | Independent catalog IDs and explicit unresolved/ambiguous outcomes |
| Relation extraction | Predicate, subject/object mentions, evidence span, omission reason |
| Ontology validation | ACCEPTED / REJECTED / UNSUPPORTED and reason |
| Evidence binding | Exact, partial, document-scope and unavailable cases |
| Graph projection | Expected nodes/edges, provenance, no production write |
| Retrieval | Fixed queries and expected objects/provenance |

## 5. Chunk contract

The chunk manifest must distinguish:

- **Exact boundary**: required parent/section boundary.
- **Acceptable variation**: whitespace or overlap tolerated by the contract.
- **Semantic content**: concepts expected in the chunk, independent of exact boundary.
- **Parent/child relationships**: book → chapter → section.
- **Cross-chunk references**: explicitly listed, not inferred from a single implementation.

Example shape:

```json
{
  "fixtureVersion": "synthetic-it-books-v1",
  "documentId": "synthetic-distributed-systems-v1",
  "chunks": [
    {
      "chunkId": "chapter-1",
      "parentId": "book",
      "section": "Chapter 1: Fundamental Concepts",
      "requiredConcepts": ["Distributed Systems", "Node", "Message Passing"],
      "boundaryPolicy": "EXACT_SECTION",
      "acceptableOverlap": "NONE"
    }
  ]
}
```

## 6. Deliberate negative cases

| Input | Expected behavior |
|---|---|
| `<!-- SPDX-License-Identifier: MIT -->` | `METADATA_COMMENT`; suppress |
| `Version: 1.0` | Suppress as metadata |
| `Chapter 1: Fundamental Concepts` | Extract complete heading |
| `Author: Jane Example` | Extract `Author` |
| `Raft is a consensus algorithm` | Emit only if an A1 predicate and textual evidence support it |
| `The system is very useful` | Do not invent a relation |
| `RAFT` in metadata | Do not classify as a concept from uppercase alone |
| Unclassified HTML comment | `UNKNOWN_COMMENT`; route to review |

## 7. Expected extraction examples

For the proposed source, the gold contract should explicitly list:

- complete book title heading;
- author;
- chapter and section headings;
- definitions and their terms;
- acronym/expansion pairs only where explicit evidence exists;
- relations such as `HAS_CHAPTER`, `HAS_SECTION`, `DEFINES`, `DEPENDS_ON`, `USES` only when
  supported by source text and A1 predicates;
- negative assertions that must not become accepted edges.

The gold must not contain canonical IDs borrowed from A2.

## 8. Evaluation outputs

- mention precision/recall/F1 and exact/partial span counts;
- type confusion matrix;
- canonical resolution coverage, precision, recall and outcomes;
- relation precision, ontology-valid/unsupported counts and omission reasons;
- evidence binding status by space;
- graph projection assertions and provenance;
- retrieval query results;
- suppression counts by rule;
- reproducibility hashes and runner metadata.

Each metric must report its denominator; judged and unjudged items remain distinct.

## 9. Implementation sequence

1. Publish the synthetic source and gold contract proposal.
2. Define the chunk/span contract and a fixture validator.
3. Add a controlled runner that evaluates each stage independently.
4. Add a graph projection test with an in-memory client only.
5. Add retrieval assertions.
6. Add a report and checksums.
7. Expand only after v1 is stable: nested headings, cross-chunk references, ambiguity, multiple books.

## 10. Boundaries

- Do not replace A2 or change A4/A4.1.
- Do not use A2 gold as extraction rules.
- No production graph writes, ontology changes, CSO context, or semantic rerun.
- Do not merge synthetic results into the official A4 baseline.
- This proposal does not alter A9.2 scope or authorize its implementation.

## 11. First ticket

Create `Synthetic Ingestion Benchmark v1` with deliverables:

- source fixture;
- gold contract (chunks, entities, types, canonical IDs, relations, evidence, graph/retrieval);
- independent validator/runner;
- report and checksums;
- review PR, no auto-merge.
