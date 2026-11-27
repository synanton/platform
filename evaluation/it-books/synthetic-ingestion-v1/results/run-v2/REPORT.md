# Synthetic Ingestion v1 — run-v2

**Run:** `synthetic-ingestion-v1-run2`  
**Operation:** `3b1b5c8a-47b9-488a-a958-63a7a7779070` (`SUCCEEDED`, outcome `PARTIAL`)  
**Ingestion run:** `583d92c7-0332-30a6-86ea-f8faa1dcb3d9`  
**Source version:** `6937e6eb-0f26-570d-aed0-76b37c91cde1`  
**Config commit:** `8205cdb6`  
**Images:** `run2-c3c833d5ab12`  
**Pipeline:** existing local-real-demo API; tenant `synthetic-ingestion`; SCEP text adapter; graph disabled  
**No production graph writes. No semantic model invoked.**

## Stage results

| Stage | Status | Observation |
|---|---|---|
| Preflight/runtime | PASS | Clean stack, 7/7 Neo4j constraints, all services healthy. |
| Source registration | PASS | Source version bound to `db4f2e99...`. |
| SCEP extraction payload | PASS | `text-adapter`, 51 elements, 51 source locations, descriptor present. |
| Structural parsing | PARTIAL | SCEP payload present; document offsets are not propagated to chunks (`-1`). |
| Semantic chunking | PARTIAL | 12 chunks vs 14 gold; three container headings missing; Appendix A duplicated. |
| Heading context | PARTIAL | Emitted paths/levels are correct; book/Chapter 1/Chapter 2 paths absent. |
| Ordering | PASS | Ordinals increase 0–11. |
| Coverage | PARTIAL | 11/14 gold section paths represented. |
| Metadata negatives | PARTIAL | Metadata surfaces absent from chunks; mention-level checks NOT_RUN. |
| Mention extraction | NOT_RUN | Extraction consumer disabled; 0 `extraction_results` rows. |
| Canonical resolution | NOT_RUN | `resolution_status` is null. |
| Relation extraction | NOT_RUN | No extraction result rows. |
| Ontology validation | NOT_RUN | Graph validation disabled. |
| Evidence binding | NOT_RUN | Not wired into this path. |
| Graph projection | NOT_RUN | Disabled; graph status PENDING. |
| Retrieval | CONDITIONAL | Projection timestamp exists; not evaluated. |

## Findings

- The SCEP-only path is active and produced a canonical Markdown payload.
- The historical 2-chunk fallback failure is resolved: 12 structural/table chunks were generated.
- Chunking remains partial: book, Chapter 1, and Chapter 2 container headings are missing.
- Appendix A is duplicated as TABLE and SUBSECTION.
- All document offsets are `-1`; offset/boundary conformance fails.
- Chunk text includes a section-path prefix and repeated heading.
- No mention/relation rows exist because the extraction consumer is disabled.
- No graph, ontology, evidence, or retrieval result is counted as success.

## Attempt history

- Attempt 1: HTTP 500 before operation creation due to a stale named `content-cache` volume owned by `_apt`. Preserved under `attempts/`.
- Attempt 2: HTTP 202, operation `3b1b5c8a-47b9-488a-a958-63a7a7779070` completed `SUCCEEDED` / `PARTIAL`.

## Artifacts

`run-manifest.json`, `extraction-evidence.json`, `chunk-comparison.json`,
`negative-assertions.json`, `stage-results.json`, raw API/Cassandra captures, and `attempts/`.
