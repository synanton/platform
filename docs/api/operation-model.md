# Operation Resource Model

**Status:** Draft — PAPI-3 artifact (#246 "Freeze Platform API 1.32 contracts")
**Source:** Design 1.32 §55–§60, Appendix B; `synanton.extraction.v1` `ExtractionStatus` (§15/§24/§12); `synanton.gpu.v1` `ExecutionState`

The public contract for long-running work. One resource shape, one state
machine, regardless of which internal plane performs the work. This is the
*external* contract; internal coordination and recovery are Design 1.27's
(§55 boundary note).

## Operation resource

Design 1.32 Appendix B, projected:

```json
{
  "operation_id": "op_01...",
  "type": "INGESTION",
  "status": "RUNNING",
  "requested_by": { "principal_id": "prn_01..." },
  "tenant_id": "ten_01...",
  "created_at": "2026-10-17T10:00:00Z",
  "started_at": "2026-10-17T10:00:02Z",
  "completed_at": null,
  "progress": { "completed": 73, "total": 100 },
  "error": null
}
```

| Field | Type | Notes |
|---|---|---|
| `operation_id` | string | stable id; the poll handle |
| `type` | enum | operation type; §58 examples: `INGESTION`, `REINDEX`, `RECALCULATION`, `BULK_PROCESSING`, `AI_EXECUTION`, `EXPORT` (the set is open — §58 lists categories, not a frozen enum) |
| `status` | enum | the state machine below (§56) |
| `requested_by.principal_id` | string | from the verified identity context (W1) |
| `tenant_id` | string | from the verified identity context (W1) |
| `created_at` / `started_at` / `completed_at` | timestamp | lifecycle marks |
| `progress` | object | `completed` / `total`; the partiality qualifier (see below) |
| `error` | object | present only when `status = FAILED`; `{code, message, retryable}` (§57) |

Endpoints (§55, §59):

```http
GET  /v1/operations/{operation_id}
POST /v1/operations/{operation_id}/cancel
```

## State machine

Design 1.32 §56:

```text
ACCEPTED ──▶ QUEUED ──▶ RUNNING ──┬──▶ SUCCEEDED
                                  ├──▶ FAILED
                                  └──▶ CANCEL_REQUESTED ──▶ CANCELLED

ACCEPTED / QUEUED ──▶ CANCELLED        (cancel before running)
any non-terminal  ──▶ EXPIRED          (expires_at passed, §12)
```

- **Terminal states:** `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`.
- **`CANCEL_REQUESTED` is a request, not an instantaneous transition** (§59):
  the operation moves `RUNNING → CANCEL_REQUESTED → CANCELLED`. It is a
  1.32-owned state — no internal plane models it (see reconciliation).
- **`EXPIRED` is not a technical failure** (§12): `expires_at` passed.

## Error

Design 1.32 §57 — structured, no secrets:

```json
{ "status": "FAILED", "error": { "code": "SOURCE_ACCESS_DENIED", "message": "The source could not be accessed.", "retryable": false } }
```

The `error` shape is the Operation-level projection of the W2 error model
(`docs/api/error-model.md`), not a parallel model.

## Retry

Design 1.32 §60 — distinguish **HTTP request retry** from **operation retry**.
An HTTP timeout does not mean the operation failed; clients query by
`operation_id` (or the idempotency key, PAPI-3 deliverable 2).

## Reconciliation — internal status → public Operation state

Two already-shipped async models feed this contract. Both are reconciled
explicitly; neither may leak its own vocabulary to the public surface.

### `ExtractionStatus` (synanton.extraction.v1) → Operation

| `ExtractionStatus` | Operation state | Note |
|---|---|---|
| `STATUS_ACCEPTED` | `ACCEPTED` | direct |
| `STATUS_QUEUED` | `QUEUED` | direct |
| `STATUS_RUNNING` | `RUNNING` | direct |
| `STATUS_COMPLETED` | `SUCCEEDED` | **rename** — extraction's `COMPLETED` = 1.32's `SUCCEEDED` |
| `STATUS_PARTIAL` | `SUCCEEDED` | **qualified** — see partiality below |
| `STATUS_FAILED` | `FAILED` | direct |
| `STATUS_CANCELLED` | `CANCELLED` | direct |
| `STATUS_EXPIRED` | `EXPIRED` | direct |
| — | `CANCEL_REQUESTED` | **1.32-only** — no extraction counterpart |

### `ExecutionState` (synanton.gpu.v1) → Operation

The GPU plane's `ExecutionState` is a second divergent model — the PAPI-3
deliverable's "confirm no other async model needs the same treatment" resolves
to **yes, this one does**.

| `ExecutionState` | Operation state | Note |
|---|---|---|
| `QUEUED` | `QUEUED` | direct (GPU has no `ACCEPTED`; it jumps to `QUEUED`) |
| `RUNNING` | `RUNNING` | direct |
| `SUCCESS` | `SUCCEEDED` | **rename** — GPU's `SUCCESS` = 1.32's `SUCCEEDED` |
| `FAILED` | `FAILED` | direct |
| `CANCELLED` | `CANCELLED` | direct |
| `TIMEOUT` | `FAILED` | **collapse** — gateway deadline exceeded is a failure (`retryable: true` if the operation is re-runnable) |
| — | `ACCEPTED` / `CANCEL_REQUESTED` / `EXPIRED` | **1.32-only** — no GPU counterpart |

### The three divergences, resolved

1. **Terminal-success naming.** `COMPLETED` (extraction) and `SUCCESS` (GPU)
   both map to the single public `SUCCEEDED`. The public surface has one name
   for "it finished and worked"; internal planes keep their own.

2. **`PARTIAL` (extraction) has no public state.** Maps to **`SUCCEEDED`,
   qualified by `progress`**: `progress.completed < progress.total` with
   `status = SUCCEEDED` is a partial success — observable, not misreported.
   A `SUCCEEDED` operation with `completed == total` is a full success.
   *Proposed additive extension to Appendix B:* an explicit
   `outcome: FULL | PARTIAL` field, so partiality is a first-class field rather
   than inferred from `progress`. Flagged as a design extension, not assumed.

3. **`CANCEL_REQUESTED` is 1.32-owned.** Neither extraction nor GPU models a
   "cancellation requested but not yet applied" state. The platform owns it:
   it records the request, then reconciles to `CANCELLED` when the plane
   reports terminal cancellation. The plane never sees `CANCEL_REQUESTED`.

## Gen-A vs Gen-B

Gen-A (BBAG-A) is synchronous — no Operation is created. This model is the
contract PAPI-3 builds against; the state machine, mappings, and error shape
are frozen now, the `operation-service` implementation follows.

## Cross-references

- `docs/api/error-model.md` — W2; the `error` field's shape
- `docs/api/identity-context.md` — W1; `requested_by` / `tenant_id` source
- `docs/implementation/content-extraction-plane/INDEX.md` — joint owner of the
  extraction half of the reconciliation
- `synanton.extraction.v1` — `extraction_service.proto` L111 (enum), §15/§24/§12
- `synanton.gpu.v1` — `gpu_execution_service.proto` L128 (`ExecutionState`)
