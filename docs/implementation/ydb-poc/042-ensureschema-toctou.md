# 042 — ensureSchema TOCTOU Guard (H2 Follow-Up)

**Status:** Filed (not blocking; trigger after H2 matrix closes)
**Origin:** H2 matrix 2026-09-29 — drop-then-create raced scheme deletion;
local wait-for-absence fixed the test, shared `ensureSchema` still exposed.

## Problem

`ensureSchema` probes existence via `SELECT LIMIT 0` and skips creation on
success. After a DROP, scheme deletion propagates asynchronously: the probe
can see the ghost and skip, after which TRUNCATE (or index DDL) fails on
nothing. Presence misread — the inverse of the phantom-SKIP family
(absence misread as pass).

## Fix (same shape as the H2-local one)

After any drop-then-create sequence, poll until all tables are actually
absent (60s cap, fail loudly) before creating. Options:

- Promote the H2-local `waitForAbsent` into `YdbSearchSchema` as
  `awaitAbsent(client, prefix, timeout)` and call it from `ensureSchema`
  callers that drop first — or from `dropSchema` itself (changes its
  best-effort contract; decide explicitly).
- Keep `ensureSchema` untouched; document that callers dropping first must
  await absence (weaker — relies on every future caller reading the doc).

## Acceptance

- Drop → recreate → TRUNCATE succeeds deterministically (no ghost window).
- Timeout still fails loudly, never silently skips creation.
- Existing callers unaffected (no-behavior-change when tables simply absent).

## Out of scope

- Generation-pointer coverage (fixed separately: `dropSchema` now drops
  `_generations`; committed on the 041 branch).
