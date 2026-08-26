---
title: "Semantic Content Structuring / Chunking - Implementation Plan"
status: "in progress"
last_reviewed: "2026-08-26"
---

# Semantic Content Structuring / Chunking - Implementation Plan

**Purpose:** Implementation plan for the v1.22 semantic chunking layer. Transforms normalized structured extraction output into task-optimized, semantically coherent chunks with full provenance-without re-running extraction or coupling chunking logic to the extraction plane.
**Architecture reference:** `docs/architecture/synanton-design-1.22.md` (Part X summary), `docs/proposals/v1.22/Synanton v1.22  Structured Content Semantic Chunking Design Proposal.md` (full design)
**Prerequisite:** v1.21 Structured Content Extraction Plane - see [`../content-extraction-plane/INDEX.md`](../content-extraction-plane/INDEX.md)
**Target repository:** `synanton/platform` (`synflux` pipeline, chunk persistence, synquest indexing)
**Audience:** Architects, ingestion engineers, search engineers
**Last Updated:** 2026-08-26

---

## Theme

> Structured extraction is the canonical input to semantic chunking. The chunking layer answers *how* structure should be represented for a downstream task; the extraction plane answers *what* structure was observed. The same `DocumentPayload` can produce different chunk sets for embedding, summarization, or citation without re-extraction.

---

## User-Facing Capability Unlocked

- Ingested documents produce chunks that respect heading hierarchy, tables, lists, and figures-not arbitrary token windows.
- Search hits carry `section_path`, `page_start`/`page_end`, and `chunk_type` for precise citation and UI highlighting.
- Tables remain atomic retrieval units with structured content preserved for embedding and display.
- Oversized sections split at paragraph/list boundaries before falling back to token-based splitting.
- Summarization and embedding can consume the same structured tree through different projections (future).

---

## Non-Negotiable Invariants

Derived from the v1.22 proposal §2, §3, and §8.2.

1. **Structure before chunking.** Chunk boundaries use normalized `elements`, not `flattenedText`, as the primary input.
2. **Extraction ≠ chunking.** Chunking logic MUST NOT live inside the extraction plane or `content_extractor`.
3. **Tables are atomic.** A table MUST NOT span multiple chunks.
4. **Provenance on every chunk.** `sourceElements`, `sectionPath`, and page coordinates MUST be present where extraction provided them.
5. **Token limits are secondary.** Semantic boundaries take precedence; token/size splitting is fallback only.
6. **No silent flattening.** Generated LLM/VLM descriptions remain typed and provenance-tagged; chunking does not treat them as source text.

---

## Phased Delivery

| Phase | Name | Owner | Status |
|-------|------|-------|--------|
| SC-1 | Structure builder (elements → section tree) | Platform / synflux | 🔶 PoC |
| SC-2 | Semantic chunker (boundaries + constraints) | Platform / synflux | 🔶 PoC |
| SC-3 | Table and figure chunk types | Platform / synflux | Planned |
| SC-4 | Persist + index provenance fields | Platform / synflux + synquest | 🔶 PoC |
| SC-5 | Summarization hierarchy from chunk tree | Platform / synflux | Post-v1.22 |
| SC-6 | Multimodal chunking (audio, image, video) | Platform / synflux | Post-v1.22 |

---

## Phase SC-1 - Structure Builder

**Goal:** Convert a flat `elements[]` array from `DocumentPayload` into a hierarchical section tree with `sectionPath`, heading levels, and source element linkage.

### Definition of Done

1. Headings at levels 1–6 nest correctly into a `SectionNode` tree.
2. Paragraphs, lists, and tables attach to the active section by reading order.
3. Each node records `sourceElements`, `pageStart`, and `pageEnd`.
4. Unit tests cover nested headings, orphan paragraphs before first heading, and empty documents.

---

## Phase SC-2 - Semantic Chunker

**Goal:** Produce bounded semantic chunks from the section tree using configurable constraints.

### Definition of Done

1. Sections under `maxTokensPerChunk` emit a single chunk with full `sectionPath`.
2. Oversized sections split at paragraph/list boundaries; token split is last resort.
3. Chunk `type` reflects origin: `section`, `subsection`, `paragraph`, `list`, `table`, `figure`, `fallback`.
4. `includeSectionPath` and `includeHeadingInContent` behave per `ChunkerConfig`.

---

## Phase SC-3 - Table and Figure Chunk Types

**Goal:** First-class table and figure chunks with structured content and embedding-friendly text projection.

### Definition of Done

1. Tables never split across chunks; `structuredContent.table` carries headers and rows.
2. Figure/image chunks retain caption and optional generated description with provenance metadata.
3. Embedding representation includes `sectionPath` prefix for contextual retrieval.

---

## Phase SC-4 - Persist and Index

**Goal:** Chunks with provenance land in Cassandra and synquest with citation-ready fields.

### Definition of Done

1. Chunk records store `section_path`, `page_start`, `page_end`, `chunk_type`, and `heading`.
2. BM25 index includes citation fields on search hits.
3. `./scripts/run-extract-index-poc.sh` demonstrates structure-aware chunks end to end.

---

## Configuration Keys (planned)

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `synflux.chunking.strategy` | `SYNFLUX_CHUNKING_STRATEGY` | `structure-aware` | `structure-aware` or `flat-text` |
| `synflux.chunking.max-tokens` | `SYNFLUX_CHUNKING_MAX_TOKENS` | `512` | Primary token budget per chunk |
| `synflux.chunking.keep-table-atomic` | `SYNFLUX_CHUNKING_TABLE_ATOMIC` | `true` | Tables as single chunks |
| `synflux.chunking.include-section-path` | `SYNFLUX_CHUNKING_INCLUDE_SECTION_PATH` | `true` | Prefix embedding text with hierarchy |

---

## References

1. `docs/proposals/v1.22/Synanton v1.22  Structured Content Semantic Chunking Design Proposal.md` - full v1.22 design
2. `docs/architecture/synanton-design-1.22.md` - Part X summary
3. `docs/implementation/content-extraction-plane/INDEX.md` - upstream extraction plane plan
4. `docs/architecture/synanton-design-1.21.md` - Part IX extraction summary

---

## How to Contribute

Plan changes land here first. A phase is not done until its numbered Definition of Done is fully satisfied. Chunking changes that affect persisted chunk shape require a migration note in the same change set.
