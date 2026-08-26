# Synanton Platform - Architecture (v1.22 current)

> **Document type:** Current engineering reference (pointer + semantic chunking addendum)
> **Version:** 1.22
> **Date:** 2026-08-26
> **Status:** Current
> **Audience:** Architects, module owners, SREs, security engineers

This is the **current** architecture pointer for the Synanton platform.

| Layer | Where to read it |
|---|---|
| Baseline (helper/wizard, query/ingest core, DR, security) | [`synanton-design-1.19.md`](./synanton-design-1.19.md) - **superseded as “the” current doc**, still the merged baseline for Parts I–VII |
| GPU Execution Plane isolation (Part VIII, §50–§64) | [`synanton-design-1.20.md`](./synanton-design-1.20.md) |
| Structured Content Extraction Plane (Part IX) | [`synanton-design-1.21.md`](./synanton-design-1.21.md) + [`../proposals/v1.21/`](../proposals/v1.21/) |
| Semantic Content Structuring / Chunking (Part X) | This document + [`../proposals/v1.22/`](../proposals/v1.22/) |

Do not treat 1.19 as the live system description. GPU isolation, the extraction contract, and structure-aware chunking are in force.

---

## What's new in v1.22

v1.22 adds a **Semantic Content Structuring / Chunking** layer that operates on the normalized structured representation from the extraction plane-not on `flattenedText`. The same extracted document can be chunked differently for vector search, RAG, summarization, or entity extraction without re-running extraction.

| # | Change | Home |
|---|---|---|
| 1 | Chunking operates on structured `elements`, not flat text | Part X below; `SemanticChunkStage` |
| 2 | Structure builder converts flat elements → hierarchical section tree | Part X; `synflux` |
| 3 | Semantic boundaries first; token/size limits as fallback only | Part X chunker config |
| 4 | Tables are atomic first-class chunks with structured row/column content | Part X; chunk `type=table` |
| 5 | Every chunk carries `sectionPath`, `sourceElements`, `pageStart`/`pageEnd` | Part X; synquest citation fields |
| 6 | Chunking layer is separate from the extraction plane (architectural invariant) | Part X §8.2 |

v1.21 extraction contract remains in force. `synanton.extraction.v1` is mirrored byte-for-byte between `platform` and `content_extractor` (`scripts/verify-contract-mirror.sh`). v1.20 GPU isolation unchanged: `synanton.gpu.v1` mirrored with `gpu-runtime` (`scripts/verify-gpu-contract-mirror.sh`).

---

## Part X - Semantic Content Structuring / Chunking (summary)

**Invariant:** structured extraction is the canonical input to semantic chunking. Chunk boundaries SHOULD follow document semantics-section hierarchy, lists, tables, figures-while token/size limits provide a secondary constraint and fallback. `flattenedText` MUST NOT be the only input available to the chunking stage. The chunking logic MUST NOT reside inside the extraction plane.

```text
Object store (raw bytes)
        │
        ▼
synanton.extraction.v1  (ExtractSync / async later)
        │
        ▼
DocumentPayload (elements, headings, tables, page boxes)
        │
        ▼
Structure Builder → Section Tree
        │
        ▼
Semantic Chunker → chunks (sectionPath, sourceElements, page/bbox)
        │
        ├───────────────────────┬───────────────────────┐
        ▼                       ▼                       ▼
 Embedding chunks      Summarization context    Search metadata
        │
        ▼
 persist → synquest (BM25 + optional HNSW)
```

**Separation of concerns:**

| Layer | Responsibility |
|---|---|
| Extraction Plane (v1.21) | “What is in this document and what is its structure?” |
| Chunking Layer (v1.22) | “How should this structure be represented for a particular downstream task?” |
| Knowledge Processing | “What does this mean in the Synanton domain?” |

**Core chunking principles:**

1. **Structured input only** - chunk boundary decisions use the normalized `elements` collection, not `flattenedText`.
2. **Semantic boundaries first** - sections, subsections, lists, tables, and figures define boundaries; pure token splitting is a final fallback.
3. **Hierarchical, not one-heading-per-chunk** - large coherent sections split at paragraph/list boundaries when they exceed the token budget.
4. **First-class tables** - tables MUST NOT be split arbitrarily; they are atomic chunks with structured content and an embedding-friendly projection.
5. **`sectionPath` on every chunk** - heading hierarchy (e.g. `["3. GPU Execution Plane", "3.1 GPU Gateway"]`) travels with each chunk for retrieval and citation.
6. **Provenance preservation** - `sourceElements`, `pageStart`, and `pageEnd` link every chunk back to extraction evidence.

**In scope for the current PoC:** structure-aware document chunking in `synflux` (`SemanticChunkStage`), heading hierarchy via `section_path`, atomic table chunks, provenance fields on persisted chunks, BM25 index with citation metadata.

**Out of scope for the PoC (still planned):** multimodal chunking (audio turn-based, image OCR/description, video scene/clip), chunking tags on the extraction request, summarization hierarchy built from chunk tree, dedicated `semantic-chunking` service boundary.

Full design text: [`../proposals/v1.22/Synanton v1.22  Structured Content Semantic Chunking Design Proposal.md`](../proposals/v1.22/Synanton%20v1.22%20%20Structured%20Content%20Semantic%20Chunking%20Design%20Proposal.md).  
Implementation plan: [`../implementation/semantic-chunking/INDEX.md`](../implementation/semantic-chunking/INDEX.md).

---

## Compatibility

- Rolling from 1.21: extraction client/URL unset keeps Tika-only ingest; when extraction succeeds, chunking uses structure when elements are present, flat-text fallback otherwise.
- Rolling from 1.20: GPU client remains off until `gateway.gpu.enabled=true` **and** a gpu-runtime that serves the mirrored `synanton.gpu.v1`.
- Rolling from 1.19: Relix graph backends are selected with `relix.graph.connector` (`memory` | `neo4j` | `nebula`); this is an adapter swap, not a design-version break.

---

## How “current” is managed

`docs/VERSION` is `1.22`. [`INDEX.md`](./INDEX.md) names this file as authoritative. 1.19, 1.20, and 1.21 remain in this directory as lineage, not as the live pointer.
