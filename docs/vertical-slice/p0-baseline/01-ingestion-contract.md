# P0-2 — Ingestion contract baseline

Status: draft for #859. Shapes are verified against production DTO/controller code, not prose. Tests were not executed; P0-3 owns pass/fail. Paths are repo-relative.

## Interface set

| Interface | Method / path | Version | Evidence |
|---|---|---|---|
| Sources | `POST/GET/PATCH/DELETE /v1/sources...` | `/v1` | `java/synflux/src/main/java/org/synanton/synflux/api/SourceController.java:32-33` |
| Ingestion runs | `POST/GET /v1/ingestion-runs...` | `/v1` | `.../IngestionRunController.java:25-26` |
| Operations | `GET/POST /v1/operations...` | `/v1` | `.../OperationController.java:22-23` |
| Legacy ingest | `/ingest`, `/ingest/run`, `/ingest/jobs...` | unversioned | `.../IngestController.java:26-27,96-98` |
| Content push/read | `/content/{tenant}`, `/manifest/{tenant}...` | unversioned | `java/synvault/.../ContentPushController.java:24`; `ManifestController.java:32-55` |
| Extraction handoff | `ExtractionRequested@1` event + Cassandra extraction slot | `event_version=1` | `events/IngestionEventConfig.java:21,32`; `events/ExtractionRequested.java:9-24` |

No `@Deprecated`, `Sunset`, or deprecation headers found. Only prose calls `/ingest/run` and `/content/{tenant}` legacy.

## `SourceController` — `/v1/sources`

### Endpoints

| Method / path | Success | Response |
|---|---|---|
| `POST /v1/sources` | 201 | `SourceResource` |
| `GET /v1/sources` | 200 | `SourceResource[]` |
| `GET /v1/sources/{id}` | 200 | `SourceResource` |
| `GET /v1/sources/{id}/status` | 200 | `SourceStatusResource` |
| `PATCH /v1/sources/{id}` | 200 | `SourceResource` |
| `DELETE /v1/sources/{id}` | 204 | empty; config delete leaves `status=DELETED` |
| `DELETE /v1/sources/{id}/resources?resource_id=` | 202 | `RunSubmission` |
| `GET /v1/sources/{id}/versions` | 200 | `SourceVersionResource[]` |
| `GET /v1/sources/{id}/versions/{versionId}` | 200 | `SourceVersionResource` |

Evidence: `SourceController.java:55-56,94-97,99-100,212-219,245-250,252-282`.

### Request/response shapes

`CreateSourceRequest` (snake_case, ignores unknown):

| Field | Type | Required | Semantics |
|---|---|---|---|
| `source_id` | string | yes | non-blank |
| `connector_id` | string | yes | non-blank |
| `connector_type` | string | yes | exactly `file` or `upload`; else 400 |
| `source_endpoint` | string | no | `null`→`""`; `file` must stay under `SYNFLUX_FIXTURE_ROOT` |
| `capability_flags` | string[] | no | `null`→`[]` |
| `tenant_id` | — | ignored | tenant only from verified context |

`PatchSourceRequest`: optional `source_endpoint`, `capability_flags`; null retains current. `DELETE .../resources`: `resource_id` must be normalized connector-relative; optional `Idempotency-Key` ≤256 chars. Evidence: `SourceController.java:238-241,307-336`.

`SourceResource`: `source_id`, `connector_id`, `connector_type`, `source_endpoint`, `capability_flags`, `status`, `created_at`, `last_successful_sync_at` (nullable). `SourceStatusResource`: `source_id`, `status`, `indexed`, `chunk_count`, `extraction_status`, `graph_status`, `resolution_status`. `SourceVersionResource`: `source_identity{tenant_id,connector_id,source_id,resource_id}`, `version_id` UUID, `content_digest`, `content_cache_reference`, `source_revision`, `observed_at`. Evidence: `SourceController.java:155-210,338-374`.

### Errors / retryability

- `401` missing/invalid bearer or no tenant claim; body `{"status":401,"code":"AUTHENTICATION_REQUIRED"|"TENANT_CONTEXT_MISSING","detail":...}` (`shared/common/.../TenantContextFilter.java:74-91,113-117`).
- `400` invalid create fields, connector type, file root, resource ID, long idempotency key.
- `404` source/version not found.
- `409` existing source identity conflict, deleted-source reuse, patch of deleted, filesystem-origin change, idempotency identity conflict.
- `400/401/404/409` not retryable unchanged. Non-401 error body shape is **unknown**; no product `@RestControllerAdvice` found in synflux.

### Consumers

Scripts/E2E: `scripts/demo/rak-pipeline-smoke.py`; `scripts/run-local-real-demo.sh`; `tests/integration/vertical-slice.sh`; `deployments/homelab/test/p1-concurrency.sh`; `test/e2e/tests/entity-graph.live.spec.ts`. No UI ingestion consumer.

## `IngestionRunController` — `/v1/ingestion-runs`

| Method / path | Consumes | Success | Response |
|---|---|---|---|
| `POST /v1/ingestion-runs` | JSON | 202 | `RunSubmission{run_id,operation_id,status}` |
| `POST /v1/ingestion-runs` | multipart | 202 | `RunSubmission` |
| `GET /v1/ingestion-runs/{runId}` | — | 200 | `RunResource` |
| `POST /v1/ingestion-runs/{runId}/cancel` | — | 200 | `RunResource` |

JSON request: `source_id`, `resource_id` required non-blank; optional `Idempotency-Key` ≤256. Multipart: `connector_id`, `source_id`, `resource_id`, `file`; file must declare Content-Type, not `application/octet-stream`, non-empty. `RunResource`: `run_id`, `operation_id`, `source_id`, `status`, `started_at`, `completed_at`, `result`, `error_ref`; `SyncResultResource` counters `discovered,changed,unchanged,deleted,failed,skipped,quarantined,cancelled`. Evidence: `IngestionRunController.java:39-101,132-183`.

Errors: 401 auth; 400 invalid JSON/headers/media/upload/resource; 404 source/run; 409 deleted source or idempotency conflict; 500 unexpected. Async retry is operation-level. Consumers: JSON/multipart scripts/E2E; GET/cancel tests only; no UI.

## `OperationController` — `/v1/operations`

`GET /v1/operations/{id}`, `POST /v1/operations/{id}/cancel` → `OperationResource`: `operation_id`, `type`, `status`, `requested_by.principal_id`, `tenant_id`, `created_at`, `started_at`, `completed_at`, `progress{completed,total}`, `outcome`, `details`, `error`. Error keys: `type,title,status,detail,instance,code,retryable,request_id,details`; production sets retryable true only for `DEPENDENCY_UNAVAILABLE`. 404 unknown; cancel of terminal returns 200 with terminal status. Consumers: demo/integration scripts, no UI. Evidence: `OperationController.java:36-118`; `FixtureIngestionService.java:562-596`.

## Legacy `/ingest`

`POST /ingest` returns `202 {job_id,status:"QUEUED",estimated_wait_seconds}` on Kafka path, else `200 {jobId,state:"RUNNING"}`; `/ingest/run` returns `{jobId,state:"RUNNING"}`; job reads return `JobRow` camelCase. Request `{source,path}` required; tenant from verified context. Errors 400/401/404; Kafka failure falls back inline; persistence best-effort. **Casing hazard:** Kafka path snake_case, inline paths camelCase. Consumers: retrieval-eval, ingestion/index scripts, `KafkaIngestConsumer`, `RouterConsumer`. Evidence: `IngestController.java:49-130`; `JobRow.java:6-23`.

## Synvault content/manifest

- `POST /content/{tenant}` body bytes; headers `X-Source-Uri`, `Content-Type` (defaults octet-stream); `200 {content_ref_id,sha256,size_bytes,source_uri}`; deterministic UUIDv5 over tenant+sha256; no auth/tenant guard in controller. No in-repo consumer; documented external Lucentrix.
- `GET /manifest/{tenant}?limit=100`, `GET /manifest/{tenant}/{ref}` → `ManifestRow` camelCase; `GET /content/{tenant}/{ref}` → 302 presigned URL 15 min. Tenant guard present but no synvault Bearer filter; missing context means unscoped. No controller test or in-repo consumer.
- Evidence: `ContentPushController.java:24-38`; `ContentPushPort.java:25-62`; `ManifestController.java:32-55`; `ManifestRow.java:6-24`.

## `ExtractionRequested@1` handoff

Event envelope: `event_id`, `event_type`, `event_version=1`, `occurred_at`, `producer`, `tenant_id`, `tenant`, `correlation_id`, optional `causation_id/subject_id`, `replay_class=REPLAY_SAFE`, `security`, `payload`. Payload: `source_identity{tenant_id,connector_id,source_id,resource_id}`, `version_id` UUID, `content_ref_id` UUID, `observed_at`. Deterministic event ID UUIDv5 over type+version. Published to `source_events` at-least-once; consumer actually polls Cassandra extraction slot after `search_projected_at`. No in-repo Kafka `source_events` consumer found. Evidence: `ExtractionRequested.java:19-24`; `FixtureIngestionService.java:694-710`; `OutboxPublisher.java:99-137`; `ExtractionConsumer.java:158-188`.

## Unknowns / gaps

- Non-401 HTTP error body shape unknown for synflux/synvault.
- Batch/folder upload absent; only single-file multipart or legacy `/ingest`.
- Synvault tenant enforcement and error handling unwired/untested; `ForbiddenException` has no handler.
- Content push/manifest controller tests absent; external consumers documented-only.
- Kafka `/ingest` branch untested; casing inconsistent.
- Max upload size: `synflux.ingest.max-file-size-bytes` not referenced; servlet limit behavior (400 vs 413) unknown.
- No golden JSON for `ExtractionRequested@1`; design-doc payload is stale vs code.
- No UI consumer of any ingestion endpoint.
