---
title: "OGCE Quarantine Control Test Plan"
status: "current"
last_reviewed: "2026-11-22"
---

# OGCE Quarantine Control Test Plan

**Decision:** [ADR-044: Ontology-Grounded Chunk Extraction](../architecture/decisions/adr-044-ontology-grounded-chunk-extraction.md)
**Control specification:** [OGCE quarantine data-handling controls](./ogce-quarantine-controls.md)
**Gate register:** [OGCE acceptance gates](../quality/ogce-gates.md)
**Owner:** andreminin

Status mirrors ADR-044; see ADR-044 for transition criteria. This is a test-plan traceability entry, not test implementation. No test code, harness, fixture, or runtime change is included, and no control is claimed verified.

## Scope

Each row maps one control from the [quarantine control specification](./ogce-quarantine-controls.md) to the assertions a future implementation must satisfy. Test IDs are stable traceability keys; they do not imply a test exists yet.

## Traceability

| ID | Control | Level | Assertions | Fixture / preconditions | Expected evidence | Status |
|---|---|---|---|---|---|---|
| **QUAR-ENC-01** | Encryption at rest | Integration / security | A written quarantine payload and its bounded raw lines are ciphertext at rest; encryption uses the tenant-scoped envelope key; a key-scope negative proves tenant A's key cannot decrypt tenant B's object. | Two tenants, one quarantine record each, distinct tenant keys. | Ciphertext-at-rest assertion plus key-scope denial; no plaintext in storage. | Not implemented — traceability only |
| **QUAR-ISO-01** | Tenant isolation | Security / integration | Tenant B cannot read, export, or replay tenant A's item by ID, query, or export job; denials do not reveal item existence or content. | Two tenants, known item IDs, read/export/replay endpoints. | Denial responses for cross-tenant access; no data leak in response or logs. | Not implemented — traceability only |
| **QUAR-AUD-01** | Audit | Integration | Every read, export, replay, and disposition emits exactly one audit record with actor, time, reason, and source/segment identity; ordinary application logs contain no raw quarantine content. | Scripted read / export / replay / disposition sequence. | One audit record per action with all five fields; clean log scan. | Not implemented — traceability only |
| **QUAR-RET-01** | Retention (30/90) | Integration (clock-controlled) | An item remains readable through the approved default window and is expired or removed at its end; no item exceeds 90 days without a recorded approved exception; expiry is automatic. | Fixed clock and quarantine metadata; one item at each boundary; one approved exception. | Expiry state transitions at the default boundary and the 90-day hard maximum; exception is recorded. | Not implemented — traceability only |
| **QUAR-DEL-01** | Source-deletion cleanup — cascade | Integration | Deleting a source generation leaves zero quarantine rows/payloads and zero derived raw prompts/responses for that generation; audit disposition is preserved per policy. | Source generation with an accepted and a quarantined candidate. | Post-delete queries return no generation payload; audit disposition remains. | Not implemented — traceability only |
| **QUAR-DEL-02** | Source-deletion cleanup — idempotency | Integration | Repeating the source-generation deletion is idempotent and does not remove other generations or tenants. | Two source generations across two tenants, deletion invoked twice. | Second deletion succeeds with no additional removals; the second generation and tenant are intact. | Not implemented — traceability only |

## Promotion rule

A row changes from **Not implemented — traceability only** only when a named test exists and passes in CI. The row then links the test class or path and the evidence run. This plan does not authorize runtime work, does not set gate thresholds, and does not change ADR-044 or gate status.

## Non-goals

- No test implementation, harness, fixture, or runtime change.
- No retention, authorization, encryption, or gate-policy decision beyond the control specification.
- No ADR-044 status transition.
