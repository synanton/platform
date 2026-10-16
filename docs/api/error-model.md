# Error Model

**Status:** Draft — subject of gate row 5 (error-model approval)
**Source:** Design 1.32 §62 (categories), Appendix C (extension fields)

RFC 9457 problem details, extended with four fields. This is the canonical
error artifact — W3 and W4 reference `Problem.yaml`; they do not redefine it.

## Shape

Five RFC 9457 base members + four extensions:

| Field | Type | RFC/ext | Notes |
|---|---|---|---|
| `type` | URI ref | RFC 9457 | category URI; `about:blank` if unresolvable |
| `title` | string | RFC 9457 | stable per category |
| `status` | integer | RFC 9457 | HTTP status, duplicated in body |
| `detail` | string | RFC 9457 | per-occurrence; **not** stable |
| `instance` | URI ref | RFC 9457 | request path or request-id URI |
| `code` | string enum | extension | the 10 categories |
| `retryable` | boolean | extension | same-request retry may succeed |
| `request_id` | string | extension | matches server log |
| `details` | object | extension | per-category context |

Clients branch on `code`. `title` is stable per category; `detail` varies.

## Categories

| `code` | `status` | `retryable` | Meaning |
|---|---|---|---|
| `VALIDATION_ERROR` | 400 | false | malformed or semantically invalid request |
| `AUTHENTICATION_REQUIRED` | 401 | false | missing/invalid credentials |
| `AUTHORIZATION_DENIED` | 403 | false | authenticated, not permitted |
| `RESOURCE_NOT_FOUND` | 404 | false | absent **or** not visible |
| `RESOURCE_CONFLICT` | 409 | false | state conflict |
| `PRECONDITION_FAILED` | 412 | false | conditional request failed |
| `RATE_LIMITED` | 429 | **true** | throttle/quota |
| `DEPENDENCY_UNAVAILABLE` | 503 | **true** | upstream down; backoff |
| `OPERATION_FAILED` | 500 | false | an accepted async Operation failed |
| `INTERNAL_ERROR` | 500 | false | unexpected server fault |

**`RESOURCE_NOT_FOUND` returns 404 for both absent and invisible.** Deliberate
— prevents existence-leakage, consistent with Design 1.32's pagination
invariant. Callers cannot distinguish "wrong id" from "no permission."

**`OPERATION_FAILED` at 500** is the only category tied to state, not a
request/response outcome. It surfaces when a synchronous wrapper or status
check reports a failed Operation; the Operation resource itself carries the
failure detail.

**`INTERNAL_ERROR.retryable: false`** — a 500 implies a bug; retrying unchanged
won't fix it. `retryable` means "unchanged retry plausibly succeeds," not
"transient."

## Slice vs hardening

| Slice | Hardening |
|---|---|
| The shape, all 10 categories, `retryable` | `type` URI registry (hosted) |
| `request_id` (live — PR 225) | per-category `details` schemas |
| `Problem.yaml` referenced by W3/W4 | i18n of `title`/`detail` |
| | `errors.synanton.local` resolution (`.local` is mDNS-reserved) |

## Producers

First live producer: `McpEndpoint` (PR 225) generates `request_id` per
`handle()`; that value becomes this model's `request_id`. Propagation to
successful responses is a follow-up.
