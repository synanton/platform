# ADR-020: Operation Partial Outcome — Additive `outcome: FULL | PARTIAL`

**Status:** Proposed 2026-10-17
**Basis:** Design 1.32's Operation resource (Appendix B) has no field expressing a partial success, and `synanton.extraction.v1`'s `STATUS_PARTIAL` ("some items succeeded, some did not", §24) has no public Operation state. The 1.32 design is frozen (2026-10-15, "changes need a versioned design revision, never a silent edit"), so the extension is recorded here as a decision rather than edited into the design.

## Decision

1. The public Operation resource gains an additive `outcome: FULL | PARTIAL` field, present when `status = SUCCEEDED`. Absent/`null` otherwise.
2. `STATUS_PARTIAL` (extraction) maps to `SUCCEEDED` + `outcome: PARTIAL`; `STATUS_COMPLETED` maps to `SUCCEEDED` + `outcome: FULL`.
3. The design document stays frozen. This ADR is the extension record; a future versioned revision of 1.32 (e.g. 1.32.1 §55/Appendix B) incorporates the field. No silent edit to the frozen contract.

Consequences:
- **Operation model:** partiality is first-class (`outcome`), not inferred from `progress.completed < progress.total`. PAPI-3 DoD 2 ("observably mapped ... not misreported as SUCCEEDED without qualification") is satisfied by a named field.
- **Freeze discipline:** honored — the change is a dated, traceable decision, not an edit to the frozen design.
- **Clients:** branch on `outcome` rather than doing progress arithmetic; `SUCCEEDED` + `outcome: FULL` is unambiguous success.
- **Additive, not breaking:** a client ignoring `outcome` sees `SUCCEEDED`, which is still true; the field qualifies, it does not contradict.
- **Rejected:** a new public state `PARTIAL` — the design's §56 state list is fixed at eight, and a ninth state changes the state machine rather than extending the resource. Rejected: inferring partiality from `progress` alone — DoD 2 requires it observable, not derivable.
- **Neutral:** no change to the eight states, to the reconciliation of `COMPLETED`/`SUCCESS` → `SUCCEEDED`, or to the W2 error model.

## Context

`ExtractionStatus` distinguishes `COMPLETED` (all items succeeded) from `PARTIAL` (some did not). The public Operation state machine has one terminal-success state, `SUCCEEDED`. Without a field, `PARTIAL` would either be dropped or misreported as a full success. The additive `outcome` field is the minimal change that keeps both truths: the operation succeeded, and it succeeded partially. `docs/api/operation-model.md` is the artifact that applies this mapping.
