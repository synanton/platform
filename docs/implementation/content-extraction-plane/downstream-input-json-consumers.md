# Downstream `KnowledgeProcessingRun.input_json` Consumers

**Status:** Inventory only. No production behavior change.  
**Baseline:** PR #963 / merge `bda6ed72` persists canonical SCEP evidence from `PersistStage`.

## 1. Search result

No production code reads or parses `KnowledgeProcessingRun.input_json`.

| Location | Role |
|---|---|
| `PersistStage.inputJson()` | Sole writer; emits `extraction_*` canonical fields. |
| `IngestionCacheClient.upsertKnowledgeProcessingRun` | Persists the JSON string unchanged. |
| `IngestionCacheClient.readKnowledgeProcessingRun` | Returns the row unchanged; called only by lifecycle tests today. |
| `KnowledgeProvenanceController` | Exposes `processing_run_id` from chunks, not `input_json`. |
| Search/read paths | Use run status and IDs; do not parse structural or extraction metadata. |
| `FixtureIngestionLifecycleTest` | Reads the row for lifecycle assertions only. |

No remaining production consumer depends on legacy `structural_*` or `text_projection` fields.

## 2. Persisted contract

`input_json` now contains canonical fields only:

- `extraction_payload_status`: `PRESENT` or `ABSENT`.
- Payload identity, media type, metadata, and source descriptor.
- Payload descriptor: schema and processor identity, format, schema digest, payload digest.
- Counts derived directly from payload fields: elements, source locations, canonical ranges.
- Projection records, including loss reports.
- Execution provenance, including outcome and quality separately.
- Feature states.

Consumers must not infer missing values from `flattened_text`, source bytes, or legacy
`DocumentStructure`.

## 3. Validation boundary

The current end-to-end production path is:

```text
SCEP adapter
  → DocumentPayload
  → ParsedDocument
  → ExtractionStage
  → PersistStage
  → KnowledgeProcessingRun.input_json
  → ingestion-cache storage
```

Today that path terminates at storage; there is no downstream metadata reader. The appropriate
next validation point is a new consumer, not a migration of an existing one.

When a consumer is added, it must:

1. Parse only documented `extraction_*` fields.
2. Treat `extraction_payload_status=ABSENT` as missing evidence, not an error to repair.
3. Read execution outcome and quality independently.
4. Never reconstruct source or canonical offsets from `flattened_text`.
5. Define its behavior for partial projections and partial execution quality.

## 4. Documentation cleanup

The `ParsedDocument.structure()` consumer inventory is updated to reflect the #963 migration.
The P1 exit report is clarified as local conformance evidence, not the production persistence
contract.
