# SCEP Chunking Contract Approval Record

**Status:** DRAFT — implementation not authorized  
**Specification baseline:** #969 / `154a4caf`  
**Run evidence:** frozen run-v2 under `evaluation/it-books/synthetic-ingestion-v1/results/run-v2/`

## Decisions already established

| ID | Decision | Status |
|---|---|---|
| D1 | Structural container chunks use `chunk_type=HEADING`; content sections use `SECTION`/`SUBSECTION`. | APPROVED in #969 |
| D2 | Empty leaf headings emit no semantic chunk; record diagnostics only. | APPROVED in #969 |
| D3 | Atomic tables consume TABLE_ROW/TABLE_CELL descendants via SCEP `child_ids`, never list ordering. | APPROVED in #969 |
| D4 | Benchmark text policy is opt-in; production retrieval defaults stay unchanged. | APPROVED in #969 |
| D5 | No persistence-schema change is required for D1–D4. | APPROVED in #969 |

## Open approval gates

| ID | Decision | Proposed resolution | Status |
|---|---|---|---|
| A1 | Canonical offset ownership | Offsets are UTF-16 into P1 canonical structural text; SCEP must supply canonical identity/ranges. The chunker consumes them and never reconstructs or substitutes original-source bytes. | PENDING |
| A2 | Metadata preamble in benchmark chunk | Include it only if the canonical-text contract includes it; SCEP must supply corresponding canonical evidence, or a separately reviewed gold version must define its exclusion. | PENDING |
| A3 | Discontiguous source evidence | Do not synthesize min/max ranges. Until a real consumer requires lossless multi-span evidence, emit explicit unmapped state plus contributing element IDs. | PENDING |
| A4 | `schema_digest` population | Track independently in `content_extractor`; not part of the chunking milestone. | PENDING / SEPARATE |

## Implementation gate

No chunker implementation may start until A1–A3 are approved. Once approved, the work is one coherent milestone:

1. heading/container emission;
2. atomic table consumption by hierarchy;
3. opt-in benchmark text policy;
4. canonical offset consumption only after SCEP evidence is available;
5. regression fixture rerun as a new immutable attempt.

The frozen fixture, gold, ontology, run-v2 artifacts, and production retrieval defaults must not change.

## Required approvals

| Gate | Owner | Decision |
|---|---|---|
| A1 | Platform + Content Extractor | pending |
| A2 | Benchmark owner + Content Extractor | pending |
| A3 | Platform chunking owner | pending |
| A4 | Content Extractor owner | pending |
