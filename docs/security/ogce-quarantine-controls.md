---
title: "OGCE Quarantine Data-Handling Controls"
status: "current"
last_reviewed: "2026-11-22"
---

# OGCE Quarantine Data-Handling Controls

**Decision:** [ADR-044: Ontology-Grounded Chunk Extraction](../architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Design source:** [v1.36 OGCE proposal §4.7](../architecture/proposals/v1.36/ontology-grounded-chunk-extraction.md#47-validation-quarantine-and-consolidation)
**Gate register:** [OGCE acceptance gates](../quality/ogce-gates.md)
**Owner:** aminin

Status mirrors ADR-044; see ADR-044 for transition criteria. This specification records required controls and verification methods. Implementation is pending, and no control is claimed implemented or verified.

## Control matrix

| Control | Required behavior | Implementation location | Verification method | Owner |
|---|---|---|---|---|
| **Encryption at rest** | Quarantine payloads and bounded raw lines are encrypted with the platform's tenant-scoped key management/envelope-encryption facility. | Pending; expected at the candidate-store/quarantine boundary in the Synflux extraction consumer (ADR-044 decision 6). | Automated test writes a quarantine record and asserts ciphertext at rest; key-scope test proves one tenant key cannot decrypt another tenant's object. | aminin |
| **Tenant isolation** | Storage and authorization enforce tenant isolation; no cross-tenant read, export, or replay. | Pending; extraction-consumer authorization plus candidate-store partitioning. | Negative test: tenant B cannot read, export, or replay tenant A's quarantine item by ID, query, or export job. | aminin |
| **Audit** | Every read, export, replay, and disposition records actor, time, reason, and source/segment identity. | Pending; candidate-store audit events. | Replay a scripted read/export/replay/disposition sequence and assert one audit record each with all five fields; assert ordinary application logs contain no raw quarantine content. | aminin |
| **Retention (30/90)** | Default retention is 30 days; hard maximum is 90 days absent an approved legal/security exception; expiry is automatic. | Pending; scheduled retention job over quarantine metadata. | Clock-controlled test: the item remains readable through the approved default window and is expired/removed at its end; no item exceeds 90 days without a recorded exception. | aminin |
| **Source-deletion cleanup** | Deleting the associated source generation cascades to quarantine payloads and derived raw prompts/responses. | Pending; source-deletion consumer plus candidate-store cascade. | Test deletes a source generation and asserts: zero quarantine rows/payloads for that generation; derived raw prompts/responses gone; audit disposition preserved per policy; cleanup is idempotent; no other generation or tenant is affected. | aminin |

## D6 readiness

D6 is a test-plan traceability entry, not test implementation. The source-deletion cleanup row is specific enough to author D6 assertions. **aminin owns every control**, so D6 is ready to open after this PR merges; D6 ownership and traceability do not change this specification's status or ADR-044's status.

## Boundaries

- This specification sets no gate threshold, retention exception, or authorization policy beyond what ADR-044 and the v1.36 proposal state.
- Quarantine remains an operational work queue, not a write-only log; raw quarantine data is not copied into ordinary application logs.
- No new deployable is authorized.
