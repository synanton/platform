# YDB-POC-040 — Rewire call sites off ingestion-cache; retire CQL exception

**Status:** Open (target: 021/024 close)
**Owner: Platform Engineering, YDB-PoC workstream** — assigned 2026-09-26.
A named human countersigns at Phase-0 exit review; until then the workstream owns
the target. If unowned at Phase-1 entry, 021/024 do not start (escalate, do not drift).
**Tracks:** `011-cql-exception.md`

## Countersign (Phase-0 exit, recorded in-commit 2026-09-26)

- Workstream accepts the target (complete by 021/024 close) and the constraints
  (bugfix-only `ingestion-cache`, no new importers — review-enforced from this commit).
- Remaining formality: a named human re-confirms at the Phase-0 exit review.
  That confirmation is a checklist line in the exit review, not a new ticket —
  if the review happens without it, the exit is not declared.
- If 021/024 open while this countersign is still workstream-only, the opening
  decision must name the human owner explicitly. No silent carry-over.

## Scope

1. Rewire `ManifestController` / `SynvaultApplication` (synvault) and
   `LuceneIndexBuilder` (synquest) from direct `ingestion-cache` imports onto the
   `SynvaultStore` / `SynquestEngine` ports (via `synvault-cassandra` /
   024A adapters as the bridge — behavior-preserving).
2. Verify: no production module outside `*-cassandra` adapters imports
   `org.synanton.ingestioncache` or the Cassandra driver.
3. Retire `011-cql-exception.md` (status → Closed) or promote `ingestion-cache` to
   a sanctioned Content-Cache-1.26-adjacent plane by explicit Architecture decision
   (proposal §8.2 amendment in that case — not the default path).

## Acceptance

- `grep -rn "ingestioncache\|datastax" java/<service>/src/main` returns nothing
  outside adapter modules.
- Existing service tests green; behavior unchanged (no feature work in this ticket).
