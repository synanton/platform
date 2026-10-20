# Platform #328 — Tenant-scoped document folder uploads through the shared ingestion contract

**Status:** Plan (not started)
**Ticket:** Platform #328
**Repo:** `synanton/platform`
**Related:** Lucentrix #16 (`lucentrix` — filesystem connector); recommended order #16 → #328 → integration

## Goal

Add a browser folder-upload path: a tenant selects a folder of documents in the
web UI, the platform ingests each file through the **existing** processing
pipeline with per-file progress and retries, provenance, idempotency, and tenant
isolation — using the **shared ingestion contract**, not a second pipeline.

The contract already exists and is frozen: `docs/api/source-model.md` plus the
`/v1/sources`, `/v1/sources/{id}/versions`, `/v1/ingestion-runs`,
`/v1/operations` surface already implemented in `synflux` (see below). #328 is
about wiring the *manual/browser upload* entry point into it.

## What "the shared ingestion contract" means here

Design 1.28 §48 (Manual upload) is explicit: manual upload is a
connector-independent ingestion source and **must use the same contracts as
automated sources**, with no special security model. The normative pieces:

| Piece | Location | Status |
|---|---|---|
| Source identity `{tenant_id, connector_id, source_id, resource_id}` | `docs/api/source-model.md` §Source identity | Frozen |
| `SourceVersion {version_id, content_digest, content_cache_reference, source_revision, observed_at}` | `docs/api/source-model.md` §Source version | Frozen |
| Ingestion run + `SyncResult` + manifest | `docs/api/source-model.md` | Frozen |
| `POST/GET/PATCH/DELETE /v1/sources`, `DELETE /v1/sources/{id}/resources`, `GET .../versions` | `java/synflux/.../api/SourceController.java` | Implemented |
| `POST/GET /v1/ingestion-runs`, `POST .../cancel` | `java/synflux/.../api/IngestionRunController.java` | Implemented |
| `GET /v1/operations/{id}`, `POST .../cancel` (Operation state machine) | `java/synflux/.../api/OperationController.java` | Implemented |
| Content byte push → `{content_ref_id, sha256, size_bytes}` | `java/synvault/.../ContentPushPort.java`, `ContentPushController.java` | Implemented |
| Pipeline (acquire → extract → chunk → persist → knowledge) | `java/synflux/.../lifecycle/FixtureSourceWorkflow.java` | Implemented |

## Verified current state / gaps

- `SourceController.create` currently accepts **only `connector_type == "file"`**
  and rejects everything else. A browser upload is not a filesystem source — it
  is a manual/upload source where the *client* supplies bytes, not a server-side
  path. **A new connector type (or an explicit "upload" variant) is required.**
- `/v1/ingestion-runs` accepts **one `resource_id` per run** (one file). A folder
  of N files is therefore N runs (or one run with a manifest extension) — the
  aggregation of "per-file progress" onto a single user-facing upload must be
  defined.
- There is **no byte-upload endpoint** on the public surface today. The only
  byte entry is the internal `synvault POST /content/{tenant}` (used by the
  Lucentrix legacy sink; its removal is tracked by #329). The public path needs
  a way to write bytes and obtain a content reference under verified tenant
  scope.
- **Content dedup is per-tenant, not global.** `content_ref_id = UUIDv5(tenant +
  ":" + sha256)` (`ContentPushPort`) means the same bytes under two tenants get
  two content refs. This is correct for isolation and must not be "optimized"
  into a shared blob store later.
- Tenant isolation is already enforced by the shared `TenantContextFilter` +
  `TrustedRequestContext` (tenant from verified JWT only; body/query
  `X-Tenant*` ignored — see `source-lifecycle-fixture.md` §Integration boundary).
  #328 must reuse it, never add a fallback tenant.
- Provenance/idempotency already exist in the source model: per-file
  `SourceIdentity` + immutable `SourceVersion` (dedup by `tenant_id + source_id +
  resource_id + source_version`) and `Idempotency-Key` (scoped by tenant +
  principal + endpoint).

## Design decisions to confirm

1. **Connector model for uploads — identity mapping matches #16 (not optional).**
   The upload connector's identity mapping matches Lucentrix #16's:
   `connector_id = "upload"`, `source_id = upload session id`,
   `resource_id = path relative to the uploaded folder`, normalized identically
   to #16's filesystem paths. This is not optional — the integration test
   (slice 5) depends on it. `source_endpoint` is not a filesystem path; it is a
   batch/session identifier or absent. This keeps the source/resource/version
   model intact and prevents the two paths from independently "solving" identity
   and diverging.
2. **Byte entry point — inline bytes (decided).** `POST /v1/ingestion-runs` with
   `Content-Type: multipart/form-data`, not a new endpoint. Reuses the Operation
   contract (same response/polling), one auth surface (the existing JWT on
   `/v1/...`), one idempotency surface (the existing `Idempotency-Key` from
   #248), and avoids a new resource type the frozen 1.32 contract does not have.
   The Operation is created **before** bytes are received so upload progress and
   Operation progress are the same signal — a failed upload leaves an Operation
   in `FAILED`, not a dangling session. Presigned-URL passthrough is the standard
   follow-up if a single upload exceeds body limits; it is not the initial
   design. **Each part must carry its media type** (`application/pdf`,
   `application/epub+zip`): the legacy path discarded it (`PushedContentAdapter`
   hardcoded `application/octet-stream`) and the extractor rejected the result
   (`ERROR_UNSUPPORTED_MEDIA_TYPE`, platform #331). Do not repeat that on
   `/v1/...`.
3. **Per-file progress — client-declared manifest.** The client declares the
   upload manifest (file count, total bytes) before bytes are sent.
   `Operation.progress.total` = declared file count from the start, incremented
   as files are **admitted**; `SyncResult` counters report admitted→succeeded.
   The upload-completion barrier ("all files sent") is a separate client-side
   signal, not an Operation state. This keeps the progress bar honest: upload
   progress first, then processing progress — using `Operation.progress` for both
   would show 0% during a long upload then jump to 100%. Do **not** invent a new
   progress shape (invariant 3 in `platform-api-plane/INDEX.md`: resource and
   Operation state remain distinct).
4. **Retries.** Per-file retry reuses the existing durable workflow recovery
   (#250) and `Idempotency-Key`; retryable vs non-retryable per Design 1.28 §41.
5. **Deletion/overwrite.** Uploading the same folder again must converge
   (unchanged files → `UNCHANGED`, changed → new version, removed files → `DELETE`
   only when the upload is authoritative/complete). Conservative semantics per
   `source-model.md` §Deletion.
6. **Tags.** Tags are manual-assigned in the UI for M1, **not** derived from
   directory/file names. Neither Lucentrix's field vocabulary nor the frozen
   source model has a tags field; do not let the first implementation invent a
   directory-to-tag mapping.

## Sliced implementation

| Slice | Deliverable | Acceptance |
|---|---|---|
| 1 | `manual-upload` connector type + source creation path (accept a folder/batch as one `source_id`) | `POST /v1/sources` with `connector_type=manual-upload` succeeds; tenant from verified context |
| 2 | Inline byte entry: `POST /v1/ingestion-runs` multipart under tenant scope; Operation created before bytes received | A file is written and resolvable; no tenant fallback; a failed upload leaves `FAILED`, not a dangling session |
| 3 | Per-file ingestion runs wired through the existing pipeline | Uploaded PDF/EPUB is chunked and searchable; `SyncResult`/manifest records the per-file result |
| 4 | Client-declared manifest + per-file progress/retry/idempotency on the Operation | N-file upload reports `progress{completed,total}` = declared file count; duplicate re-upload converges (`UNCHANGED`); a failed file retries without re-uploading the batch |
| 5 | Integration with Lucentrix #16 | A filesystem crawl (#16) and a browser upload (#328) produce searchable documents through the **same** pipeline; no two-pipeline divergence |

## Acceptance criteria (summary)

1. A tenant-scoped browser folder upload ingests every file through the same
   acquire → extract → chunk → persist pipeline as any other source.
2. Progress, retries, provenance, and idempotency all conform to the frozen
   `source-model.md` / `operation-model.md` shapes — no new state machines.
3. Tenant isolation holds: tenant is derived only from verified identity;
   cross-tenant access is impossible by default; no `X-Tenant*` body/header read.
4. `./gradlew build` green; new REST tests follow the repo's
   `method_condition_expected` convention and exercise real production shapes.
5. EPUB/PDF real-file check: one real EPUB and one real PDF through the full
   pipeline yield searchable chunks (not `FEATURE_UNSUPPORTED`) — the EPUB
   adapter is "listed as active", which is a different claim from "extracts".

## Out of scope

- SharePoint / cloud-drive connectors.
- EPUB/PDF *extraction* implementation (discovery/upload only).
- Any change to the `synapt` vs `platform-api` module-home decision (PAPI-0).

## Where this lives

Implementation lands in `java/synflux` (the service already owning
`/v1/sources`, `/v1/ingestion-runs`, `/v1/operations` and the fixture workflow).
If PAPI-5 (ingestion-adapter) is started before this, reconcile against
`docs/implementation/platform-api-plane/INDEX.md` — do not build a parallel
upload surface in `synapt`.
