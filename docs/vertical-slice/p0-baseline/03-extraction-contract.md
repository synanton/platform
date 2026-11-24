# P0-2 — Extraction contract baseline

Status: draft for #859. Verified against proto/Java records; tests not executed. Paths are repo-relative.

## gRPC contract — `synanton.extraction.v1`

- Proto: `java/extraction-contract/src/main/proto/synanton/extraction/v1/extraction_service.proto`; byte-identical mirror enforced by `extraction-contract-mirror.yml`.
- Package `synanton.extraction.v1`, proto3, `contract_version` field. No stability annotation; platform contains **no server implementation** (external plane).
- RPCs: `SubmitExtraction`, `SubmitExtractionBatch`, `ExtractSync`, `GetOperations`, `ListCompletedOperations`, `GetResult`, `CancelOperation`, `GetCapacity`, `EstimateExtraction`, `GetCapabilities`. Evidence: proto `:44-102`.
- `ExtractionOperation` is the stable async handle: `operation_id,tenant_id,status,progress,created_at,updated_at,expires_at,items[],admission,error`. `ExtractionResult` carries `operation_id,item_index,content_ref_id,status,payload,flattened_text,feature_states,provenance,metadata,business_tags,error,usage`. Payload supports inline or reference; callers MUST handle both.
- Error contract: `ExtractionError{code,diagnostic,retryable}`; 13 codes; only `ERROR_REJECTED_CAPACITY`, `ERROR_TIMEOUT`, `ERROR_INTERNAL_ERROR` retryable. `diagnostic` unstable.
- Request validation contract: `ExtractionRequestValidator` enforces tenant regex `[a-zA-Z0-9_-]{1,64}`, idempotency key ≤256, content ref ≤256, media type ≤255, source bucket/key bounds, lowercase SHA-256, positive size, priority not UNSPECIFIED, batch 1..256, page bounds; collects all violations. **No production caller found.**
- Client behavior: submit timeout reconciles via same idempotency key; polls by operation ID; `GetOperations` empty → NOT_FOUND; does not consult error catalogue. Evidence: `ExtractionPlaneClient.java:59-180`.
- Unknowns: server implementation external; `ExtractionErrorCatalogue`/validator contract-only; `ExtractionStage` accepts inline payload only, not `PayloadReference` (proto says both required).

## Engine SPI

| Contract | Shape / semantics | Evidence |
|---|---|---|
| `ExtractionEngineAdapter` | `extract(TextSegment,ExtractionContext) -> ExtractionResult`; default `cacheKey(...) -> Optional<ExtractionCacheKey>` | `ExtractionEngineAdapter.java:12-24` |
| `ExtractionContext` | `tenantId,documentId,allowedEntityTypes,sourceVersionId` | `ExtractionContext.java` |
| `TextSegment` | `chunkId,text,charOffsetStart` | `TextSegment.java` |
| `ExtractionResult` | `preEntities,relations,llmUsage,quarantined,integrity,provenance`; candidate knowledge only | `ExtractionResult.java:6-40` |
| `PreEntity` | `mentionId,surfaceForm,proposedType,charStart,charEnd,context` | `PreEntity.java` |
| `RelationCandidate` | `subjectMentionId,predicate,objectMentionId,evidenceChunkId,evidenceCharStart,evidenceCharEnd` | `RelationCandidate.java` |
| `LlmProvenance` | `promptVersion,promptCompiledDigest,promptRenderedDigest,schemaVersion,schemaDigest`; null = unavailable, never “same as current” | `LlmProvenance.java:10-19` |
| `LlmUsage` | `finishReason,promptTokens,completionTokens,totalTokens`; `NONE=(-1)`; `isIncomplete()` = length | `LlmUsage.java` |
| `ExtractionIntegrity` | `CLEAN,TRUNCATED,CORRUPTED`; CORRUPTED must fail closed | `ExtractionIntegrity.java:3-15` |
| `QuarantinedLine` | `lineNumber,raw,reasonCode,detail,category`; 1-based; STRUCTURAL/SEMANTIC | `QuarantinedLine.java` |
| `JsonlFailureReason` | stable codes: JSON_PARSE,TRUNCATED,LINE_TOO_LARGE,CONCATENATED_RECORDS,SCHEMA,EMPTY_FIELD,UNKNOWN_RECORD_TYPE; UNKNOWN_ENTITY_TYPE,UNKNOWN_PREDICATE,UNANCHORED,SELF_RELATION,DUPLICATE | `JsonlFailureReason.java:4-23` |

Schema provenance is **not produced**: LLM adapter hardcodes schema version/digest null (`LlmExtractionAdapter.java:218-219`); tests assert null.

## Consumer handoff

| Contract | Semantics | Evidence |
|---|---|---|
| Pending query | event present, not published/dead-lettered, `search_projected_at != null`, backoff elapsed, `extraction_status != KNOWLEDGE_READY`; sorted/limited | `IngestionCacheClient.java:899-943` |
| `ExtractionRequestRow` | tenant/connector/source/resource, eventId, versionId, contentRefId, payloadJson, requestedAt, searchProjectedAt, status, attemptCount, lastError, nextAttemptAt, deadLettered, generation | `ExtractionRequestRow.java:6-33` |
| Status | `PENDING → EXTRACTING → KNOWLEDGE_READY | EXTRACTION_FAILED`; graph status separate | `IngestionCacheClient.java:1525-1595` |
| Success | `markExtractionSucceeded`: KNOWLEDGE_READY, provider, published=true, clear error/backoff/dead-letter | same |
| Retryable failure | `markExtractionFailed`: EXTRACTION_FAILED, attempt/backoff, not published | same |
| Terminal failure | `markExtractionFailedTerminal`: dead-lettered + no next attempt | same |
| Provider handoff | persisted provider wins; otherwise resolve tenant and persist **before** extraction; retry cannot switch provider | `ExtractionConsumer.java:191-220` |
| Result upsert | per chunk: contentRef/ordinal/sourceVersion + entities/relations + prompt/schema provenance | `IngestionCacheClient.java:1620-1641` |
| Error nuance | incomplete LLM/provider-invalid dead-letter; generic/corrupted retry with capped backoff and at maxAttempts only log, not dead-letter | `ExtractionConsumer.java:203-220,357-384,503-507` |

## Quarantine store

| Contract | Fields / semantics | Evidence |
|---|---|---|
| `QuarantineGeneration` | tenant, sourceVersion, generation, sourceDocumentId, prompt/schema, model/revision, startedAt, runId, status STAGED/SUPERSEDED/FAILED/DELETED, supersession/promotion/deletion fields | `QuarantineGeneration.java:8-43` |
| `QuarantineRecord` | tenant, sourceVersion, generation, chunk/line, reason/category/detail, raw+sha, capturedAt, disposition fields, retentionExpiresAt (`+30d`) | `QuarantineRecord.java:27-38` |
| `QuarantineAuditEvent` | tenant, sourceVersion, generation, eventId/time/type, actor, reason, detail | `QuarantineAuditEvent.java:8-30` |
| `createGeneration/append` | insert; append idempotent by PK | `QuarantineStore.java:41-76` |
| `currentGeneration` | pointer is authoritative active state | `:78-86` |
| `promote` | LWT max 4 attempts; make current, supersede previous, idempotent if current; audit ATTEMPTED/IDEMPOTENT/APPLIED/FAILED | `:88-134,243-259` |
| `deleteSource` | TOMBSTONE keeps rows/hides pointer; HARD_DELETE removes rows/pointer; audit; returns count | `:174-211` |
| `markFailed` | sets FAILED + audit GENERATION_FAILED | `:261-273` |

**Promotion has no production caller**; normal extraction leaves STAGED and no current pointer. Quarantine retention is metadata only; no expiry job.

## Cache contract

| Contract | Fields / semantics | Evidence |
|---|---|---|
| `ExtractionCacheKey` | tenant, sourceVersion, segmentHash, promptDigest, schemaDigest, model/revision, temperature, topP, tokenizer; **excludes generation** | `ExtractionCacheKey.java:11-46` |
| `ExtractionCacheService` | `lookup`, `put`, `getOrLoad`; hit returns `CachedExtractionResult{result,generationId,cacheHit=true}` | `ExtractionCacheService.java:49-87` |
| Hit reconstruction | builds `LlmUsage.NONE`, empty quarantine, CLEAN, provenance from entry; **drops usage/quarantine/integrity** | same |
| Consumer preflight | all chunks must hit with same generation; otherwise normal lifecycle; write only after generation exists | `ExtractionConsumer.java:228-247,306-318,387-437` |
| Durable store | `get/put/invalidate/deleteForSource`; cache deletion never deletes generation/provenance | `ExtractionCacheStore.java:24-83` |
| Replay policy | `PromptReplayPolicy` full/verification/rejected; **not wired**; cache service serves rendered-only | `PromptReplayPolicy.java:30-60` |
| TTL | none | absence |

## Capture contract

`CaptureStore` writes tenant/source-version-scoped quarantine JSONL and parse-failure JSON; `deleteSource` idempotent; `expireOlderThan` cutoff; production enablement refused; scheduled 30-day job. Exceptions are swallowed so capture never masks extraction. Evidence: `CaptureStore.java`, `FileSystemCaptureStore.java:33-208`, `CaptureRetentionJob.java:20-51`, `ExtractionEngines.java:15-46`. `deleteSource` has **no production cascade caller**.

## Provider contract

`ExtractionProvider` values `REGEX("regex")`, `LLM("llm")`; parse accepts `vllm` alias; blank → regex; unknown fails closed. Resolution precedence tenant override → env default → hardcoded regex; adapters cached per provider. Evidence: `ExtractionProvider.java:20-28`; `ExtractionAdapterFactory.java:29-55`; `ExtractionEngines.java:29-46`.

## Error/retry matrix

| Failure | Behavior | Retryable |
|---|---|---|
| Rejected capacity/timeout/internal (gRPC) | catalogue retryable | yes |
| Submit deadline | resubmit same idempotency key | yes |
| Incomplete LLM output | terminal dead-letter | no |
| Provider config invalid | terminal dead-letter | no |
| Provider resolution other | retry with backoff | yes |
| Corrupted output | generic retry, not dead-letter even at max | yes |
| Generic adapter/graph/quarantine | retry capped 300s | yes |
| Cache lookup/key/write | degrade to normal lifecycle | yes (normal extraction) |
| Capture write/expiry | swallowed | n/a |

## Unknowns

Server external; validator/catalogue contract-only; promotion/replay/deleteSource/capture-delete not wired; schema digest absent; cache hit loses fidelity; mixed-generation cache branch untested; no cache TTL; quarantine retention/encryption/audit absent; `PayloadReference` unsupported; client ignores `not_found_operation_ids`; `QuarantinedLine` javadoc stale.
