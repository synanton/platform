# Synthetic Ingestion Benchmark v1 — first isolated run

**Run:** `synthetic-ingestion-v1-run1`
**Status:** PARTIAL / benchmark run recorded
**Source version:** `6937e6eb-0f26-570d-aed0-76b37c91cde1`
**Source digest:** `db4f2e992df82deb62578b0245eed4e99c04cb6bc16c88674820fbcb73add063`
**Operation:** `8fe0f7f8-11c2-49ff-b438-4c526316648f` (`SUCCEEDED`, outcome `PARTIAL`)
**Pipeline:** existing local-real-demo API; tenant `synthetic-ingestion`; regex extraction; graph disabled
**No production graph writes. No semantic model invoked.**

## Stage results

| Stage | Status | Observation |
|---|---|---|
| Preflight | COMPLETED | Source/gold/ontology/manifest checks passed |
| Source registration | COMPLETED | Source identity and pinned digest recorded |
| Parsing | FAILED | Content extractor produced FALLBACK structural output |
| Chunking | FAILED | 2 generated FALLBACK chunks vs 14 frozen gold chunks; heading_level=0; offsets -1 |
| Mention extraction | PARTIAL | 94 candidates: 85 Concept, 9 Identifier; exact P/R/F1 against gold is not computable because the generated chunk coordinate space is fallback/invalid |
| Canonical resolution | NOT_RUN | resolution_status was not populated; no resolver path in this run |
| Relation extraction | PARTIAL | 12 candidates: 11 `is_a`, 1 `uses`; no ontology validation step ran |
| Ontology validation | NOT_RUN | graph validation disabled (`EXTRACTION_GRAPH_ENABLED=false`) |
| Evidence binding | NOT_RUN | no P3a evidence binding in this ingestion path |
| Graph projection | NOT_RUN | graph status PENDING; no graph writes |
| Negative assertions | FAILED | Metadata labels/fragments were emitted as candidates: Author, Edition, Document ID, SPDX-License-Identifier |
| Retrieval | CONDITIONAL | Search projection timestamp exists, but this benchmark did not evaluate retrieval |

## Interpretation

The run is valid empirical evidence even though it is partial. The dominant failure is structural:
the existing ingestion path returned two FALLBACK chunks with no heading levels or document offsets.
Mention and relation counts are reported as candidates, not as precision/recall against the frozen
gold. The 25 gold mentions and 15 gold relations remain the fixed denominator; no gold or baseline
was changed.

## Artifacts

`source.json`, `submit.json`, `operation.json`, `versions.json`, `chunks-summary.txt`,
`extraction-table.txt`, `extraction-summary.json`, `run-manifest.json`.
