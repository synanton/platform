# Operation Resource Model

**Status:** Frozen 2026-10-17 — PAPI-3 artifact (#246 "Freeze Platform API 1.32 contracts")
**Source:** Design 1.32 §55–§60, Appendix B; `synanton.extraction.v1` `ExtractionStatus` (§15/§24/§12); `synanton.gpu.v1` `ExecutionState`

The public contract for long-running work. One resource shape, one state
machine, regardless of which internal plane performs the work. This is the
*external* contract; internal coordination and recovery are Design 1.27's
(§55 boundary note).

## Operation resource

Design 1.32 Appendix B, verbatim (`synanton-design-1.32.md` L3174–3188):

```yaml
operation:
  operation_id: op_01...
  type: INGESTION
  status: RUNNING
  requested_by:
    principal_id: prn_01...
  tenant_id: ten_01...
  created_at: ...
  started_at: ...
  completed_at: null
  progress:
    completed: 73
    total: 100
  error: null
```

The public resource is this list, plus the ADR-020 `outcome` field:

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
  "outcome": null,
  "error": null
}
```

| Field | Type | Notes |
|---|---|---|
| `operation_id` | string | stable id; the poll handle |
| `type` | string (open set) | operation type; §58 examples: `INGESTION`, `REINDEX`, `RECALCULATION`, `BULK_PROCESSING`, `AI_EXECUTION`, `EXPORT` — §58 lists categories, not a frozen enum |
| `status` | enum | the state machine below (§56) |
| `requested_by.principal_id` | string | from the verified identity context (W1) |
| `tenant_id` | string | from the verified identity context (W1) |
| `created_at` / `started_at` / `completed_at` | timestamp | lifecycle marks |
| `progress` | object | `completed` / `total` |
| `outcome` | enum | `FULL` \| `PARTIAL`; present when `status = SUCCEEDED`. **Not in Appendix B** — added by ADR-020 |
| `error` | object | present only when `status = FAILED`; a W2 error-model instance (see Error) |


Endpoints (§55, §59):

```http
GET  /v1/operations/{operation_id}
POST /v1/operations/{operation_id}/cancel
```

## State machine

Design 1.32 §56 names the eight states (verbatim: "Initial states:" followed by
the list). It does **not** define a transition matrix. The transitions below are
a **PAPI-3 decision** (recorded, not projected) except where §59 defines one
explicitly (`RUNNING → CANCEL_REQUESTED → CANCELLED`).

```text
ACCEPTED ──▶ QUEUED ──▶ RUNNING ──┬──▶ SUCCEEDED
  │            │                  ├──▶ FAILED
  └────────────┴──▶ FAILED         └──▶ CANCEL_REQUESTED ──▶ CANCELLED
  │            │
  └────────────┴──▶ CANCELLED
any non-terminal ──▶ EXPIRED          (expires_at passed, §12)
```

- **Terminal states:** `SUCCEEDED`, `FAILED`, `CANCELLED`, `EXPIRED`.
- **`FAILED` may arise from any non-terminal state, not only `RUNNING`.** An
  operation can fail before it runs — admission rejection, scheduling failure,
  resource unavailable at dequeue. Restricting `FAILED` to `RUNNING` would push
  pre-run failures into `EXPIRED` or `CANCELLED`, both of which misrepresent what
  happened. The `error.code` distinguishes the failure; the state does not.
- **`CANCELLED` may be reached without `CANCEL_REQUESTED`.** A cancel before
  execution has nothing to interrupt, so there is no request/acknowledge round
  trip to model: `ACCEPTED`/`QUEUED → CANCELLED` directly. The
  `CANCEL_REQUESTED` path (`RUNNING → CANCEL_REQUESTED → CANCELLED`) applies only
  once work is in flight.
- **`CANCEL_REQUESTED` is a request, not an instantaneous transition** (§59,
  the one defined transition): `RUNNING → CANCEL_REQUESTED → CANCELLED`. It is
  a 1.32-owned state — no internal plane models it (see reconciliation).
- **`EXPIRED` is not a technical failure** (§12): `expires_at` passed.
- **`expires_at` is server-side policy, not a resource field.** Appendix B has
  no `expires_at`, and ADR-020 adds only `outcome`. So a client cannot read
  *when* an operation will expire, and the `→ EXPIRED` trigger is not
  observable in the contract — expiry is policy per operation type. `EXPIRED`
  is a state a client may observe but not predict. *If client-predictable
  expiry is needed, `expires_at` is an additive field and needs the ADR-020
  treatment* — flagged, not assumed.

## Error

Design 1.32 §57 gives the Operation's embedded error (illustrative):

```json
{ "status": "FAILED", "error": { "code": "SOURCE_ACCESS_DENIED", "message": "The source could not be accessed.", "retryable": false } }
```

**The `error` field is a W2 error-model instance** (`docs/api/error-model.md`),
not a second shape. A client parses one error vocabulary, whether the error is
the HTTP response body or embedded in an Operation.

**Naming resolution — `message` vs `detail`.** §57 and Appendix C name the
human-readable occurrence string `message`; W2 (RFC 9457, row 5) names it
`detail`. The public contract uses **W2's `detail`**. The design's `message` is
the pre-RFC 9457 name; W2 superseded it when the error model was adopted. This
is a W2-vs-design naming divergence, recorded here so it is not read as drift —
same class as the D1/D4 divergence note.

So the embedded error is `{ code, detail, retryable, request_id, ... }` per W2,
with `detail` where §57 shows `message`. Messages must not expose secrets or
sensitive backend details (§57).

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
| `TIMEOUT` | `FAILED` | **collapse** — gateway deadline exceeded maps to `FAILED` **with `error.retryable: true`**, so a client can distinguish a retryable deadline from a permanent failure. Without the `retryable` flag the collapse would be lossy. |
| — | `ACCEPTED` / `CANCEL_REQUESTED` / `EXPIRED` | **1.32-only** — no GPU counterpart |

### The three divergences, resolved

1. **Terminal-success naming.** `COMPLETED` (extraction) and `SUCCESS` (GPU)
   both map to the single public `SUCCEEDED`. The public surface has one name
   for "it finished and worked"; internal planes keep their own.

2. **`PARTIAL` (extraction) has no public state.** Maps to **`SUCCEEDED` with
   `outcome: PARTIAL`** — the additive field defined in **ADR-020**. Partiality
   is also visible via `progress.completed < progress.total`, but the `outcome`
   field makes it first-class rather than inferred. A `SUCCEEDED` operation with
   `outcome: FULL` is a full success. `outcome` is a change to the frozen 1.32
   Operation resource and is carried by ADR-020 (a decision record), not by a
   silent edit to the design doc.

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
- `docs/architecture/decisions/adr-020-operation-partial-outcome.md` — the `outcome` field
- `docs/implementation/content-extraction-plane/INDEX.md` — joint owner of the
  extraction half of the reconciliation
- `synanton.extraction.v1` — `extraction_service.proto` L111 (enum), §15/§24/§12
- `synanton.gpu.v1` — `gpu_execution_service.proto` L128 (`ExecutionState`)
