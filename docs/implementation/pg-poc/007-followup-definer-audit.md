# PG-007 Follow-up — DEFINER Audit (production migration gate)

**Status:** OPEN — PoC-safe, production-blocking. Not urgent: current PoC
callers are validated (engine binds chunk ids from port inputs and
generation ids from its own pointer reads; no raw caller text reaches the
functions except through bound parameters).
**Origin:** PG-POC-007-7a. Pattern: `pattern-notes.md PN-5`.

## Finding

Port methods without tenant context (`delete()`, `rebuild()`) require
SECURITY DEFINER functions owned by a BYPASSRLS-capable role
(`quest_chunk_tenants`, `quest_promote_flip`, `quest_reset_quest_rows`).
That role is a privilege-escalation surface: anyone who can invoke the
functions with arbitrary arguments bypasses tenant RLS. The functions'
argument validation (exact chunk+generation match; global flip with no
row filter) IS the security boundary — not the caller, not the port.

## Production checklist (before any PG backend serves real tenants)

- [ ] Functions owned by a dedicated least-privilege BYPASSRLS role (not a
      superuser, not the table owner subject to FORCE RLS surprises).
- [ ] Migration enforces ownership (assert `pg_proc.proowner`, fail deploy
      otherwise) — ownership must never depend on who ran the script.
- [ ] Argument audit: `quest_chunk_tenants` predicates stay equality-only;
      any future wildcard/prefix form needs its own review (widens the
      bypass from exact-key to set-scan).
- [ ] `REVOKE EXECUTE` from PUBLIC/default; grant execute to the engine
      role only.
- [ ] Re-test the PoC's RLS inventory (`SchemaInventoryTest` shape) against
      the production role set — the PoC proves the mechanism against
      container roles, not production's.

## Why a ticket, not a note

"Production-migration follow-up" without a tracking item is the shape that
gets lost. This file is the item: it survives the PoC and gates Phase-6
production-readiness for the PG leg.
