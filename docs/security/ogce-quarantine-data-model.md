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

## 2. Layer hierarchy

The model must not collapse these layers:

```text
Document (identity, source lifecycle, development-touched provenance)
  └── Extraction generation (prompt version+digest, schema digest, model/revision, run ID, started_at)
        ├── Quarantined lines (reason_code, STRUCTURAL/SEMANTIC, raw text/hash, chunk/line)
        └── Accepted candidates (persisted separately, anchored; not this table)
```

- **Document:** stable identity and lifecycle; the `development-touched` flag lives here.
- **Generation:** one extraction run/attempt; versioned by prompt/schema/model inputs.
- **Quarantine:** rejected lines for one generation; never document-scoped.
- **Accepted candidates:** separate storage; not folded into quarantine.

## 3. Document layer

Document identity is owned by the source lifecycle (1.28/ADR-029), not by this table. The quarantine store references:

| Field | Notes |
|---|---|
| `tenant_id` | Partition key; never defaulted. |
| `source_document_id` | Stable document identity. |
| `source_version_id` | The source generation being extracted. |

`development-touched.tsv` (repository sidecar) records the five #820 documents. It is **document-scoped provenance only**; it never changes extraction, quarantine, or held-out policy. Source deletion does not rewrite that history.

## 4. Generation layer

Each extraction attempt gets a generation key. The generation is the versioning unit; records are not overwritten when a new prompt/schema/model runs.

**Generation key:** `(tenant_id, source_version_id, extraction_generation)`.

`extraction_generation` is a UUID minted at extraction start. Its identity inputs are recorded:

| Field | Notes |
|---|---|
| `prompt_version` | Nullable for historical records. |
| `prompt_digest` | SHA-256 from #829/#831; null means historical/unverified. |
| `schema_digest` | From #827/#832; null until available. |
| `model_id` | Extraction model identity. |
| `model_revision` | Immutable revision/artifact. |
| `extraction_started_at` | Run timestamp. |
| `extraction_run_id` | Durable run/correlation ID if distinct from the generation ID. |
| `generation_status` | `PENDING`, `ACTIVE`, `SUPERSEDED`, `FAILED`, `DELETED`. |
| `superseded_by` | Nullable; later generation that superseded this one. |
| `tombstoned_at` | Nullable; set on source deletion/retention tombstone. |
| `tombstone_reason` | Nullable; `SOURCE_DELETED`, `RETENTION`, `OPERATOR`. |

The cache/provenance key must include the prompt and schema digests; the generation ID is the storage/version key (#829/#831).

## 5. Quarantine record layer

| Field | Type | Notes |
|---|---|---|
| `tenant_id` | text | Partition key. |
| `source_version_id` | uuid | Source generation. |
| `extraction_generation` | uuid | Generation key. |
| `chunk_ordinal` | int | Parent chunk. |
| `line_number` | int | 1-based line in the model response. |
| `reason_code` | text | Stable code from `JsonlFailureReason`. |
| `category` | text | `STRUCTURAL` or `SEMANTIC`. |
| `detail` | text | Bounded cause. |
| `raw_line_sha256` | text | Always stored; verifies content without retaining raw text. |
| `raw_line` | text/blob | Optional, encrypted, retention-bound. |
| `captured_at` | timestamp | When the line was rejected. |
| `disposition` | text | `OPEN`, `REVIEWED`, `ACCEPTED_AS_INVALID`, `RETRY_REQUESTED`, `CLOSED`. |
| `disposition_at` | timestamp | Nullable. |
| `disposition_by` | text | Nullable; actor/service identity. |
| `disposition_reason` | text | Nullable; audit rationale. |
| `retention_expires_at` | timestamp | Default +30 days; never beyond 90 days absent an approved exception. |

Proposed primary key:

```text
((tenant_id, source_version_id), extraction_generation, chunk_ordinal, line_number)
```

A separate `quarantine_generations` metadata table carries the generation fields in §4. A dedicated `quarantine_records` table avoids overloading `extraction_results` (accepted per-chunk candidates).

## 6. Re-extraction policy

**Decision: versioned generations with explicit tombstones; no destructive overwrite.**

1. Mint a new `extraction_generation`.
2. Write quarantine records for that generation.
3. On success, mark the previous `ACTIVE` generation `SUPERSEDED` with `superseded_by`.
4. On failure, keep the previous `ACTIVE` generation current; mark the failed attempt `FAILED`.
5. Old generations remain queryable until retention or source deletion. Their records are not overwritten.

This preserves diagnostic history and keeps v2/v3 quarantines distinguishable. Replacement is limited to retention/source-deletion tombstones.

## 7. Deletion, retention, and audit

- **Source deletion:** key `(tenant_id, source_version_id)` tombstones all generations for that source version. Raw payloads are removed; generation/tombstone metadata and audit disposition remain per policy. The operation is idempotent and must not touch another generation or tenant.
- **Document identity:** owned by source lifecycle; this store does not delete or reuse it.
- **Development-touched flag:** repository/document provenance; unaffected by extraction, re-extraction, or source deletion.
- **Retention:** per-record `retention_expires_at`; default 30 days, hard max 90 days absent an exception. Expiry removes payloads; audit metadata may remain.
- **Audit:** read, export, replay, and disposition events record actor, time, reason, and source/segment identity. Raw content never enters ordinary logs.
- **Encryption:** `raw_line` uses the tenant-scoped KMS/envelope facility; `raw_line_sha256` and metadata may remain after payload expiry.

## 8. Dev-touched handling

`development-touched.tsv` is a **document-layer, provenance-only** flag. It must not:

- alter quarantine lifecycle, retention, or deletion;
- create special-case extraction behavior;
- decide P2-3 held-out status.

That decision belongs to P2-3 planning; the flag only preserves the #820 history.

## 9. Aggregation and queries

- By `(tenant, source_version, extraction_generation)`: record count, structural vs semantic split, reason-code histogram.
- By disposition: open/reviewed/accepted/retry/closed counts.
- By expiry: records due for deletion.
- By source deletion: tombstone all generations for a source version.
- For #828: persisted histograms are required before a run is labelled a durable baseline.

## 10. Security boundaries

- Quarantine is governed by D5/D6; it is not controlled by `LLM_EXTRACTION_CAPTURE_ENABLED` (that gate governs the separate diagnostic capture directory).
- Capture and quarantine share the source-deletion key but remain separate stores until #830 decides whether to unify them.
- No cross-tenant read/export/replay; fail closed on missing tenant context.
- No digest semantics are defined here; prompt/schema digests follow #831.

## 11. Open decisions before implementation

1. **Raw line storage:** retain encrypted raw lines (proposed) or only hashes plus bounded metadata.
2. **Generation ID source:** mint at extraction start in the consumer; expose it on `ExtractionResult` and persistence.
3. **Audit retention vs payload retention:** how long disposition audit remains after raw payload expiry.
4. **Store placement:** Cassandra `quarantine_records`/`quarantine_generations` vs candidate store; migration/backfill.
5. **Replay:** whether replay creates a new generation and whether the original disposition remains linked.
6. **Model identity fields:** exact model ID/revision source for the generation record.

## 12. Test plan (D6 traceability)

- Re-extract v2 -> v3: old generation `SUPERSEDED`; new `ACTIVE`; no overwrite.
- Failed re-extraction: previous generation stays `ACTIVE`; failed attempt `FAILED`.
- Source deletion: all generations tombstoned; raw payloads removed; second source version/tenant untouched; idempotent.
- Retention: clock-controlled expiry at 30 days; no record beyond 90 days without an exception.
- Cross-tenant access: tenant B cannot read/export/replay tenant A's record.
- Encryption: ciphertext at rest; key-scope negative test.
- Audit: read/export/replay/disposition records all required fields.
- Aggregation: structural/semantic counts and histogram match records.
- Dev-touched: flag does not alter any quarantine lifecycle behavior.

## 13. Non-goals

- No F1, extraction-quality, gate status, profile qualification, or ADR-044 transition.
- No DEV-label or frozen-gold consumption.
- No capture-directory implementation (that is #830).
- No prompt/schema digest contract definition (that is #831).
