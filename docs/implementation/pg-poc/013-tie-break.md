# PG-POC-013 — Tie-Break Verification (Gate B Input)

**Status:** Verified with adapter obligation (see below)
**Date:** 2026-09-27
**Probe:** `TieBreakTest` — 50 identical embeddings / 50 identical texts, one tenant, 5 repeated runs each leg

## Results

| Leg (mechanism in use) | Stable across 5 runs | Server order chunkId-asc |
|---|---|---|
| Vector, btree-restrict-then-sort | Yes | **No** |
| Lexical, tsvector+GIN + `ts_rank` | Yes | **No** |

## Reading (precise scope)

Stability across runs is necessary but not sufficient: the server's tied
order is deterministic yet **not** chunkId-asc on either leg. The frozen rule
— `(score desc, chunkId asc)` applied post-retrieval on every adapter — is
therefore a real adapter obligation on PG, not a no-op passthrough. PG-POC-007
must implement the post-retrieval sort; convergence (PG-POC-006/014) compares
with tolerance on tied scores regardless.

## Trivial-green caveat

The vector leg exercises btree-sort, deterministic by construction over a
bounded set. HNSW tie order is untested here by design and rides with the
Phase 4 HNSW-at-scale re-verification (005-gate-a.md caveat). This file must
not be cited as covering HNSW ties.

## Gate B input

Determinism verified on the paths the planner uses at PoC scale. Input ready.
