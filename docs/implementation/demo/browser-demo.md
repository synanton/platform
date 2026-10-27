# Manual browser demo / smoke

**Scope:** this demo endpoint serves four rehearsed queries. Any other query goes through `scripts/demo/run-query.sh`; live-TEI fallback is a follow-up.

## Start
1. Start the local-real-demo stack (Cassandra, security, synquest, synflux, extractor) and rebuild the `demo-v2` projection if needed.
2. Run `./scripts/demo/start-browser-demo.sh`.
3. Open `http://127.0.0.1:5173/search`; sign in as `aminin`, tenant `demo-v2` is prefilled.

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
