# YDB-POC-010 — Escalation packet (Architecture §14–15 gate)

**Status:** Escalated 2026-09-26 — decision requested by **2026-10-03**
**Owner (driver):** Platform Engineering, YDB-PoC workstream
**Decider:** Architecture (1.27 event-schema owner + 1.32 contract owner)

## Written ask

Confirm one of:

- **(a) Frozen:** 1.27 event schema + 1.32 Operation/error contracts are frozen as
  implementation contracts. Effect: 021/024A/024B open; provisional markings in
  `011-provisional-followup.md` re-validated and lifted where unchanged.
- **(b) Not frozen:** 1.27/1.32 remain in flight. Effect: PoC continues in
  **throwaway scope** (no domain-API pre-commitment); 021/024A/024B run as
  evaluation-only behind the provisional file.

"No answer by 2026-10-03" defaults to (b) — engineering does not stall on the gate.

## Why this is now the critical path (and a different conversation)

Phase-0 engineering is complete (001–009, 011, 012–020, 037–040; 004/006/038
closed). Two findings change the frame Architecture is deciding in:

1. **The baseline itself is non-conforming on Must rows** (004 matrix: highlights
   absent, metadata operators absent, tie-breaking unspecified). The decision is
   no longer "throwaway vs frozen PoC against a conforming baseline" — it is
   "which adapter path meets the requirement first." That question belongs to
   Architecture with this packet attached.
2. **Must rows gate the requirement, not the baseline** (004 gating rule), and
   unmeasured metrics gate absolute targets (vector Recall@10 ≥ 0.95). Any freeze
   decision should ratify these two rules or amend them explicitly.

## Throwaway-fallback cost (bounded, for full cost information)

If (b): re-validate the three provisional surfaces at freeze
(`PublicationIntent`, `StorageErrorKind`, `StorageException` — all annotated and
listed), reshape or lift, re-run affected contract suites. Estimated ≤ 2 days;
no production code depends on the provisional shapes (port-owned types are stable).
The fallback is cheap *because* of the 011 provisional file — cite it, don't redo it.

## What unblocks on (a)

021, 024A, 024B open immediately behind green 020+039. 029/032 follow on 038.
040 work proceeds to its 021/024-close target either way.
