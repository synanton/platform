# Manual browser demo / smoke

**Scope:** this demo endpoint serves four rehearsed queries. Any other query goes through `scripts/demo/run-query.sh`; live-TEI fallback is a follow-up.

## Start
1. Start the local-real-demo stack (Cassandra, security, synquest, synflux, extractor).
2. Run `./scripts/demo/start-browser-demo.sh`; it reindexes `demo-v2` before starting Vite.
3. Open `http://127.0.0.1:5173/search`; sign in as `aminin`, tenant `demo-v2` is prefilled.

## Narrative order (hard-entity taxonomy)

1. `pdf005` — retrieval only (Dense strong; RRF follows).
2. `pdf004` — retrieval only (legs agree at top).
3. `rb003` — retrieval between-legs + graph panel (12 hard neighbors).
4. `rb010` — **demo hero**: Dense miss, RRF recovers BM25 #1 + graph panel (12/14/16).
5. `rb008` — sole failure-mode backup + graph panel; rehearse twice.

Graph is a view on retrieval, not a standalone story. `pdf012` is dropped from the graph subset (soft-only).

## Six checks
1. `Which medications are listed for substance use disorders?` → gold card shows `BM25 #7 · Dense #2 · RRF #2`; query badge `mixed`.
2. `IATF 16949` → `BM25 #1 · Dense — · RRF #1`; query badge `exact`.
3. `How does the report's glossary define a major depressive episode?` → `BM25 #1 · Dense #4 · RRF #1`; query badge `conceptual`.
4. `In what year was Globex Manufacturing founded and where is it headquartered?` → `BM25 #3 · Dense #1 · RRF #2`.
5. Force expiry: in DevTools console set `sessionStorage.setItem('synanton.accessToken', '<expired-token>')`, reload, search. Get the token with `python3 scripts/demo/expired-token.py`. Expect one retry, success, and console line `401 → minted fresh token → retry`.
6. Refresh the browser; search still works without manual login.

## Scope
The demo endpoint is **cached-query only** for Slice 1 (option A). Impromptu queries are run through `scripts/demo/run-query.sh`; live-TEI fallback is a follow-up (option C). Graph is not part of this smoke.

Record observations in `docs/acceptance/slice-1-attribution-2026-10-27.md`.


## Graph narrative (optional)

If graph is shown, label it **temporal/identifier co-occurrence**, not a knowledge graph. `rb010`/`rb008` have 2 IATF identifier neighbors plus years; `rb003` is year-only. Do not claim section bridges; zero section-ref neighbors were observed in the demo gold chunks. Section refs exist (df=3) only in legal casebook chunks. Ontology + retrieval attribution remain the headline. Use a 30-second view after the retrieval attribution, not a graph story.


## Demo-day 503 recovery

If search returns 503 after a synquest restart, the tenant searcher map is empty even though `/health` is ready. Mint a demo token and reindex:

```bash
TOKEN=$(curl -fsS -X POST "$SECURITY/v1/auth/tenant" \
  -H 'Content-Type: application/json' \
  -d '{"principal_id":"demo-runner","tenant_id":"demo-v2","capabilities":[]}' \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["token"])')
curl -fsS -X POST "$SYNQUEST/reindex?tenant=demo-v2" \
  -H "Authorization: Bearer $TOKEN"
```

Expected: `{"status":"done","tenant":"demo-v2"}` in ~10s, then search works. `start-browser-demo.sh` performs the same step.


## Graph API pre-check

After reindex, run `./scripts/demo/check-graph-neighbors.sh`; expected: `rb010` 12/14/16, empty gold chunk 0 neighbors, unknown chunk 404. `rb003` is 12 and `rb008` is 14.

Graph panel content is query-specific: only the gold result of `rb003`, `rb010`, or `rb008` has a non-empty neighborhood. Click the gold result; other result cards intentionally show the empty state.

Corpus graph coverage is **69/634 chunks (11%)**. This is a property of the hard-entity df 3–15 taxonomy, not a bug. Most result panels are empty by design.
