---
title: "OGCE Quarantine Data-Handling Controls"
status: "current"
last_reviewed: "2026-11-22"
---

# OGCE Quarantine Data-Handling Controls

**Decision:** [ADR-044: Ontology-Grounded Chunk Extraction](../architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Design source:** [v1.36 OGCE proposal §4.7](../architecture/proposals/v1.36/ontology-grounded-chunk-extraction.md#47-validation-quarantine-and-consolidation)
**Gate register:** [OGCE acceptance gates](../quality/ogce-gates.md)
**Owner:** andreminin

Status mirrors ADR-044; see ADR-044 for transition criteria. This specification records required controls and verification methods. A non-KMS capture slice is implemented: tenant/source-version-scoped filesystem paths, idempotent source deletion, an expiry primitive, and production refusal. Encryption/KMS, read/export audit, automatic retention scheduling, and compliance-cascade wiring remain pending. No gate or ADR-044 status is claimed.

## Control matrix

| Control | Required behavior | Implementation location | Verification method | Owner |
|---|---|---|---|---|
| **Encryption at rest** | Quarantine payloads and bounded raw lines are encrypted with the platform's tenant-scoped key management/envelope-encryption facility. | Pending; expected at the candidate-store/quarantine boundary in the Synflux extraction consumer (ADR-044 decision 6). | Automated test writes a quarantine record and asserts ciphertext at rest; key-scope test proves one tenant key cannot decrypt another tenant's object. | andreminin |
| **Tenant isolation** | Storage and authorization enforce tenant isolation; no cross-tenant read, export, or replay. | Pending; extraction-consumer authorization plus candidate-store partitioning. | Negative test: tenant B cannot read, export, or replay tenant A's quarantine item by ID, query, or export job. | andreminin |
| **Audit** | Every read, export, replay, and disposition records actor, time, reason, and source/segment identity. | Pending; candidate-store audit events. | Replay a scripted read/export/replay/disposition sequence and assert one audit record each with all five fields; assert ordinary application logs contain no raw quarantine content. | andreminin |
| **Retention (30/90)** | Default retention is 30 days; hard maximum is 90 days absent an approved legal/security exception; expiry is automatic. | Pending; scheduled retention job over quarantine metadata. | Clock-controlled test: the item remains readable through the approved default window and is expired/removed at its end; no item exceeds 90 days without a recorded exception. | andreminin |
| **Source-deletion cleanup** | Deleting the associated source generation cascades to quarantine payloads and derived raw prompts/responses. Normal source deletion uses **tombstone** (hide from current queries, retain audit metadata); compliance deletion (DSAR/GDPR) uses **hard delete** per policy. | Pending; source-deletion consumer plus candidate-store cascade. | Test both modes: tombstone hides from current but remains auditable; hard delete removes payloads/metadata per policy; cleanup is idempotent; no other generation or tenant is affected. | andreminin |

**Deletion mode policy:** normal source deletion = `TOMBSTONE`; compliance deletion (DSAR/GDPR or equivalent) = `HARD_DELETE`. The same mode applies to the associated capture payloads; audit metadata retention after hard delete follows legal/security policy. See [quarantine data model](./ogce-quarantine-data-model.md) §7.

**Dev-touched provenance:** `evaluation/dev25/development-touched.tsv` survives document hard-deletion. It records the document ID and provenance only, contains no source content, and never affects extraction, quarantine, or held-out policy.

## Capture directory controls (diagnostic)

`LLM_EXTRACTION_CAPTURE_DIR` writes raw model output for diagnosis. It is separate from the quarantine work queue but holds the same class of tenant-adjacent data. Until the controls below are implemented and tested, capture is **diagnostic-only and disabled by default**; a configured path alone must not enable it in production.

| Control | Required behavior | Current state | Verification |
|---|---|---|---|
| **Tenant isolation** | Capture storage/path is tenant-scoped; no shared production directory; cross-tenant read/export fails. | Implemented for filesystem write/delete paths (`tenant_id/source_version_id`); read/export authorization remains pending. | Path-scope and traversal tests in `FileSystemCaptureStoreTest`. |
| **Encryption at rest** | Same tenant-scoped key management/envelope encryption as the quarantine controls; no plaintext production capture. | Pending; blocked on the platform KMS facility. | Ciphertext-at-rest and key-scope tests. |
| **Audit** | Read/export/replay/disposition records actor, time, reason, and source/segment identity. Capture is write-only by default; raw content never enters ordinary logs. | Pending; filesystem-only diagnostic access. | Scripted access test; log scan. |
| **Retention (30/90)** | Default 30 days; hard maximum 90 days absent an approved exception; automatic expiry. | Scheduled 30-day expiry implemented (`CaptureRetentionJob`); the 30-day cutoff also enforces the 90-day hard maximum. Approved-exception extension remains pending. | `CaptureRetentionJobTest` covers expiry and disabled/production no-op; exception-policy test pending. |
| **Source-deletion cleanup** | Deleting the associated source generation cascades to capture payloads; cleanup is idempotent and does not touch other generations/tenants. Cleanup key is `(tenant_id, source_version_id)`; a re-extraction generation is an additional replacement dimension, not a deletion boundary. | Scoped idempotent `deleteSource` implemented; compliance-cascade wiring remains pending. | Cross-tenant/sibling-version and retry tests in `FileSystemCaptureStoreTest`. |
| **Production enablement gate** | `LLM_EXTRACTION_CAPTURE_ENABLED` defaults to `false`; a path alone does not enable capture. A production-environment signal must additionally refuse activation. Production enablement requires the controls above and tests to pass. | Two-flag gate plus code-enforced production refusal added; production enablement still denied because encryption/audit/retention are incomplete. | Config tests: path alone disabled; enabled + path + production refused. |

**Owner:** andreminin. **Implementation:** `FileSystemCaptureStore` provides the tenant/source-version-scoped filesystem slice, deletion, and expiry primitive; #833 provides the two-flag gate and #830 adds production refusal. Production enablement remains denied pending encryption/KMS, audit, automatic 30/90 retention, and compliance wiring.

**Relationship to #826:** capture and quarantine must not become two ungoverned stores. #826 must link this section and use the same tenant, retention, audit, and source-deletion controls. Tests for capture controls will be added to the [quarantine test plan](./ogce-quarantine-test-plan.md) when the store exists.

## D6 readiness

D6 is now tracked as a traceability-only test plan: [OGCE quarantine control test plan](./ogce-quarantine-test-plan.md). **andreminin owns every control**; D6 ownership and traceability do not change this specification's status or ADR-044's status.

## Boundaries

- This specification sets no gate threshold, retention exception, or authorization policy beyond what ADR-044 and the v1.36 proposal state.
- Quarantine remains an operational work queue, not a write-only log; raw quarantine data is not copied into ordinary application logs.
- No new deployable is authorized.
