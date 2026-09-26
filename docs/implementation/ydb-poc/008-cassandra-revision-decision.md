# YDB-POC-008 — Cassandra Revision-Path Conformance Decision

**Status:** Closed (decision recorded; exception tracked, not estimated away)
**Date:** 2026-09-26
**Proposal refs:** §9.1 (atomicity), §12.2, §15 acceptance

## Decision: option (b) — Cassandra recorded non-conforming for revision/delete

Rationale: `putDocumentRevision` requires multi-table atomicity (document + chunks +
provenance + publication record) that Cassandra cannot provide. Per the port contract,
the adapter reports `supportsTransactions == false` and fails revision/delete calls
with `UNSUPPORTED` instead of silently weakening the guarantee (implemented in
`CassandraSynvaultStore`; contract suite verifies the branch).

## Consequences (tracked, not deferred)

1. **Outcome 4 qualified.** "Cassandra remains the implementation" is available only
   for the metadata/persistence path (`putDocument`/`getDocument`/chunk/provenance
   reads). It is **not** available for the revision path. The Phase-6 package (036)
   must state this explicitly.
2. **§15 acceptance qualified.** "Contract tests pass for all three implementations"
   now reads "for every adapter over its supported operations (per-adapter
   conformance matrix)". Proposal §15 and §8.2 amended accordingly.
3. **039 enforcement (load-bearing).** Startup validation must reject a Cassandra
   selection whenever the deployment requires revision semantics
   (`supportsTransactions == true` required). A Cassandra-backed deployment without
   revision semantics is permitted only with this exception referenced in its config.
4. **No best-effort revision path** exists until a follow-up decision explicitly
   approves one. Check-then-write OCC stays out of the Cassandra adapter.

## Revisit trigger

If a future Cassandra lightweight-transaction design is proposed, this file reopens
and the adapter's `UNSUPPORTED` behavior is re-verified (not silently replaced).
