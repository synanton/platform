# P0-1 decision — generation consistency model (2026-09-26)

**Model: persisted-or-process active-generation pointer + generation-validated
writes + generation-filtered reads.** One invariant across rebuild/upsert/delete/
search, enforced per adapter — no split-brain between the three operations.

## Rules

1. **Pointer.** Exactly one active generation per scope (global `*` in this PoC).
   YDB persists it (`generations` table, single `*` row) — P2-3 dissolved there.
   In-memory/Cassandra keep a process-local pointer — accepted test-scope
   limitation, recorded here (IndexStatus accurate within process lifetime).
2. **Bootstrap.** Unset pointer adopts the first written generation
   (adopt-on-first-write). No writer can change an already-set pointer — only
   `rebuild()` flips it. Existing single-generation tests are unaffected.
3. **Writes validated.** `upsert` rejects projections whose generation differs
   from active (`CONFLICT: stale generation`). Stale replay data fails loudly,
   never mixes silently.
4. **Reads filtered.** Search returns only active-generation rows. A reader
   during promotion sees one generation or none — never mixed.
5. **Promotion is atomic where the backend allows.** YDB: pointer flip +
   optional wipe in one serializable tx. In-memory/Cassandra: flip + wipe
   under the adapter lock / sequentially (documented, test-scope).
6. **Delete unchanged.** Generation-scoped delete semantics stand; it now
   composes with 1–5 instead of floating beside them.

## Port impact

`SynquestIndexWriter.upsert` gains documented validation: implementations reject
stale-generation writes. Delete semantics unchanged. (Proposal-subordinate PoC
port clarification, recorded here — not a normative design change.)

## P2-3 resolution (coupled, decided here)

- YDB: defect dissolved (persisted pointer; `rebuild → restart → status`
  reports correctly).
- In-memory/Cassandra: accepted test-scope limitation (process-local pointer).
  If either graduates beyond test/dev scope, persistence becomes a defect again.

## Regression tests (both required)

- G1 write → G2 rebuild → G2 write → search all three supported modes →
  G1 invisible everywhere.
- Search traffic during the promotion window → every result set consistently
  G1 or G2, never mixed.
