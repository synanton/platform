# SCEP Chunking Implementation

**Status:** Implemented; run-v3 recorded; canonical offsets/preamble blocked upstream.  
**Specification:** #969  
**Approval record:** #970  
**Implementation branch:** `feat/scep-chunking-contract`

## Implemented

- New opt-in strategy: `semantic-benchmark`.
  - `ChunkerConfig.benchmarkV1()`.
  - `ChunkTextPolicy.BENCHMARK_V1`.
- Structural container chunks:
  - `chunk_type=HEADING` for heading-only parents with children.
  - Content sections remain `SECTION`/`SUBSECTION`.
  - Empty leaves emit no semantic chunk.
  - Heading IDs are retained in chunk provenance under the benchmark policy.
- Atomic tables:
  - TABLE descendants are resolved via `DocumentElement.child_ids`.
  - TABLE_ROW/TABLE_CELL elements are consumed and do not become duplicate chunks.
  - Table and descendant IDs are retained in provenance.
  - Adjacent tables remain independent.
- Benchmark text policy:
  - Heading included once.
  - No section-path breadcrumb.
  - Retrieval defaults remain unchanged.
- Canonical evidence consumption:
  - Reads `synanton.canonical-text` projection.
  - Validates projection format, lossless outcome, `sha256:<64 lowercase hex>` digest,
    `ct-v1:<canonical_text_digest>` identity, UTF-8 encoding, source document/digest/version,
    normalization profile/version, execution identity, UTF-16 unit, NFC/LF, and half-open bounds.
  - Requires every element to carry an identical canonical identity and fails closed otherwise.
  - Bridges only gaps whose code points have the Unicode 15.1.0 UAX #44 White_Space property and
    are not non-LF line endings. Canonical v1 keeps LF valid and rejects VT, FF, CR, NEL, LS, and PS.
  - Never derives a range from original-source spans or descendant min/max.
  - Emits explicit `CANONICAL_EVIDENCE_UNAVAILABLE`, `DISCONTIGUOUS_SOURCE_EVIDENCE`, or invalid-evidence status.
- Metadata preamble:
  - The document-root container range is `[0, nextTopLevelBoundary)`.
  - Its text is the canonical projection slice, so the preamble is included only when SCEP
    supplies it in canonical text. No preamble is reconstructed or injected.
- `SemanticChunk.withEvidence` preserves offsets and metadata.

## Tests

`SemanticChunkerBenchmarkTest` covers:

- container emission, empty leaves, heading provenance;
- table child consumption and adjacent-table isolation;
- benchmark text policy and retrieval compatibility;
- contiguous canonical offsets;
- missing and discontiguous evidence;
- table parent-range authority;
- canonical preamble slice for a container;
- document-root container starting at offset zero when the heading follows a preamble;
- `ct-v1` identity, digest, encoding, source, and normalization consistency, including fail-closed
  mixed/invalid identity cases;
- UAX #44 White_Space boundaries (`U+0085`, `U+00A0`, and excluded code points) and rejection of
  non-LF line endings (`U+000B`, `U+000C`, `U+000D`, `U+0085`, `U+2028`, `U+2029`).

Existing `SemanticChunkerTest` and `SemanticChunkStageTest` remain green. Full `:java:synflux:test` passed in 7m07s before the final defensive `keepTableAtomic` guard.

## Run-v3

Artifacts: `evaluation/it-books/synthetic-ingestion-v1/results/run-v3/`

- Operation: `640dad56-6e64-4b67-a2b1-8efbc7f2de77`
- Run: `76cc94f3-1eac-31bd-b756-16062f04ad8a`
- Source version: `6937e6eb-0f26-570d-aed0-76b37c91cde1`
- Strategy: `semantic-benchmark`
- Image tag: `run3-7fc0f5558be2`
- Generated chunks: 14 / 14 gold section paths
- Containers: book, Chapter 1, Chapter 2 emitted as `HEADING`
- Appendix A: single TABLE chunk; duplicate removed
- Text policy: no breadcrumb or repeated heading
- Offsets: all `-1`
- Book preamble: absent

## Upstream blockers

- `content_extractor` #33: canonical text projection/identity/UTF-16 ranges not yet implemented.
- `content_extractor` #34: metadata preamble canonical evidence not yet implemented.
- `content_extractor` #32: `schema_digest` remains empty; separate and non-blocking.

No original-source-to-canonical conversion, synthetic min/max range, persistence-schema change, or production graph write is included.

## A3 persistence limitation

`SemanticChunk.metadata` carries diagnostic status in-process; current `ChunkRow` has no metadata column, so persisted chunks retain `-1/-1` and source IDs but not the reason. Preserving that distinction downstream requires a separate approved additive contract.
