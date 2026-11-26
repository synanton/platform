# #936 — Synthetic ingestion execution protocol

**Status:** Proposed; no ingestion executed
**Fixture:** `synthetic-ingestion-v1`
**Gold:** frozen contract; source/gold checksums in fixture `SHA256SUMS`
**Execution path:** existing isolated ingestion path only

## 1. Preconditions

- Source and gold checksums match `evaluation/it-books/synthetic-ingestion-v1/SHA256SUMS`.
- Gold validator passes.
- P1 conformance test passes.
- Ontology is pinned to IT Books v1:
  `47fe38f8-9f72-481b-826a-6c5b12a6027a` / `3a4a5edf...`.
- The selected pipeline uses only deterministic stages available in the existing path.
- No semantic model is invoked unless separately authorized.
- No production graph, A2/A4/A4.1/A8/A8.1/A9 artifacts, ontology, CSO, or evaluation gates are modified.

## 2. Existing path and configuration

The run must use the existing local-real-demo ingestion path, not a new runner:

- `POST /v1/sources` and multipart `POST /v1/ingestion-runs` on the existing `synflux` service;
- isolated Compose project `synanton-local-real-demo` and tenant `synthetic-ingestion`;
- `EXTRACTION_PROVIDER=regex` by default; no semantic fallback;
- `EXTRACTION_GRAPH_ENABLED=false` and no production graph writes;
- resolution/retrieval only if the existing path supports them; otherwise record `UNSUPPORTED` or `NOT_RUN`.

Do not use the hard-coded PDF acceptance script as the synthetic fixture runner. The execution
step must be separately authorized and must not invent a parallel ingestion path.

## 3. Stage observations

| Stage | Required observation |
|---|---|
| Preflight | fixture/gold/hash/ontology/config status |
| Source registration | source ID/version/checksum |
| Parsing | P1 elements, offsets, reading order, source coverage |
| Chunking | generated chunks, parent links, boundaries vs 14 gold chunks |
| Mention extraction | candidates, exact/overlap matches, P/R/F1, type accuracy |
| Canonical resolution | resolved/unresolved/ambiguous/type-mismatch; catalog digest if used; `NOT_RUN` if not wired |
| Relation extraction | emitted candidates, omissions, gold relation matches, predicate correctness |
| Ontology validation | accepted/rejected/unsupported and reasons |
| Evidence binding | resolved spans/elements and semantic support assessment |
| Negative assertions | whether forbidden mentions/relations were emitted |
| Graph projection | disposable/isolated only; otherwise `UNSUPPORTED` |
| Retrieval | `SUPPORTED`, `CONDITIONAL`, or `NOT_RUN`; no conditional pass |

Record emitted-and-rejected separately from not-emitted. Evidence resolution is not the same as
evidence supporting the claim.

## 4. Run statuses

Allowed values: `COMPLETED`, `FAILED`, `NOT_RUN`, `BLOCKED_AUTHORIZATION`, `UNSUPPORTED`,
`CONDITIONAL`.

A run is not accepted unless every non-completed stage has an explicit reason.

## 5. Run manifest

Use `run-manifest.template.json` and fill:

- fixture/source/gold SHA-256;
- ontology ID/release/digest;
- compose project, tenant, provider/model configuration and authorization state;
- extractor/parser/resolver/validator versions;
- stage statuses, counts, errors and artifact checksums;
- retrieval support state;
- run timestamp and git commit.

## 6. Acceptance

1. Frozen source/gold/ontology identities match their pins.
2. Existing pipeline path is used; no second runner.
3. Every executed stage has inspectable output.
4. Every skipped stage has an explicit status and reason.
5. Comparisons use the original frozen gold; no gold tuning.
6. No production graph, baseline, ontology, or unrelated artifacts are modified.
7. Repeatability is reported where the stages permit it.

No F1 threshold is required for the first execution; the goal is an honest measurement.

## 7. Post-run attribution

Classify failures before opening implementation work:

- structural/parse/offset defect → parsing fix;
- chunk mismatch → chunking policy review;
- missing/incorrect mentions or types → A9.2 extraction work;
- wrong canonical identity → resolver/catalog investigation;
- ontology-invalid relation → predicate/contract investigation;
- valid predicate with unsupported evidence → evidence-grounded extraction;
- negative assertion emitted → targeted validation fix;
- retrieval unsupported → capability gap, not an extraction failure.

A9.2 real-book work remains independent of this synthetic run.
