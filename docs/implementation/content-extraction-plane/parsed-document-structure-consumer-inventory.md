# ParsedDocument.structure() Consumer Inventory

**Status:** Inventory and migration contract only. No production implementation.  
**Baseline:** PR #961 / merge `9a8f6442` removes local parsing from production `ExtractionStage`.

## 1. Repository-wide usage

`ParsedDocument.structure()` has exactly one production caller:

| Consumer | Classification | Current behavior |
|---|---|---|
| `PersistStage.inputJson()` | Production | Writes legacy `structural_*` fields. After #961 the field is null, so production records only `structural_status=UNSUPPORTED`. |
| `PersistStageTest.writesStructuralProvenanceIntoProcessingRunInput` | Test | Constructs a local `DocumentStructure` to exercise the legacy JSON path. |
| `P1ExitConformanceTest` | Conformance | Asserts production `parsed.structure()` is null; local structure is checked separately. |
| `ExtractionStageProductionPathTest` | Production-path guard | Asserts no local `DocumentStructureService` construction and null structure. |

`ParsedDocument.withStructure(...)` has no production caller and is used only by `PersistStageTest`.

Direct `DocumentStructure`/`DocumentStructureService` consumers are evaluation/conformance only:

| Consumer | Classification |
|---|---|
| `DocumentStructureServiceTest` | Conformance |
| `P1FixtureConformanceTest`, `P1ExitConformanceTest` | Conformance |
| `SyntheticFixtureValidationTest`, `A2FixtureValidationTest` | Evaluation |
| `EvidenceBinderTest` | Evaluation |
| `RegexBaselineSmoke`, `RegexBaselineA4`, `GpuSemanticEvaluation` | Evaluation |
| `StructuralEvidenceResolver`, `StructuralArtifactDigest`, `EvidenceBinder` | Evaluation library; no production caller |
| `PersistStageTest` local structure fixture | Test only |

## 2. Canonical evidence available

Production already carries `ParsedDocument.documentPayload()` with:

- `source`: media type, source format, source digest/version.
- `elements`: ID, type, text, level, child IDs, attributes, content provenance.
- `elements.source_locations`: typed original-source locations.
- `elements.canonical_text_range`: optional canonical text identity/range.
- `elements.execution_ids` / `execution_attribution`.
- `provenance`: execution history and quality.
- `projections`: loss-aware serializations.
- `flattened_text`: legacy compatibility projection.

Absent optional fields remain absent. Consumers must not infer source or canonical offsets from `flattenedText`.

## 3. PersistStage migration contract

Replace legacy structural metadata with canonical extraction metadata:

| Current field | Canonical replacement |
|---|---|
| `structural_status=PARSED/UNSUPPORTED` | `extraction_payload_status=CANONICAL` when a usable payload exists; `ABSENT` otherwise |
| `structural_source_id` | `source.source_uri` / source descriptor fields |
| `structural_source_version_id` | `source.source_version` |
| `structural_media_type` | `source.media_type` |
| `structural_parser_name/version` | payload descriptor `processor_id/version` |
| `structural_element_count` | `documentPayload.elementsCount` |
| `structural_chunk_count` | derived from canonical chunking, not local parser |
| `structural_text_sha256` | not derived from local `DocumentStructure` |
| `extraction_text_sha256` | hash of canonical extraction text or flattened projection |
| `text_projection` | retain only where the canonical projection actually exists |

Rules:

1. `PersistStage` reads only `documentPayload` and existing extraction metadata.
2. Null `ParsedDocument.structure()` is expected and not an error.
3. Missing canonical payload is explicit; do not reconstruct structure or search text for offsets.
4. Preserve existing persisted-row compatibility; new canonical fields are additive.
5. Do not change existing evaluation artifacts, annotations, or scoring.

## 4. Test contract

Focused regression tests should cover:

- Canonical payload present → canonical fields persisted.
- Canonical payload absent → explicit `ABSENT`, no local reconstruction.
- Source descriptor fields preserved.
- Element/source-location counts reflect payload.
- Legacy local `DocumentStructure` does not appear in production input JSON.
- `PersistStage` behavior remains idempotent.

## 5. Boundary

- Production migration: `PersistStage` only.
- Evaluation migration remains separate (P1/A2/A4 consumers).
- Local `DocumentStructureService` remains available for conformance/evaluation until a separate retirement decision.
- No parser, schema, adapter, benchmark, or baseline changes in the inventory step.
