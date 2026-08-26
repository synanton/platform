---
title: "Structured Content Extraction Plane — Implementation Plan"
status: "planned"
last_reviewed: "2026-08-24"
---

# Structured Content Extraction Plane — Implementation Plan

**Purpose:** Implementation plan for the v1.21 Structured Content Extraction Plane. Introduces a deployment-neutral extraction boundary between raw content storage and Synanton knowledge processing, connected to the primary platform through the versioned `synanton.extraction.v1` gRPC contract.
**Architecture reference:** `docs/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md` (contract), `docs/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane_draft.md` (multimodal design)
**Target repositories:** `synanton/platform` (this repo — client side), `synanton/content_extractor` (new repo — extraction plane)
**Audience:** Architects, module owners, extraction engineers, SREs
**Last Updated:** 2026-08-24

---

## Theme

> Extraction is a platform contract, not a processor implementation. The platform decides *what* must be extracted and *with what constraints*. The extraction plane decides *how* extraction is performed. Deployment topology — embedded, co-located, or an independent cluster — is a scaling concern that MUST NOT change the contract.

---

## User-Facing Capability Unlocked

- PDF, plain text, EPUB, and HTML content is extracted into a structured payload that preserves reading order, headings, lists, tables, bounding boxes, and page provenance — not just flattened text.
- Downstream consumers never reparse raw source bytes to obtain text; `flattenedText` is a projection of the structured result.
- Expensive extraction (OCR, transcription, image description, video scene analysis) is requested explicitly and reported honestly: callers learn whether a feature was applied, was unnecessary, is unsupported, or failed.
- Extraction work survives client disconnects. Operations are idempotent, pollable, expirable, and batchable.
- The extraction implementation can move from in-process to a separate cluster, and grow GPU/OCR/ASR capability, without a platform API change.

---

## Non-Negotiable Invariants

These are derived from §30 (`§67.1`–`§67.18`) of the proposal and are enforced by review, tests, and the `.cursor/rules` in the new repo.

1. **Contract over topology.** The contract MUST be byte-identical whether extraction runs embedded or as a cluster.
2. **Black-box extraction.** The platform MUST NOT know which parser, library, accelerator, queue, or worker executes the work.
3. **Raw source authority.** The extraction plane MUST NOT modify the source artifact. Raw bytes remain authoritative.
4. **No topology leakage.** No pod names, worker pool names, GPU device IDs, queue names, CPU counts, or processor-internal endpoints cross the contract.
5. **No consumer reparse.** A structured consumer being unavailable MUST NOT cause the raw source to be reprocessed for text.
6. **Extraction ≠ knowledge processing.** No ontology assignment, entity resolution, relationship inference, or business classification inside the plane.
7. **Idempotency is required** for every asynchronous operation.
8. **Feature state is explicit.** A requested feature that was not applied MUST NOT report success silently.
9. **No webhooks in v1.21.** Operation ID + status polling + cursor completion polling only.
10. **PostgreSQL is the authoritative operation-state store.** No Redis, no Kafka, no Cassandra for operation state.
11. **Equalix (or any scheduler) MUST NOT become an architectural dependency.** Only scheduling *intent* (priority class) crosses the contract.
12. **Untrusted content.** Parsers run under enforced size, time, and output limits; source-supplied code is never executed.

---

## Repository Split

| Concern | Repository | Rationale |
|---|---|---|
| `synanton.extraction.v1` proto contract | **both** (mirrored, byte-identical) | Same pattern as `synanton.gpu.v1`: `java/gpu-contract` exists in `platform` and the server stubs live in `gpu-runtime`. |
| Extraction client, fallback policy, ingestion wiring | `synanton/platform` | The platform owns *what* to extract and what to do when extraction is unavailable. |
| Extraction service, adapters, processors, operation store | `synanton/content_extractor` | The plane owns *how*. Must not depend on platform internals. |
| Knowledge processing of the payload | `synanton/platform` | Downstream of the boundary. |

Mirroring rule (inherited from the GPU plane): the `.proto` files are copied verbatim between repositories and a CI check fails on divergence. Neither repo depends on the other's build.

---

## New Project — `synanton/content_extractor`

Structure mirrors `synanton/gpu-runtime` exactly: root Gradle Kotlin DSL multi-module build, `java/<module>/`, a version catalog, `.cursor/rules/` carrying the architectural invariants, `doc/` for the plan, and `.github/workflows/gradle.yml`.

```text
content_extractor/
├── build.gradle.kts                    # root: group=org.synanton, Java 21 toolchain, buildAll task
├── settings.gradle.kts
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml
│   └── wrapper/
├── gradlew, gradlew.bat
├── LICENSE                             # Apache 2.0
├── README.md
├── .gitignore
├── .github/workflows/gradle.yml
├── .cursor/rules/
│   ├── java-rules.mdc                  # hexagonal layout, Lombok, test conventions
│   └── extraction-rules.mdc            # the 12 invariants above, enforced
├── doc/
│   ├── Structured Content Extraction Plane Implementation Plan v1.21.md
│   └── TEST_ENVIRONMENT_SETUP.md
└── java/
    ├── extraction-contract/            # proto only; no service deps
    │   └── src/main/proto/synanton/extraction/v1/
    │       ├── extraction_service.proto
    │       └── extraction_payload.proto
    ├── extraction-gateway/             # the service: gRPC in, operation store, router
    │   └── src/main/java/com/synanton/extraction/
    │       ├── ExtractionGatewayApplication.java
    │       ├── adapter/in/grpc/
    │       ├── adapter/out/database/
    │       ├── adapter/out/objectstore/
    │       ├── domain/{model,service}/
    │       └── config/
    ├── extraction-spi/                 # ModalityAdapter SPI + normalized payload model
    ├── adapter-document-pdf/           # OpenDataLoader-backed PDF adapter
    ├── adapter-document-text/          # plain text + EPUB + HTML (Tika-backed)
    └── adapter-stubs/                  # audio/image/video: capability-declining stubs
```

**Package root:** `org.synanton.extraction.*` (matches `gpu-runtime`'s `org.synanton.gpu.*`; note the platform side uses `org.synanton.*`).

### Hexagonal boundaries enforced in the new repo

`domain/` MUST NOT import: protobuf classes, JDBC/Spring, S3 SDK, OpenDataLoader, Tika, or any adapter package. The domain crosses only these interfaces:

| Port | Direction | Purpose |
|---|---|---|
| `OperationRepository` | out | Operation + item state persistence |
| `IdempotencyStore` | out | Key → operation binding, fail-closed |
| `SourceObjectReader` | out | Read source bytes by `ObjectReference` |
| `PayloadWriter` | out | Persist `StructuredPayload` content, return `PayloadReference` |
| `ModalityAdapter` | out | Media type → structured payload |
| `ExtractionRouter` | in-domain | Select adapter by media type + capability tags |
| `AdmissionController` | in-domain | Capacity + quota decisions |

---

## Phased Delivery

Phases follow §31 of the proposal (Contract → Embedded → Async → External → Scaling), with the multimodal stubs of the draft folded in. **SCEP-1 through SCEP-4 are the v1.21 critical path.** SCEP-5 and SCEP-6 are post-v1.21.

| Phase | Name | Repo(s) | Status |
|-------|------|---------|--------|
| SCEP-1 | Contract | both | Done |
| SCEP-2 | Extraction plane skeleton + sync path | `content_extractor` | ExtractSync PoC |
| SCEP-3 | PDF PoC (OpenDataLoader) | `content_extractor` | HTTP adapter; incomplete feature-state |
| SCEP-4 | Async operation model | `content_extractor` | Planned |
| SCEP-5 | Platform integration | `platform` | Partial (synflux ExtractSync + semantic chunks) |
| SCEP-6 | Topology equivalence + hardening | both | Planned |
| SCEP-7 | Multimodal expansion (audio/image/video) | `content_extractor` | Post-v1.21 |

---

## Changes in `synanton/platform` (this repo)

### New modules

| Module | Type | Purpose |
|---|---|---|
| `java/extraction-contract` | library | Mirror of `synanton.extraction.v1` proto + generated stubs. No platform internals. |
| `java/extraction-client` | library | `ExtractionPlaneClient` (gRPC), retry/reconcile logic, `ExtractionFallbackPolicy`, payload readers. |

`java/extraction-contract` follows `java/gpu-contract`'s build file exactly (protobuf + grpc plugins, no Spring). `java/extraction-client` follows the existing gRPC client wiring in `java/gateway/src/main/java/org/synanton/gateway/gpu/` (`GpuExecutionClient` + `GpuExecutionClientProperties`).

### Modified modules

| Module | Change |
|---|---|
| `java/synflux` | `ParseStage` is re-pointed at the extraction plane. It currently calls Tika in-process (`Tika.parseToString`, `ParseStage.java:29`) and returns `ParsedDocument(acquired, text, metadata)`. It becomes a contract client, with the in-process Tika path retained as the configured fallback. |
| `java/synflux` | New `StructuredDocument` record carries the structured payload alongside `flattenedText`, so `ChunkStage` can chunk on structure (headings/blocks) rather than a flat string. `ParsedDocument` is kept and derived from it for compatibility. |
| `java/synvault` | `ObjectReference` (bucket/key/version/sha256/size) introduced next to `ContentRef`; `ObjectStorePort` gains the presigned-read path the extraction plane uses to fetch source bytes. |
| `settings.gradle.kts` | Include the two new modules. |

### Deliberately unchanged

- `syntology`, `relix`, `planner`, `gateway`, `synquest` — extraction sits upstream of them.
- `java/gpu-contract`, `java/gpu-gateway` — the extraction plane MUST NOT reach the GPU plane *through* the platform. If extraction wants GPU, that is its own internal decision behind its own boundary.

---

## The Contract — `synanton.extraction.v1`

Two proto files under `synanton/extraction/v1/`, following `.cursor/rules/proto-rules.mdc`: `lower_snake_case` fields, `Timestamp` with `_at` suffix, `*_UNSPECIFIED = 0` on every enum, PGV rules on every request field, cursor pagination.

### `extraction_service.proto`

```protobuf
service ExtractionService {
  // Async, first-class. Returns an operation handle immediately.
  rpc SubmitExtraction(SubmitExtractionRequest) returns (ExtractionOperation);

  // Batch: one operation, many items, one business action.
  rpc SubmitExtractionBatch(SubmitExtractionBatchRequest) returns (ExtractionOperation);

  // Sync convenience for small content. Same semantics, same result model.
  rpc ExtractSync(SubmitExtractionRequest) returns (ExtractionResult);

  // Authoritative. Always safe after a timeout or disconnect.
  rpc GetOperations(GetOperationsRequest) returns (GetOperationsResponse);

  // Cursor completion polling for high-throughput consumers.
  rpc ListCompletedOperations(ListCompletedOperationsRequest)
      returns (ListCompletedOperationsResponse);

  rpc GetResult(GetResultRequest) returns (ExtractionResult);
  rpc CancelOperation(CancelOperationRequest) returns (ExtractionOperation);

  // Advisory only. Does NOT reserve capacity.
  rpc GetCapacity(GetCapacityRequest) returns (CapacityResponse);

  // Optional pre-flight estimate. Advisory.
  rpc EstimateExtraction(SubmitExtractionRequest) returns (ExtractionEstimate);

  rpc GetCapabilities(GetCapabilitiesRequest) returns (ExtractionCapabilities);
}
```

Key messages, with the proposal section each satisfies:

| Message | Fields | §ref |
|---|---|---|
| `ObjectReference` | `bucket`, `key`, `version`, `sha256`, `size_bytes` | §5 |
| `ExtractionRequestItem` | `content_ref_id`, `source`, `media_type`, `options`, `metadata`, `routing_tags`, `business_tags` | §6, §7 |
| `ExtractionOptions` | `ocr`, `transcription`, `layout`, `tables`, `embedded_images`, `scene_analysis`, `language`, `preflight` — each `optional bool` so *unset* ≠ *false* | §8 |
| `SubmitExtractionRequest` | item + `tenant_id`, `idempotency_key`, `priority_class`, `expires_at` | §9, §12, §13 |
| `ExtractionOperation` | `operation_id`, `status`, `progress`, `created_at`, `expires_at`, `items[]` | §14, §16 |
| `ExtractionResult` | `content_ref_id`, `payload`, `flattened_text`, `feature_states`, `provenance`, `error` | §20, §21, §25 |
| `StructuredPayload` | `descriptor`, `content` (inline bytes **or** `PayloadReference`) | §20 |
| `PayloadDescriptor` | `schema_id`, `schema_version`, `processor_id`, `processor_version`, `format`, `schema_digest`, `payload_digest` | §20, §25 |

### Enums

- `ExtractionStatus`: `ACCEPTED`, `QUEUED`, `RUNNING`, `COMPLETED`, `PARTIAL`, `FAILED`, `CANCELLED`, `EXPIRED` (§15)
- `PriorityClass`: `LOW`, `NORMAL`, `HIGH`, `CRITICAL` (§9) — no numeric priority, no queue names
- `FeatureState`: `REQUESTED`, `APPLIED`, `NOT_REQUESTED`, `NOT_APPLICABLE`, `UNSUPPORTED`, `FAILED`, `PARTIAL` (draft §6)
- `ExtractionErrorCode`: the 13 codes of §24 — `INVALID_REQUEST`, `INVALID_OBJECT_REFERENCE`, `OBJECT_NOT_FOUND`, `OBJECT_CHANGED`, `UNSUPPORTED_MEDIA_TYPE`, `UNSUPPORTED_OPTION`, `REJECTED_CAPACITY`, `EXPIRED`, `TIMEOUT`, `EXTRACTION_FAILED`, `PARTIAL_EXTRACTION`, `PAYLOAD_INVALID`, `INTERNAL_ERROR`
- `CapacityLevel`: `AVAILABLE`, `LIMITED`, `SATURATED` (§11); admission verdict `ACCEPTED` / `REJECTED_CAPACITY` / `DEFERRED`

### `extraction_payload.proto`

Normalized payload schemas, versioned independently of the service (§20: processor version and schema version are independent). v1.21 defines `DocumentPayload` concretely and reserves the other modalities:

```protobuf
message DocumentPayload {
  string media_type = 1;
  map<string, string> metadata = 2;
  repeated DocumentElement elements = 3;   // reading order preserved
  string flattened_text = 4;
}

message DocumentElement {
  string id = 1;                    // "p1-e07"
  DocumentElementType type = 2;     // PARAGRAPH, HEADING, LIST, LIST_ITEM,
                                    // TABLE, TABLE_CELL, IMAGE, FORMULA, CAPTION
  ElementLocation location = 3;     // page + bbox
  string text = 4;
  int32 heading_level = 5;
  TableStructure table = 6;
  ImageDetail image = 7;
  string formula_latex = 8;
  repeated string child_ids = 9;
}
```

`AudioPayload`, `ImagePayload`, and `VideoPayload` are declared with their draft-defined shape (timeline segments with pause/overlap, OCR + description with provenance, scene + clip structure) but are not implemented until SCEP-7. Declaring them now keeps SCEP-7 additive rather than breaking.

**Provenance rule (draft §24, §30):** any LLM/VLM-generated text — image descriptions, clip summaries, conversation summaries — carries `provenance: GENERATED` plus the generating model id, and MUST NOT be presented as source evidence. Extracted text carries `provenance: SOURCE`; OCR carries `provenance: OCR`.

---

## Phase SCEP-1 — Contract

**Goal:** the complete contract exists, validates, and is provably identical in both repositories, before any service implementation.

### Deliverables

| # | Deliverable | Repo |
|---|---|---|
| 1 | `extraction_service.proto` + `extraction_payload.proto` | both (mirrored) |
| 2 | PGV rules on every request field | both |
| 3 | 13-code error catalogue in proto + a doc table mapping each code to caller action and retryability | both |
| 4 | `java/extraction-contract` module wired into `settings.gradle.kts` | `platform` |
| 5 | `java/extraction-contract` module wired into `settings.gradle.kts` | `content_extractor` |
| 6 | Proto-drift CI check (fails when the two copies diverge) | both |
| 7 | Consumer-driven contract test against an in-process mock server | `platform` |

### Definition of Done

1. `./gradlew :java:extraction-contract:generateProto` succeeds in both repos.
2. PGV rejects: missing `idempotency_key`, missing `tenant_id`, `tenant_id` outside `^[a-zA-Z0-9_-]{1,64}$`, empty `bucket`/`key`, `size_bytes <= 0`, malformed `sha256`, `PRIORITY_CLASS_UNSPECIFIED`, batch with zero items, `page_size` outside 1..1000.
3. All 13 error codes present and documented.
4. A checksum comparison of the two proto directories is green in CI and blocks merge on divergence.
5. Contract tests in `platform` compile and pass against the mock, asserting: submit → operation handle; poll → status; result → payload + `flattened_text` + feature states.
6. No proto message references OpenDataLoader, Tika, a worker pool, a queue, or a device.

---

## Phase SCEP-2 — Extraction Plane Skeleton + Sync Path

**Goal:** a running service that satisfies the contract end-to-end for one trivial media type, with the domain fully isolated from adapters. Sync first, because it proves the result model without the operation store.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `content_extractor` repo scaffolding: root build, version catalog, wrapper, LICENSE, README, CI workflow, `.cursor/rules/` |
| 2 | `extraction-gateway` Spring Boot app + gRPC server lifecycle (mirrors `GrpcServerLifecycle` from `gpu-runtime`) |
| 3 | `extraction-spi`: `ModalityAdapter`, `AdapterCapabilities`, `NormalizedDocument`, `FeatureStateMap` |
| 4 | `ExtractionRouter` — media type + routing tags → adapter; `UNSUPPORTED_MEDIA_TYPE` when none matches |
| 5 | `adapter-document-text`: `text/plain` via a direct reader; EPUB and HTML via Tika |
| 6 | `SourceObjectReader` (S3/MinIO) with size cap enforced *before* download |
| 7 | `ExtractSync` implemented: read → route → adapt → normalize → digest → return |
| 8 | Sandbox limits: max object size, max wall-clock per operation, max output payload size |
| 9 | Prometheus metrics from §29 |

### Definition of Done

1. `./gradlew build` green in `content_extractor`.
2. `ExtractSync` on a `text/plain` object returns a `DocumentPayload` with elements, a `flattened_text` equal to the source text, and `feature_states` reporting `text=APPLIED`, `ocr=NOT_APPLICABLE`.
3. `ExtractSync` on `application/zip` returns `UNSUPPORTED_MEDIA_TYPE` without downloading the body.
4. An object exceeding the size cap is rejected pre-download.
5. A deliberately hanging adapter is killed at the time limit and yields `TIMEOUT`, not a hung RPC.
6. An ArchUnit (or equivalent) test fails the build if `domain/` imports protobuf, JDBC, Spring, Tika, or an adapter package.
7. `payload_digest` recomputed by the caller matches the descriptor.
8. Source object bytes are unchanged after extraction (checksum before/after).

---

## Phase SCEP-3 — PDF PoC (OpenDataLoader)

**Goal:** satisfy all 15 PoC acceptance criteria from draft §39 behind the adapter boundary.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Resolve the OpenDataLoader Java artifact coordinates and pin them in the version catalog — see the open question below |
| 2 | `adapter-document-pdf`: invoke OpenDataLoader, request JSON output |
| 3 | `OpenDataLoaderJsonNormalizer`: raw JSON → `DocumentElement` list, reading order preserved |
| 4 | Bounding box + page provenance carried onto every element |
| 5 | Heading hierarchy, lists, tables, image references, formula LaTeX mapped to the normalized model |
| 6 | Option → OpenDataLoader flag mapping for `ocr`, `tables`, `embedded_images`, `language` |
| 7 | Feature-state computation from what the processor actually produced — never from what was requested |
| 8 | `flattened_text` generated from the element tree, never by reparsing the PDF |
| 9 | Markdown projection (`text/markdown`) as an alternate serialization of the same payload |
| 10 | Fixture PDFs: text-only, scanned (OCR path), tables, formulas, mixed |

### Definition of Done

1. The 15 criteria of draft §39 each have a named passing test.
2. A scanned PDF with `ocr` unset reports `ocr=NOT_APPLICABLE` or `NOT_REQUESTED` and does **not** silently OCR.
3. The same scanned PDF with `ocr=true` reports `ocr=APPLIED` and returns OCR text with `provenance=OCR`.
4. Requesting `scene_analysis` on a PDF reports `UNSUPPORTED` — the operation still succeeds for the features that do apply.
5. When OCR is requested and the OCR step fails but text extraction succeeds, status is `PARTIAL` with `ocr=FAILED`, not `FAILED`.
6. Every element carries a page number and a 4-element bbox.
7. `flattened_text` is derived from elements — verified by mutating the element list in a test and observing the projection change.
8. No OpenDataLoader type appears in `extraction-contract`, `extraction-spi`, or `domain/` (enforced by an import test).
9. A grep for `opendataloader` in the `platform` repo returns nothing.

---

## Phase SCEP-4 — Async Operation Model

**Goal:** operations become durable, idempotent, pollable, expirable, cancellable, and batchable. This is where PostgreSQL becomes authoritative.

### Schema (Flyway, `content_extractor`)

```text
extraction_operations
  operation_id (PK), tenant_id, status, progress,
  priority_class, created_at, updated_at, expires_at,
  completion_seq (BIGSERIAL, set on terminal transition)

extraction_operation_items
  operation_id (FK), item_index, content_ref_id, media_type,
  source_bucket, source_key, source_version, source_sha256, source_size,
  status, progress, feature_states (jsonb), error_code, error_detail,
  payload_descriptor (jsonb), payload_ref

extraction_idempotency
  idempotency_key (PK), tenant_id, request_hash, operation_id, created_at
```

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Flyway migrations for the three tables |
| 2 | `JdbcOperationRepository`, `JdbcIdempotencyStore` (fail-closed, mirroring `JdbcIdempotencyStore` in `gpu-runtime`) |
| 3 | `SubmitExtraction` / `SubmitExtractionBatch` with admission inside one transaction under a tenant-level `pg_advisory_xact_lock` |
| 4 | Worker pool draining `QUEUED` → `RUNNING` → terminal, with per-item state transitions |
| 5 | `OperationState.canTransitionTo(next)` guarding every write |
| 6 | `GetOperations` (by ids) and `ListCompletedOperations` (cursor over `completion_seq`) |
| 7 | Expiration semantics per §12: pre-execution → `EXPIRED` unstarted; queued → removed, `EXPIRED`; running → may finish, status distinguishes `COMPLETED` / `EXPIRED` / `CANCELLED` |
| 8 | `CancelOperation` (best-effort) and `GetCapacity` (`AVAILABLE`/`LIMITED`/`SATURATED`) |
| 9 | `EstimateExtraction` — advisory, no reservation |
| 10 | Batch progress: operation progress derived from item progress |

### Definition of Done

1. Same `idempotency_key` + same request submitted 100× concurrently ⇒ exactly one row in `extraction_operations`.
2. Same key + materially different request ⇒ `INVALID_REQUEST` naming the conflict; the original operation is untouched.
3. Two concurrent submits cannot exceed the tenant concurrency limit (advisory-lock test, modeled on the GPU plane's admission race test).
4. `expires_at` in the past at submit ⇒ `EXPIRED`, and no adapter is ever invoked (verified with a spy).
5. An operation expiring while `QUEUED` reaches `EXPIRED` and is never dispatched.
6. An operation expiring while `RUNNING` reports `COMPLETED` or `EXPIRED` — never `FAILED`.
7. A killed and restarted gateway resumes: no operation is stuck in `RUNNING` without a lease, no work is silently lost.
8. Cursor polling returns each completed operation exactly once, in `completion_seq` order, across pagination.
9. A batch of 3 items where 1 fails ⇒ operation status `PARTIAL`, per-item statuses correct.
10. `0.0 <= progress <= 1.0` holds at every observed transition.
11. No Redis, Kafka, or Cassandra dependency appears in any module.

---

## Phase SCEP-5 — Platform Integration

**Goal:** `synflux` consumes the extraction plane, and behaves correctly when the plane is absent.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `java/extraction-client`: `ExtractionPlaneClient`, `ExtractionClientProperties`, channel + deadline config |
| 2 | Reconcile-after-timeout logic: a timed-out submit is resolved by polling, never by blind resubmission |
| 3 | `ExtractionFallbackPolicy`: `STRUCTURED_REQUIRED` \| `FALLBACK_LOCAL_TIKA` \| `FAIL_OPEN_TEXT_ONLY`, per tenant, defaults in `application.yml` |
| 4 | `StructuredDocument` domain record + `ParsedDocument` derived from it |
| 5 | `ParseStage` rewritten to call the plane; existing Tika code moves to `LocalTikaFallbackExtractor` |
| 6 | `ChunkStage` able to chunk on structure (heading/block boundaries) with the flat-text path retained |
| 7 | `ObjectReference` in `synvault` + presigned-read path so the plane fetches bytes directly, not through the platform |
| 8 | Platform-side metrics: `extraction_client_requests_total`, `..._fallback_total`, `..._latency` |

### Definition of Done

1. An ingestion job over the `demo-data` PDFs completes through the extraction plane, and chunks carry page provenance.
2. With the plane stopped and policy `FALLBACK_LOCAL_TIKA`, ingestion still completes; `extraction_client_fallback_total` increments; a warning names the fallback.
3. With policy `STRUCTURED_REQUIRED` and the plane stopped, the job fails with a clear error and does **not** write partial chunks.
4. A submit that times out client-side is reconciled by polling — asserted by a test where the server delays the response and the client observes exactly one server-side operation.
5. Document bytes never transit the gRPC contract: the plane reads from object storage. Asserted by a test that the request carries only a reference.
6. A grep for `opendataloader` and for any worker/pool/device identifier in `platform` returns nothing.
7. `./gradlew build` green in `platform`.

---

## Phase SCEP-6 — Topology Equivalence + Hardening

**Goal:** prove invariant #1 empirically — the same request produces equivalent semantics embedded and remote. This is the gate on calling v1.21 complete (§32.1, and decision item 22).

### Deliverables

| # | Deliverable |
|---|---|
| 1 | An embedded run mode: the same adapters and domain invoked in-process, behind the same contract types |
| 2 | Topology-equivalence test suite executing one fixture corpus against both modes |
| 3 | Security hardening: parser sandboxing, output caps, tenant isolation on source access, option validation |
| 4 | Operational docs: `TEST_ENVIRONMENT_SETUP.md`, runbook, capacity guidance |
| 5 | `docs/architecture/synanton-design-1.21.md` Part IX (§65–§79) folded in from the proposal |
| 6 | Decision record `docs/proposals/v1.21/decision.md` |

### Definition of Done

1. For every fixture, embedded and remote runs agree on: element count and order, element types, feature states, error codes, and payload descriptor `schema_id`/`schema_version`. Only diagnostics and timings may differ.
2. A malicious fixture set (PDF bomb, deeply nested XML, huge embedded image, malformed EPUB) yields a contract error code — never a hang, OOM, or crash of the gateway.
3. No source-supplied code executes: verified with a fixture carrying embedded JavaScript.
4. Tenant A cannot reference tenant B's object, even with a valid bucket/key.
5. Prompts, document text, and transcript content never appear in logs — only ids, media types, states, and error codes.
6. §32.2–§32.9 validation items each map to a passing test.
7. Part IX exists in the design doc and the decision record is filed.

---

## Phase SCEP-7 — Multimodal Expansion (post-v1.21)

Additive only; no contract change. Audio (ASR + diarization + pause/overlap timeline), image (OCR + VLM description with provenance separation), video (demux → scene detection → frame/clip extraction, delegating to the audio and image adapters).

Until then, `adapter-stubs` declines these media types with `UNSUPPORTED_MEDIA_TYPE`, and requesting `transcription` or `scene_analysis` on a document yields `UNSUPPORTED` rather than silence. That is itself a v1.21 test.

---

## Cross-Cutting Rules for Both Repos

1. **Never** put a tunable default in Java (`@Value("${k:DEFAULT}")` is banned). Defaults live in `application.yml`.
2. Config keys are module-prefixed: `extraction-gateway.*` in the plane, `synanton.extraction.client.*` in the platform.
3. New config keys ship with a documented default and an env-var override.
4. Log only: `operation_id`, `content_ref_id`, `tenant_id`, `media_type`, state transitions, error codes, durations. Never document text, transcripts, OCR output, or descriptions.
5. Every plan phase has a numbered, testable Definition of Done that maps back to this INDEX.
6. Proto changes must pass a breaking-change check against the SCEP-1 baseline before merge.
7. Unit tests are `{ClassName}Test`, test names start with `should`, integration tests live in `integration/` and use single-object comparison.

---

## Open Question — OpenDataLoader Coordinates

Maven Central shows the PDF core library under two candidate groupIds: `io.github.opendataloader-project:opendataloader-pdf-core` and `org.opendataloader:opendataloader-pdf-core` (2.1.1 observed). Which is current — and whether the Java library covers the OCR, formula, and image-description features, or whether those require the CLI or hybrid mode — is the first task of SCEP-3, not an assumption baked into this plan.

This does not block SCEP-1 or SCEP-2. If the Java library turns out to cover less than draft §38 claims, the adapter boundary is exactly the place that absorbs it: the affected features report `UNSUPPORTED` through the normal feature-state mechanism, and the contract does not change.

---

## Sequencing and Parallelism

```text
SCEP-1 (contract, both repos)
   |
   +----------------------------+
   |                            |
SCEP-2 (plane skeleton)    SCEP-5a (client module, mock-backed)
   |                            |
SCEP-3 (PDF PoC)                |
   |                            |
SCEP-4 (async + Postgres)       |
   |                            |
   +----------------------------+
                |
        SCEP-5b (synflux integration)
                |
        SCEP-6 (equivalence + hardening)
                |
        SCEP-7 (multimodal, post-v1.21)
```

SCEP-1 is the hard serialization point. After it, the platform client can be built against the mock in parallel with the plane itself — the same split that let `gpu-contract` and `gpu-gateway` proceed independently.

---

## Configuration Keys

### `content_extractor` — `extraction-gateway.*`

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `extraction-gateway.grpc-port` | `EXTRACTION_GATEWAY_GRPC_PORT` | `9091` | gRPC listen port |
| `extraction-gateway.worker.pool-size` | `EXTRACTION_WORKER_POOL_SIZE` | `4` | In-process worker threads |
| `extraction-gateway.worker.claim-batch-size` | `EXTRACTION_CLAIM_BATCH_SIZE` | `8` | Items claimed per poll |
| `extraction-gateway.lease.timeout-seconds` | `EXTRACTION_LEASE_TIMEOUT_SECONDS` | `600` | Item lease window |
| `extraction-gateway.limits.max-object-bytes` | `EXTRACTION_MAX_OBJECT_BYTES` | `268435456` | Reject oversized sources (§27) |
| `extraction-gateway.limits.max-duration-seconds` | `EXTRACTION_MAX_DURATION_SECONDS` | `900` | Per-item processing ceiling |
| `extraction-gateway.limits.max-payload-bytes` | `EXTRACTION_MAX_PAYLOAD_BYTES` | `67108864` | Output size ceiling |
| `extraction-gateway.limits.inline-payload-threshold-bytes` | `EXTRACTION_INLINE_PAYLOAD_THRESHOLD` | `1048576` | Above this, payload goes to object storage as `PayloadReference` |
| `extraction-gateway.admission.max-concurrent-per-tenant` | `EXTRACTION_MAX_CONCURRENT_PER_TENANT` | `16` | Tenant fairness (§28) |
| `extraction-gateway.admission.queue-depth-limit` | `EXTRACTION_QUEUE_DEPTH_LIMIT` | `1000` | Beyond this → `REJECTED_CAPACITY` |
| `extraction-gateway.objectstore.endpoint` | `EXTRACTION_OBJECTSTORE_ENDPOINT` | `http://minio:9000` | Source + payload storage |
| `extraction-gateway.retention.result-ttl-hours` | `EXTRACTION_RESULT_TTL_HOURS` | `168` | Result retention |

### `platform` — `synanton.extraction.client.*`

| Property | Env var | Default | Purpose |
|---|---|---|---|
| `synanton.extraction.client.enabled` | `EXTRACTION_CLIENT_ENABLED` | `false` | Off until SCEP-5 lands; `false` keeps in-process Tika |
| `synanton.extraction.client.endpoint` | `EXTRACTION_ENDPOINT` | `extraction-gateway:9091` | Plane address |
| `synanton.extraction.client.mode` | `EXTRACTION_CLIENT_MODE` | `sync` | `sync` or `async` |
| `synanton.extraction.client.deadline-seconds` | `EXTRACTION_DEADLINE_SECONDS` | `120` | Sync call deadline |
| `synanton.extraction.client.poll-interval-seconds` | `EXTRACTION_POLL_INTERVAL_SECONDS` | `5` | Async status polling |
| `synanton.extraction.client.fallback` | `EXTRACTION_FALLBACK` | `local-tika` | `local-tika`, `partial`, or `fail` (§21) |
| `synanton.extraction.client.default-priority` | `EXTRACTION_DEFAULT_PRIORITY` | `NORMAL` | Priority class for ingestion work |

---

## Observability

Metrics exposed at the contract level (§29), all dimensioned by `media_type`, `processor_id`, `tenant_id`, `status`:

```text
extraction_requests_total
extraction_operations_total
extraction_completed_total
extraction_failed_total
extraction_expired_total
extraction_duration_seconds
extraction_queue_delay_seconds
extraction_payload_bytes
extraction_fallback_total
```

`extraction_fallback_total` is the one the platform owns — it counts how often the plane was unavailable or incompatible and the platform fell back. A rising value means the plane is failing without anyone noticing, because ingestion still "succeeds."

Cardinality guard: `processor_id` is a bounded adapter identifier, never a version string or worker identity.

---

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| OpenDataLoader Java library covers less than draft §38 claims | PDF PoC delivers fewer features than the matrix promises | Feature state reports `UNSUPPORTED` honestly; contract unchanged; resolved in SCEP-3 task 1 |
| Structured payload grows large for long documents | Memory pressure, gRPC message limits | `inline-payload-threshold-bytes` moves payloads to object storage as `PayloadReference` |
| `ChunkStage` rework on structured elements changes existing chunk boundaries | Re-embedding of already-ingested corpora | Structure-aware chunking lands behind a flag; flat-text chunking stays the default until measured |
| Sync path becomes the de-facto production path | Long extractions block callers, invariant §19 eroded | Sync path enforces its own size/duration ceiling and rejects above it, pointing callers at async |
| Two implementations drift (sync vs async) | §19 violation — a second result model | Sync is implemented as *submit + await* over the same domain service; no separate code path (verified in SCEP-2 DoD) |
| Extraction plane reaches for the GPU plane through the platform | Topology leak, circular dependency | `content_extractor` has no dependency on `synanton.gpu.v1`; enforced by `.cursor/rules/extraction-rules.mdc` and a dependency check |
| Proto mirror divergence between repos | Silent incompatibility | CI diff check in both repos, as with `synanton.gpu.v1` |

---

## How to Contribute

1. Plan changes land here first; phase-level detail lives in `content_extractor/doc/`.
2. A phase is not done until its numbered Definition of Done is fully satisfied — partial completion is reported as partial.
3. Contract changes (SCEP-1 artifacts) require the mirror update in both repos in the same change set.
4. New config keys are added to the tables above in the same change that introduces them.
5. Any new invariant goes into `.cursor/rules/extraction-rules.mdc`, not just prose here.

---

## References

1. `docs/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane.md` — contract proposal (§1–§35)
2. `docs/proposals/v1.21/Synanton_v1.21_Structured_content_extraction_plane_draft.md` — multimodal design and PDF PoC (§1–§49)
3. `docs/implementation/gpu-execution-plane/INDEX.md` — the plan this one is patterned after
4. `docs/architecture/synanton-design-1.20.md` — GPU Execution Plane precedent for a contract-bounded plane
5. [OpenDataLoader PDF](https://github.com/opendataloader-project/opendataloader-pdf) — PoC processor
6. [`opendataloader-pdf-core` on Maven Central](https://central.sonatype.com/artifact/org.opendataloader/opendataloader-pdf-core/2.1.1) — coordinate candidate, to be confirmed in SCEP-3

