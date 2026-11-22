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

Status mirrors ADR-044; see ADR-044 for transition criteria. This specification records required controls and verification methods. Implementation is pending, and no control is claimed implemented or verified.

## Control matrix

| Control | Required behavior | Implementation location | Verification method | Owner |
|---|---|---|---|---|
| **Encryption at rest** | Quarantine payloads and bounded raw lines are encrypted with the platform's tenant-scoped key management/envelope-encryption facility. | Pending; expected at the candidate-store/quarantine boundary in the Synflux extraction consumer (ADR-044 decision 6). | Automated test writes a quarantine record and asserts ciphertext at rest; key-scope test proves one tenant key cannot decrypt another tenant's object. | andreminin |
| **Tenant isolation** | Storage and authorization enforce tenant isolation; no cross-tenant read, export, or replay. | Pending; extraction-consumer authorization plus candidate-store partitioning. | Negative test: tenant B cannot read, export, or replay tenant A's quarantine item by ID, query, or export job. | andreminin |
| **Audit** | Every read, export, replay, and disposition records actor, time, reason, and source/segment identity. | Pending; candidate-store audit events. | Replay a scripted read/export/replay/disposition sequence and assert one audit record each with all five fields; assert ordinary application logs contain no raw quarantine content. | andreminin |
| **Retention (30/90)** | Default retention is 30 days; hard maximum is 90 days absent an approved legal/security exception; expiry is automatic. | Pending; scheduled retention job over quarantine metadata. | Clock-controlled test: the item remains readable through the approved default window and is expired/removed at its end; no item exceeds 90 days without a recorded exception. | andreminin |
| **Source-deletion cleanup** | Deleting the associated source generation cascades to quarantine payloads and derived raw prompts/responses. | Pending; source-deletion consumer plus candidate-store cascade. | Test deletes a source generation and asserts: zero quarantine rows/payloads for that generation; derived raw prompts/responses gone; audit disposition preserved per policy; cleanup is idempotent; no other generation or tenant is affected. | andreminin |

## Capture directory controls (diagnostic)

`LLM_EXTRACTION_CAPTURE_DIR` writes raw model output for diagnosis. It is separate from the quarantine work queue but holds the same class of tenant-adjacent data. Until the controls below are implemented and tested, capture is **diagnostic-only and disabled by default**; a configured path alone must not enable it in production.

| Control | Required behavior | Current state | Verification |
|---|---|---|---|
| **Tenant isolation** | Capture storage/path is tenant-scoped; no shared production directory; cross-tenant read/export fails. | Pending; shared diagnostic directory was the pre-governance behavior. | Negative test: tenant B cannot read/export tenant A's capture. |
| **Encryption at rest** | Same tenant-scoped key management/envelope encryption as the quarantine controls; no plaintext production capture. | Pending; blocked on the platform KMS facility. | Ciphertext-at-rest and key-scope tests. |
| **Audit** | Read/export/replay/disposition records actor, time, reason, and source/segment identity. Capture is write-only by default; raw content never enters ordinary logs. | Pending; filesystem-only diagnostic access. | Scripted access test; log scan. |
| **Retention (30/90)** | Default 30 days; hard maximum 90 days absent an approved exception; automatic expiry. | Pending. | Clock-controlled expiry test. |
| **Source-deletion cleanup** | Deleting the associated source generation cascades to capture payloads; cleanup is idempotent and does not touch other generations/tenants. | Pending. | Delete-generation test with a second generation/tenant control. |
| **Production enablement gate** | `LLM_EXTRACTION_CAPTURE_ENABLED` defaults to `false`; a path alone does not enable capture. Production enablement requires the controls above and tests to pass. | Gate added; storage controls pending. | Config test: path set + flag false -> capture disabled. |

**Owner:** andreminin. **Implementation:** pending a tenant-scoped `CaptureStore`; this section is the control matrix that storage design must satisfy.

**Relationship to #826:** capture and quarantine must not become two ungoverned stores. #826 must link this section and use the same tenant, retention, audit, and source-deletion controls. Tests for capture controls will be added to the [quarantine test plan](./ogce-quarantine-test-plan.md) when the store exists.

## D6 readiness

D6 is now tracked as a traceability-only test plan: [OGCE quarantine control test plan](./ogce-quarantine-test-plan.md). **andreminin owns every control**; D6 ownership and traceability do not change this specification's status or ADR-044's status.

## Boundaries

- This specification sets no gate threshold, retention exception, or authorization policy beyond what ADR-044 and the v1.36 proposal state.
- Quarantine remains an operational work queue, not a write-only log; raw quarantine data is not copied into ordinary application logs.
- No new deployable is authorized.
