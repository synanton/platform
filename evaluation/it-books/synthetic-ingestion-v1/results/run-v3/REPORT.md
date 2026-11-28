# Synthetic Ingestion v1 — run-v3

**Operation:** `640dad56-6e64-4b67-a2b1-8efbc7f2de77` (`SUCCEEDED`, outcome `PARTIAL`)  
**Ingestion run:** `76cc94f3-1eac-31bd-b756-16062f04ad8a`  
**Source version:** `6937e6eb-0f26-570d-aed0-76b37c91cde1`  
**Config commit:** `7fc0f5558be2`  
**Strategy:** `semantic-benchmark`  
**Images:** `run3-7fc0f5558be2`

## Result: PARTIAL

- 14 generated chunks match 14 gold section paths.
- Book, Chapter 1, and Chapter 2 emitted as `HEADING` containers.
- Appendix A emitted once as `TABLE`; no duplicate SUBSECTION.
- No breadcrumb prefix or repeated heading in benchmark text.
- All document offsets remain `-1`: SCEP supplied no canonical projection or UTF-16 ranges.
- Book chunk is heading-only: metadata preamble is not in SCEP evidence and was not reconstructed.
- `schema_digest` remains empty (separate Content Extractor issue #32).
- Mention extraction, resolution, relation extraction, ontology validation, evidence binding, and graph projection remain NOT_RUN. Retrieval is CONDITIONAL.

## Stage results

| Stage | Status |
|---|---|
| Runtime/source/SCEP payload | PASS |
| Structural parsing | PARTIAL |
| Semantic chunking | PARTIAL |
| Heading context / ordering / coverage | PASS |
| Table atomicity | PASS |
| Metadata negatives | PARTIAL |
| Offsets | FAIL / unavailable |
| Mention/resolution/relation/ontology/evidence/graph | NOT_RUN |
| Retrieval | CONDITIONAL |

## Comparison to run-v2

Run-v2 had 12 chunks with 3 missing containers, a duplicate Appendix A, breadcrumb/repeated headings, and `-1` offsets. Run-v3 fixes chunk coverage, container emission, table duplication, and benchmark text policy. Canonical offsets and the metadata preamble remain blocked by the unmerged/unimplemented SCEP canonical evidence work (#33/#34). Run-v2 is unchanged.

## Artifacts

`run-manifest.json`, `extraction-evidence.json`, `chunk-comparison.json`, `negative-assertions.json`, `stage-results.json`, raw API/Cassandra captures, `SHA256SUMS`.

## Environment deviation

Local Docker Compose on `main` lacked the #968 `source_revision_fence_unique` constraint, so Relix exited at startup during this run. The constraint was applied after the chunking operation; Relix became healthy. Ingestion and chunking do not use Relix. This is recorded as an environment deviation; it is not a chunking failure.
