---
title: "Extraction Error Catalogue"
status: "current"
last_reviewed: "2026-08-24"
---

# Extraction Error Catalogue - `synanton.extraction.v1`

**Purpose:** the complete contract-level error catalogue for the Structured Content Extraction Plane, with the caller action and retryability verdict for each code.
**Source:** proposal §24
**Enforced by:** `ExtractionErrorCatalogue` (contract module) and `ExtractionErrorCatalogueTest`
**Last Updated:** 2026-08-24

---

## Rules

1. Consumers branch on `ExtractionErrorCode` and on `ExtractionErrorCatalogue.isRetryable(...)`. Nothing else.
2. `diagnostic` is operator-facing, free-form, and unstable. Parsing it turns an implementation detail into a de-facto contract - a parser NPE is a diagnostic attached to `ERROR_EXTRACTION_FAILED`, never a code of its own.
3. "Retryable" means an unchanged retry could plausibly succeed. It does not promise success and does not mean retrying is free.
4. **Retries MUST reuse the original idempotency key.** A retry with a fresh key duplicates the expensive work the key existed to prevent (§13).
5. An unrecognised code is treated as **not** retryable. A client meeting a code from a newer plane must not decide on its own that looping is safe.

---

## The 13 codes

| Code | Retryable | Meaning | Caller action |
|---|:---:|---|---|
| `ERROR_INVALID_REQUEST` | no | Malformed or internally inconsistent request. Also returned when an idempotency key is reused with materially different parameters. | Fix the request. On a key conflict, the original operation is untouched - poll it instead. |
| `ERROR_INVALID_OBJECT_REFERENCE` | no | The object reference is structurally invalid: empty bucket or key, malformed sha256, non-positive size. | Fix the reference. |
| `ERROR_OBJECT_NOT_FOUND` | no | Reference well-formed, object absent or unreadable by the plane. | Confirm the object exists and the plane has access. Do not retry unchanged. |
| `ERROR_OBJECT_CHANGED` | no | The object no longer matches the supplied sha256. | Re-read the source and submit a new request with the current digest. Never silently accept the new bytes - the result would not match what the caller asked about. |
| `ERROR_UNSUPPORTED_MEDIA_TYPE` | no | This plane cannot process the media type at all. | Consult `GetCapabilities`. Apply the caller's own fallback policy (`local-tika`, `partial`, or `fail`). |
| `ERROR_UNSUPPORTED_OPTION` | no | A requested option cannot be supported for this content. | Prefer inspecting `feature_states`: an unsupported *feature* is reported per-feature without failing the whole operation. This code is for options that make the request itself unsatisfiable. |
| `ERROR_REJECTED_CAPACITY` | **yes** | Admission refused. | Back off and retry **with the same idempotency key**. `GetCapacity` is advisory - a prior `CAPACITY_AVAILABLE` does not reserve anything. |
| `ERROR_EXPIRED` | no | `expires_at` passed. | A lifecycle outcome, not a technical failure (§12). Resubmit with a new expiry if the result is still wanted. Auto-retry would re-run work whose deadline the caller declared passed. |
| `ERROR_TIMEOUT` | **yes** | Processing exceeded the plane's duration ceiling. | Retry with the same key, or split the artifact. Persistent timeouts on the same content indicate the artifact is too large for the current limits. |
| `ERROR_EXTRACTION_FAILED` | no | The content could not be processed. | Inspect the diagnostic. Do not loop - the same content will fail the same way. |
| `ERROR_PARTIAL_EXTRACTION` | no | Some content was extracted, some was not. | Inspect `feature_states` and per-item status, then decide whether the partial result is usable. Operation status will be `STATUS_PARTIAL`. |
| `ERROR_PAYLOAD_INVALID` | no | The plane produced a payload that failed its own validation. | Report it. This is a plane defect, not a caller error. |
| `ERROR_INTERNAL_ERROR` | **yes** | Unclassified internal fault. | Retry once with the same key; escalate if it persists. |

Retryable: `ERROR_REJECTED_CAPACITY`, `ERROR_TIMEOUT`, `ERROR_INTERNAL_ERROR`. Everything else describes a problem with the request or the content that retrying cannot fix.

---

## Error vs feature state

These answer different questions, and conflating them is the most likely misuse.

| Question | Where to look |
|---|---|
| Did the operation succeed? | `ExtractionStatus` |
| Why did it not succeed? | `ExtractionError.code` |
| Was OCR actually performed? | `feature_states["ocr"]` |

A successful operation can still carry `FEATURE_UNSUPPORTED` or `FEATURE_FAILED` for an individual feature. That is the point of feature state (§67.10): requesting `scene_analysis` on a PDF yields a `STATUS_COMPLETED` operation whose `scene_analysis` feature is `FEATURE_UNSUPPORTED`, rather than a failed operation or - worse - silence.

Conversely, `ERROR_UNSUPPORTED_OPTION` is for a request the plane cannot satisfy at all, not for one feature among several.

---

## Status mapping

| `ExtractionStatus` | Error present? | Meaning |
|---|:---:|---|
| `STATUS_COMPLETED` | no | Every item succeeded. Individual features may still be unsupported or not applicable. |
| `STATUS_PARTIAL` | yes, per item | Some items or features succeeded, some did not. |
| `STATUS_FAILED` | yes | No item succeeded. |
| `STATUS_CANCELLED` | optional | Cancelled by the caller. |
| `STATUS_EXPIRED` | `ERROR_EXPIRED` | Deadline passed. **Never** reported as `STATUS_FAILED`. |
