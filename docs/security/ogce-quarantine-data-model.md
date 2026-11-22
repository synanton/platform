---
title: "OGCE Quarantine Persistence Data Model (design review)"
status: "proposed"
last_reviewed: "2026-11-22"
---

# OGCE Quarantine Persistence Data Model (design review)

**Decision context:** [ADR-044](../architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Controls:** [D5 quarantine data-handling controls](./ogce-quarantine-controls.md) · [D6 test plan](./ogce-quarantine-test-plan.md)
**Tracking:** [#826](https://github.com/synanton/platform/issues/826)
**Status:** proposed for review. **No schema migration or implementation is authorized by this document.**

## 1. Goals

- Trace every quarantine record to the tenant, source document, source version, extraction generation, chunk, and line.
- Preserve diagnostic history across retries and re-extractions; do not overwrite earlier runs.
- Support structural/semantic aggregation, reason-code histograms, and disposition review.
- Apply D5 controls: tenant isolation, encryption at rest, audit, 30/90 retention, and source-deletion cleanup.
- Consume the shared digest contract (#831); do not define prompt/schema digest semantics here.

## 2. Proposed record

| Field | Type | Notes |
|---|---|---|
| `tenant_id` | text | Partition key; never defaulted. |
| `source_version_id` | uuid | Source generation. Part of the partition key. |
| `source_document_id` | text/uuid | Document identity for traceability. |
| `extraction_generation` | uuid | Minted per extraction attempt/run; all chunks of one run share it. |
| `chunk_ordinal` | int | Parent chunk within the generation. |
| `line_number` | int | 1-based line number in the model response. |
| `reason_code` | text | Stable code from `JsonlFailureReason`. |
| `category` | text | `STRUCTURAL` or `SEMANTIC`. |
| `detail` | text | Bounded human-readable cause. |
| `raw_line_sha256` | text | Always stored; lets replay verify content without retaining raw text. |
| `raw_line` | text/blob | Optional, encrypted, retention-bound; D5 controls apply. |
| `prompt_version` | text | Nullable for historical records. |
| `prompt_digest` | text | Nullable until #829/#831; null means historical/unverified. |
| `schema_digest` | text | Nullable until #827/#832. |
| `captured_at` | timestamp | When the line was rejected. |
| `superseded_by` | uuid | Nullable; later `extraction_generation` that superseded this one. |
| `disposition` | text | `OPEN`, `REVIEWED`, `ACCEPTED_AS_INVALID`, `RETRY_REQUESTED`, `CLOSED`. |
| `disposition_at` | timestamp | Nullable. |
| `disposition_by` | text | Nullable; actor/service identity. |
| `disposition_reason` | text | Nullable; audit rationale. |
| `retention_expires_at` | timestamp | Captured_at + 30 days by default; never beyond 90 days absent an approved exception. |

## 3. Keys and storage shape

Proposed primary key:

```text
((tenant_id, source_version_id), extraction_generation, chunk_ordinal, line_number)
```

- `tenant_id` and `source_version_id` form the source-generation boundary.
- `extraction_generation` is the versioning dimension: a new run appends a new generation; it does not overwrite prior rows.
- `chunk_ordinal` and `line_number` make each rejected line addressable.

Proposed table: a dedicated `quarantine_records` table, not an overload of `extraction_results` (which stores accepted per-chunk entities/relations). A migration is a separate, reviewed step.

## 4. Generation lifecycle

| State | Meaning |
|---|---|
| `PENDING` | Run started; no accepted generation yet. |
| `ACTIVE` | Latest successful extraction generation for the source version. |
| `SUPERSEDED` | A newer generation succeeded; records remain for audit until retention/deletion. |
| `FAILED` | The run failed; diagnostics remain reviewable. |
| `EXPIRED` | All records for the generation have been removed by retention. |

On re-extraction:

1. Mint a new `extraction_generation`.
2. Run extraction and write quarantine records for that generation.
3. On success, mark the previous `ACTIVE` generation `SUPERSEDED` with `superseded_by`.
4. On failure, keep the previous `ACTIVE` generation current; mark the failed attempt `FAILED`.
5. Never destructively overwrite a prior generation. Replacement is limited to retention/source deletion.

## 5. Deletion, retention, and audit

- **Source deletion:** key `(tenant_id, source_version_id)` removes **all** extraction generations for that source version. The operation is idempotent and must not touch another generation or tenant.
- **Retention:** each record carries `retention_expires_at`; default 30 days, hard maximum 90 days absent an approved exception. Expiry removes payloads; audit metadata may remain per policy.
- **Audit:** read, export, replay, and disposition events record actor, time, reason, and source/segment identity. Raw quarantine content never enters ordinary application logs.
- **Encryption:** `raw_line` uses the platform's tenant-scoped KMS/envelope encryption. `raw_line_sha256` and metadata may remain for audit after payload expiry.

## 6. Aggregation and queries

- By `(tenant, source_version, extraction_generation)`: record count, structural vs semantic split, reason-code histogram.
- By disposition: open/reviewed/accepted/retry/closed counts.
- By expiry: records due for deletion.
- By source deletion: remove all generations for a source version.
- For #828: the persisted histogram must be available before a run is labelled a durable baseline.

## 7. Security boundaries

- The quarantine store is governed by D5/D6; it is not controlled by `LLM_EXTRACTION_CAPTURE_ENABLED` (that gate governs the separate diagnostic capture directory).
- Capture and quarantine share the source-deletion key but remain separate stores until #830 decides whether to unify them.
- No cross-tenant read/export/replay; fail closed on missing tenant context.
- No digest semantics are defined here; `prompt_digest`/`schema_digest` follow #831.

## 8. Open decisions before implementation

1. **Raw line storage:** retain encrypted raw lines (proposed) or only hashes plus bounded metadata. D5 requires controls either way.
2. **Generation ID source:** mint a UUID at extraction start in the consumer; expose it on `ExtractionResult`/persistence. This is a follow-up to #824/#825.
3. **Audit retention vs payload retention:** how long disposition audit remains after raw payload expiry.
4. **Store placement:** Cassandra `quarantine_records` vs candidate store; migration/backfill plan.
5. **Replay:** whether replay creates a new generation and whether the original disposition remains linked.

## 9. Test plan (D6 traceability)

- Re-extract v2 -> v3: old generation remains `SUPERSEDED`; new generation is `ACTIVE`; no overwrite.
- Failed re-extraction: previous generation stays `ACTIVE`; failed attempt is `FAILED`.
- Source deletion: all generations removed; second source version and tenant untouched; idempotent.
- Retention: clock-controlled expiry at 30 days; no record beyond 90 days without an exception.
- Cross-tenant access: tenant B cannot read/export/replay tenant A's record.
- Encryption: ciphertext at rest; key-scope negative test.
- Audit: read/export/replay/disposition records all required fields.
- Aggregation: structural/semantic counts and reason histogram match the source records.

## 10. Non-goals

- No F1, extraction-quality, gate status, profile qualification, or ADR-044 transition.
- No DEV-label or frozen-gold consumption.
- No capture-directory implementation (that is #830).
- No prompt/schema digest contract definition (that is #831).
