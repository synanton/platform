# Spike: score-based fusion for hybrid retrieval (Fusion option B)

**Status:** CLOSED 2026-10-12 — do not implement (evidence below).
**Trigger:** F-tune (2026-10-11): RRF structurally buries single-leg hits
(q2 outsourcing: dense #3 + lexical #12–18 → fused out); `rrf_k` inert;
depth exonerated; union boost trades misses between queries instead of
removing them. Rank-fusion has no more free parameters worth fitting at n≤28.
**Constraint:** PN-11 (score spaces don't transfer) — any score fusion must
not bake engine-specific score semantics into the portable path.

## 1. Problem restated

RRF fuses ranks: `sum 1/(k+rank)`. A hit strong in one leg and weak in the
other converges to ~half a dual-leg hit at any k. q2's outsourcing chunk
(dense cosine 0.83, BM25 0.45) loses to dual-mediocre competitors on every
tested k (10–120) and depth (10–100). Union boost 2.0 fixes q2 but breaks q7.
The failure is information loss: ranks discard *how strong* each leg was.

## 2. Candidate design (not approved for build)

Per-leg score normalization → weighted sum → top-K:

1. **Dense leg:** cosine similarity already in [-1, 1] and comparable across
   engines *for the same model* (not across models — record model per leg).
2. **Lexical leg:** BM25 is unbounded and collection-dependent. Normalize per
   query: `score / max_leg_score` (min-max over the fetched leg, floor 0).
   Per-query normalization keeps it portable (no global BM25 scale assumed).
3. **Fuse:** `w_d * dense_norm + w_l * lex_norm`, defaults 0.5/0.5. Weights
   are fit, not picked: grid-search on gold (needs n≥50 to beat noise —
   we have 28; DO NOT fit on 28).
4. **Missing-leg rule:** a hit absent from a leg scores 0 there (explicit,
   replacing RRF's implicit half-weight). This is the structural fix q2 needs:
   dense 0.83 + lexical ~0.1 beats dual-0.5s without any boost knob.

## 3. What must exist before implementation

- [ ] Calibration corpus: n≥50 judged queries (we have 28). Fitting 2+
      weights on 28 overfits to quirks (see boost 1.5/2.0 trade curve).
- [ ] Per-engine score audit: confirm cosine ranges per adapter (PG returns
      -distance today — negate at the port boundary, never in fusion).
- [ ] Min-score semantics per mode (007-4 finding): neutral floors must be
      redefined in normalized space.
- [ ] Back-compat: RRF stays default until score-fusion beats it on gold by
      a margin, same n, same corpus (no switching on vibes).

## 4. Recommendation

Defer implementation until the calibration corpus reaches n≥50. The union
boost (default-off) covers experimentation meanwhile. Revisit trigger: gold
expansion round 4, or a query class where RRF demonstrably inverts quality
ordering (stronger than q2's top-5 miss).

## 5. Outcome (2026-10-12): grid search ties RRF — do not implement

- Ran the calibration the gate asked for: n=50 human gold, per-leg
  min-max normalization, weights swept 0.00–1.00 in 0.05 steps over the
  dense∪lexical union, top-5 scored.
- Best weight (w_d=0.45) scores **0.9433 — exactly the RRF baseline**.
  Score-fusion ≡ rank-fusion here; no weight beats RRF, pure-dense (1.00)
  and pure-lexical (0.00) both lose.
- Verdict: option B as specced has no headroom on this corpus. Close the
  spike; revisit only with (a) deeper legs (top-100, not top-10 — the union
  may be the binding constraint, untested), or (b) a corpus where RRF and
  score-fusion actually disagree (here they converge).
