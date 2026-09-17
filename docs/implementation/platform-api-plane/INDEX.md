---
title: "Platform API Plane - Implementation Plan"
status: "not started"
last_reviewed: "2026-09-15"
---

# Platform API Plane - Implementation Plan

**Purpose:** Implementation plan for Design 1.32 - the stable, versioned, resource-oriented external API surface for the platform, and the public `Operation` resource for long-running work.
**Architecture reference:** `docs/architecture/synanton-design-1.32.md`; [ADR-009](../../architecture/decisions/adr-009-platform-api-contract.md); capstone positioning in `docs/architecture/synanton-platform-architecture-1.0.md` §11, §14, §15.
**Target repository:** `synanton/platform` (this repo).
**Audience:** Architects, module owners, platform engineers, API consumers
**Last Updated:** 2026-09-15

---

## Theme

> The external contract is resource-oriented and versioned. The public `Operation` resource is the contract for asynchronous work - a single client-facing Operation may be backed by an arbitrarily complex internal workflow without that complexity becoming part of the public contract.

---

## User-Facing Capability Unlocked

- One stable, versioned (`/v1/...`) API surface replaces ad-hoc per-module endpoints as the thing external applications and SDKs are written against.
- Long-running work (ingestion runs, AI executions, exports) is tracked through one consistent `Operation` resource shape, regardless of which internal plane is doing the work.
- Errors are consistent and machine-actionable (`code`, `retryable`, `request_id`) across every resource, instead of each module inventing its own error shape.
- The API can evolve without breaking existing clients, via explicit versioning and a documented deprecation process.

---

## Non-Negotiable Invariants

Derived from the capstone doc's API invariants (24-29) and Design 1.32 itself:

1. **Platform API 1.32 is the stable external boundary.** Nothing external talks to internal services directly once this lands.
2. **Internal storage, broker, workflow and runtime topology are not public contracts.** A client never sees a queue name, a workflow engine detail, or a storage backend through this API.
3. **Long-running acceptance means durable Operation state.** Accepting a request for async work implies the Operation resource is already durably recorded, not merely "in flight in memory."
4. **Resource and Operation state remain distinct.** A resource's own state (e.g. a Source's lifecycle) is not conflated with the Operation that mutated it.
5. **Pagination is security-context aware.** A page of results never leaks the existence of resources the caller isn't authorized to see, including through count/facet metadata.
6. **Breaking changes require explicit versioning.** No silent breaking change to a shipped `/v1/` resource.

---

## Known Decision This Plan Must Make First

Design 1.32 is written module-agnostically - it never names `synapt` (the existing "Public REST/gRPC ingress" module) or any other module as its home. `synapt` today (`java/synapt/src/main/java/org/synanton/synapt/`) is a thin ingress: `api/{SearchController, GlobalExceptionHandler, CspReportController, InternalAdminController}`, `filter/{MockTenantFilter, SecurityHeadersFilter, DeprecationWarningFilter}`, `client/GatewayClient`, `config/{SynaptConfig, SynaptProperties}` - no resource model, no `/v1/` versioning, no Operation/idempotency/pagination machinery. This plan recommends a **new `java/platform-api` module** (cleaner separation, matches the naming precedent of other planes' dedicated gateway modules) rather than expanding `synapt`, but treats that as a decision to confirm in PAPI-0, not a foregone conclusion.

---

## Phased Delivery

Phases follow Design 1.32 §178 directly.

| Phase | Name | Status |
|-------|------|--------|
| PAPI-0 | Module-home decision | Not started |
| PAPI-1 | API Foundation | Not started |
| PAPI-2 | Resource APIs | Not started |
| PAPI-3 | Async Operations | Not started |
| PAPI-4 | Search API | Not started |
| PAPI-5 | Ingestion API | Not started |
| PAPI-6 | AI API | Not started |
| PAPI-7 | Governance | Not started |

---

## Changes in `synanton/platform`

### New modules

| Module | Type | Purpose |
|---|---|---|
| `java/platform-api` (pending PAPI-0) | service | The `/v1/...` external surface: resource services, Operation service, error mapper, pagination, idempotency, request validation. Structure per §154 (see below). |

### Modified modules

| Module | Change |
|---|---|
| `java/synapt` | Fate decided in PAPI-0: either absorbed into `java/platform-api` or kept as an internal-only ingress while `platform-api` becomes the external one. Not both indefinitely. |
| `java/synanton-mcp` | Eventually fronts the same resource/Operation model exposed by `platform-api`, rather than its own bespoke tool surface (PAPI-2 onward, not a hard requirement of this plan). |
| `settings.gradle.kts` | Include the new module once PAPI-0 confirms its name. |

### Deliberately unchanged

- No change to internal service-to-service contracts (`synanton.extraction.v1`, `synanton.gpu.v1`, etc.) - `platform-api` is a facade in front of them, not a replacement for them.

---

## The Contract - Core Shapes

Full definitions live in `docs/architecture/synanton-design-1.32.md`; load-bearing shapes reproduced here.

**Resource surface (Appendix A):**
```text
/v1/tenants
/v1/principals
/v1/sources
/v1/sources/{id}/versions
/v1/ingestion-runs
/v1/artifacts
/v1/content
/v1/knowledge
/v1/chunks
/v1/annotations
/v1/search
/v1/ai/executions
/v1/answers
/v1/workflows
/v1/operations
/v1/exports
```

**Operation resource (Appendix B):** `operation_id, type, status, requested_by.principal_id, tenant_id, created_at, started_at, completed_at, progress{completed,total}, error`

**Operation states (§56):** `ACCEPTED, QUEUED, RUNNING, SUCCEEDED, FAILED, CANCEL_REQUESTED, CANCELLED, EXPIRED`

> **Reconciliation flag:** this is close to but not identical with the already-shipped `synanton.extraction.v1` `ExtractionStatus` enum (`ACCEPTED, QUEUED, RUNNING, COMPLETED, PARTIAL, FAILED, CANCELLED, EXPIRED`) - different terminal-success name (`COMPLETED` vs `SUCCEEDED`), an extra `PARTIAL` state, and no `CANCEL_REQUESTED` symmetry. PAPI-3 must define the mapping explicitly (see PAPI-3 below) rather than let a second async model diverge from the first the same way the design's own Motivation section warns against.

**Error model (Appendix C):** `code, message, retryable, request_id, details`
**Error categories (§62):** `VALIDATION_ERROR, AUTHENTICATION_REQUIRED, AUTHORIZATION_DENIED, RESOURCE_NOT_FOUND, RESOURCE_CONFLICT, PRECONDITION_FAILED, RATE_LIMITED, DEPENDENCY_UNAVAILABLE, OPERATION_FAILED, INTERNAL_ERROR`

**Reference component architecture (§154):**
```text
platform-api/
├── api-contract
├── authentication-adapter
├── identity-context
├── authorization-adapter
├── tenant-context
├── resource-services
├── operation-service
├── error-mapper
├── pagination
├── idempotency
├── concurrency
├── request-validation
├── event-adapter
├── workflow-adapter
├── search-adapter
├── ai-adapter
├── ingestion-adapter
├── content-adapter
├── provenance-adapter
└── observability
```

**Dependency map (Appendix H):**
```text
Platform API 1.32
   → Identity 1.29, Security 1.23, Eventing 1.27
      → Ingestion 1.28, Knowledge 1.25, Search 1.31
         → Content Cache 1.26, Provenance, AI Runtime 1.30
```
Per §55's boundary note and ADR-009, PAPI-1 through PAPI-3 (foundation, resource shape, Operation *contract shape*) do not require 1.27 to be built - only later phases' "Eventing 1.27 is integrated" acceptance criterion does.

---

## Phase PAPI-0 - Module-home decision

**Goal:** decide where this API lives before writing `api-contract`/`resource-services`, so PAPI-1 isn't retrofitted into `synapt` or duplicated alongside it.

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Written decision: new `java/platform-api` module vs. expanding `java/synapt` in place - with the actual trade-off (clean separation vs. reusing existing auth/tenant filter code already in `synapt`) recorded. |
| 2 | If new module: plan for `synapt`'s eventual role (retired, internal-only, or merged) - not left ambiguous. |

### Definition of Done

1. A short decision doc (or ADR addendum) is linked from this file before PAPI-1 work is ticketed.

---

## Phase PAPI-1 - API Foundation

**Goal:** the versioning, auth, tenant, error and tracing foundation everything else is built on. Exit criterion per §178: "A secure versioned API foundation exists."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `/v1/...` versioning scheme and routing convention. |
| 2 | `authentication-adapter` + `identity-context` (consumes Design 1.29's principal model - stub/mock acceptable until 1.29 lands, per the dependency map). |
| 3 | `authorization-adapter` + `tenant-context` (consumes Design 1.23's existing security model, which is already partially implemented). |
| 4 | Error model per Appendix C, with the §62 category list as the initial fixed set. |
| 5 | Request-ID generation/propagation and tracing integration. |
| 6 | OpenAPI contract skeleton covering the Appendix A resource list (empty/stub operations acceptable at this stage). |

### Definition of Done

1. A request without valid tenant context is rejected with a `VALIDATION_ERROR`/`AUTHENTICATION_REQUIRED` shaped exactly per Appendix C - not a generic 500.
2. Every error response includes a `request_id` that also appears in the corresponding server-side trace.
3. The OpenAPI document lists every Appendix A resource path, even where the operation body is still a stub.
4. `./gradlew build` green with the new/modified module wired into `settings.gradle.kts`.

---

## Phase PAPI-2 - Resource APIs

**Goal:** core platform resources become externally consumable as read (and, where safe, write) APIs. Exit criterion per §178: "Core platform resources are externally consumable."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `sources`, `source versions` resources over existing `synvault` data. |
| 2 | `knowledge`, `chunks` resources over existing `ingestion-cache` (`manifest`, `chunks_payload`) data. |
| 3 | `provenance` exposed as part of the chunk/knowledge resource representation, drawing on the fields `ingestion-cache` already persists (`section_path`, `source_elements`, `ingest_usage`, etc.). |
| 4 | `resource-services` + `provenance-adapter` modules per the §154 skeleton. |
| 5 | Pagination implementation satisfying invariant 5 (security-context aware - no leakage through page metadata). |

### Definition of Done

1. `GET /v1/sources/{id}/versions` and `GET /v1/chunks` return data sourced from the real `synvault`/`ingestion-cache` tables, not mocks.
2. A paginated list request from a caller scoped to tenant A never reveals tenant B's resource count, even indirectly (e.g. via a total-count field).
3. Provenance fields on a chunk resource match what `ingestion-cache.chunks_payload` actually stores for that chunk (cross-checked against a real ingested document).

---

## Phase PAPI-3 - Async Operations

**Goal:** long-running work gets one consistent public contract. Exit criterion per §178: "Long-running operations have a consistent API contract."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `operation-service` implementing the Appendix B resource and §56 state machine. |
| 2 | Idempotency support (`idempotency` module per §154) for operation-creating requests. |
| 3 | Cancellation semantics (`CANCEL_REQUESTED` → `CANCELLED`) and retry-safety guidance per operation type. |
| 4 | **`ExtractionStatus` reconciliation**: an explicit, tested mapping from `synanton.extraction.v1`'s `ExtractionStatus` (`ACCEPTED/QUEUED/RUNNING/COMPLETED/PARTIAL/FAILED/CANCELLED/EXPIRED`) onto the public Operation state machine (`ACCEPTED/QUEUED/RUNNING/SUCCEEDED/FAILED/CANCEL_REQUESTED/CANCELLED/EXPIRED`) - decide where `PARTIAL` maps (likely `SUCCEEDED` with a partial-result flag in the response body, not a new public state) and confirm no other already-shipped async model (GPU execution, annotation processing runs) needs the same treatment. |
| 5 | Durable operation-state persistence satisfying invariant 3 (acceptance implies durable record, not in-memory only). |

### Definition of Done

1. A duplicate request with the same idempotency key against an in-flight Operation returns the existing Operation, not a second one.
2. An extraction-plane job's `PARTIAL` status is observably mapped to a defined, tested public Operation representation - not silently dropped or misreported as `SUCCEEDED` without qualification.
3. Killing the API process after Operation acceptance but before completion still shows the Operation as durably `QUEUED`/`RUNNING` on restart, per invariant 3.

---

## Phase PAPI-4 - Search API

**Goal:** integrate Design 1.31 (once it exists). Exit criterion per §178: "Applications can use Search without knowing the search backend."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `search-adapter` per §154, fronting whatever Search 1.31 ships (query, filters, pagination, profiles, provenance, consistency hints). |
| 2 | Interim: until 1.31 lands, `search-adapter` may front the existing `synquest` hybrid search kernel directly, with a migration path noted rather than blocking on 1.31. |

### Definition of Done

1. A search request through `/v1/search` returns results with citation/provenance metadata, consistent with invariant 33/34 (security is a prerequisite for candidate eligibility; ranking cannot override authorization).
2. Swapping the interim `synquest`-backed adapter for a future 1.31-backed one requires no change to the public `/v1/search` contract.

---

## Phase PAPI-5 - Ingestion API

**Goal:** integrate Design 1.28 (once it exists). Exit criterion per §178: "Applications can submit and monitor ingestion."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `ingestion-adapter` per §154: source creation, ingestion-run submission, upload path, Operation tracking (reusing PAPI-3's Operation model). |
| 2 | Interim: front the existing `synflux` ingestion trigger REST endpoint until 1.28 lands. |

### Definition of Done

1. `POST /v1/ingestion-runs` produces a trackable Operation whose lifecycle matches PAPI-3's state machine, backed by the real `synflux` job today.

---

## Phase PAPI-6 - AI API

**Goal:** integrate Design 1.30 (once it exists). Exit criterion per §178: "Applications can consume AI capabilities without GPU Runtime knowledge."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | `ai-adapter` per §154: execution submission, status, cancellation, streaming, RAG-style `answers` resource. |
| 2 | No GPU/runtime topology (device IDs, pool names) crosses this adapter - same no-leakage rule already enforced for the extraction plane. |

### Definition of Done

1. An AI execution's public representation contains no runtime-topology detail (grep check, mirroring the extraction plane's SCEP-5 DoD precedent).

---

## Phase PAPI-7 - Governance

**Goal:** the API can evolve safely. Exit criterion per §178: "The API can evolve safely."

### Deliverables

| # | Deliverable |
|---|---|
| 1 | Compatibility CI: a shipped `/v1/` resource cannot change in a breaking way without an explicit version bump. |
| 2 | Security testing (authz negative tests, pagination-leakage tests per invariant 5). |
| 3 | Contract testing against `platform-api`'s own OpenAPI document. |
| 4 | API documentation generation and SDK generation. |
| 5 | Deprecation process: how a resource/field is marked deprecated, communicated, and eventually removed. |
| 6 | Preview surface boundary (`/v1beta/...`) per ADR-009's flagged open item - decide scope now rather than let it grow ad hoc. |

### Definition of Done

1. A deliberately introduced breaking change to a `/v1/` resource fails CI without a version bump.
2. A pagination-leakage regression test (per PAPI-2 DoD item 2) is part of the permanent security test suite, not a one-off.

---

## Sequencing and Parallelism

```text
PAPI-0 (module-home decision)
   |
PAPI-1 (API foundation - hard serialization point)
   |
   +-------------------------+
   |                         |
PAPI-2 (resource APIs)   PAPI-3 (async operations)
   |                         |
   +-------------------------+
                |
        PAPI-4 / PAPI-5 / PAPI-6
   (each blocked on its respective plane: Search 1.31 / Ingestion 1.28 / AI Runtime 1.30 -
    or built against an interim adapter over the existing synquest/synflux/gateway modules)
                |
        PAPI-7 (governance)
```

PAPI-1 is the hard serialization point, matching Design 1.27's own EWP-1 precedent. PAPI-2 and PAPI-3 can proceed in parallel once it lands; PAPI-4 through PAPI-6 are each individually blocked on their respective plane unless an interim adapter is deliberately chosen.

---

## Risks

| Risk | Impact | Mitigation |
|---|---|---|
| `synapt` and `platform-api` coexist indefinitely without a decided boundary | Two external surfaces, unclear which one a new integration should target | PAPI-0's decision doc is a hard gate, same pattern as EWP-0 |
| `ExtractionStatus` vs. Operation-state divergence goes undecided | A second async status model with a different shape, the exact failure Design 1.27's Motivation section warns about | PAPI-3 deliverable 4 is an explicit, tested mapping - not deferred |
| PAPI-4/5/6 block entirely on 1.28/1.30/1.31 with no interim path | Platform API plan stalls waiting on three separate "Not started" designs | Each phase's deliverables include an explicit interim-adapter option over the existing `synquest`/`synflux`/`gateway` modules |
| Pagination/security leakage shipped without a permanent regression test | A silent authorization bypass through count/facet metadata | PAPI-7 deliverable 2 promotes the PAPI-2 DoD check into a permanent CI suite |

---

## How to Contribute

1. A phase is not done until its numbered Definition of Done is fully satisfied - partial completion is reported as partial.
2. PAPI-0's decision doc must exist and be linked from this file before PAPI-1 work is ticketed.
3. Each Deliverable row above is sized to be one ticket; split further only if a reviewer asks.

---

## References

- `docs/architecture/synanton-design-1.32.md` - Appendices A-H, §55-56, §62, §99, §154, §173-175, §178-179
- [ADR-009](../../architecture/decisions/adr-009-platform-api-contract.md)
- `docs/architecture/synanton-platform-architecture-1.0.md` - §11, §14, §15
- `docs/implementation/eventing-workflow-plane/INDEX.md` - joint owner of the `ExtractionStatus`/Operation reconciliation task (PAPI-3 deliverable 4)
- `docs/implementation/content-extraction-plane/INDEX.md` - precedent for this document's phase/deliverable/DoD structure
