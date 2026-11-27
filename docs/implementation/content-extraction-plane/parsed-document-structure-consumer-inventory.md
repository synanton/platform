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
| `structural_status=PARSED/UNSUPPORTED` | `extraction_payload_status=PRESENT` when a payload exists; `ABSENT` otherwise |
| `structural_document_id` | `identity.document_id` |
| `structural_source_id` | `source.source_uri` / source descriptor fields |
| n/a | `identity.document_class`, `identity.document_version` |
| `structural_source_version_id` | `source.source_version` |
| `structural_media_type` | `payload.media_type`; `source.media_type` is retained separately |
| n/a | `payload.metadata` (opaque document metadata) |
| `structural_parser_name/version` | payload descriptor `processor_id/version`, `format`, `schema_digest`, `payload_digest` |
| `structural_element_count` | `documentPayload.elementsCount` |
| `structural_chunk_count` | derived from canonical chunking, not local parser |
| `structural_text_sha256` | not derived from local `DocumentStructure` |
| `extraction_text_sha256` | hash of canonical extraction text or flattened projection |
| `text_projection` | `projections[]` with ID, format, contract, outcome, digest, unsupported reason, content availability, and loss report |

Rules:

1. `PersistStage` reads only `documentPayload`, `payloadDescriptor`, and existing extraction metadata.
2. Processor provenance comes from `PayloadDescriptor` on the enclosing `StructuredPayload`,
   not from `DocumentPayload` itself.
3. Null `ParsedDocument.structure()` is expected and not an error.
4. Missing canonical payload is explicit; do not reconstruct structure or search text for offsets.
5. Payload presence is separate from execution outcome and quality. A partial payload remains
   `PRESENT`; execution `outcome`, `quality_status`, completeness, confidence, and warnings are
   persisted independently. Unset completeness/confidence is omitted; a measured `0.0` is kept.
6. Execution provenance preserves engine, stage, policy, parent links, and attributes in addition
   to extractor identity.
7. Preserve existing persisted-row compatibility; new canonical fields are additive.
8. Do not change existing evaluation artifacts, annotations, or scoring.

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
