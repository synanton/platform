# Demo attribution contract (Slice 1)

**Endpoint:** `POST /v1/demo/search` — demo-only, separate from the frozen public `/v1/search` contract. Body: `{"query": "..."}`. Optional `top_k`, default 20.

`/v1/demo/search` is a demo surface, not the production search contract. It carries attribution fields that `/v1/search` does not. Promoting attribution to the production endpoint requires a separate contract and does not reuse this path.

**Auth:** requires the same `POST /v1/auth/tenant` token as `/v1/search`; results are scoped to the tenant in the token. Do not expose an unauthenticated demo endpoint on a NodePort.

**Runner auth:** `run-query.sh` mints a fresh token per invocation via `POST /v1/auth/tenant`, logs `tenant_id` and TTL, and uses that token for the search call. The UI uses the existing `/v1/auth/tenant` path from the UI switch (#447); it must not call the demo endpoint without a token.

**Shape:** flat. The UI binds three badges directly. Migrate to a contribution map only when a third leg exists.

## Response

```json
{
  "query_type": "mixed",
  "results": [
    {
      "chunk_id": "4e54d1ae-7e10-528c-9bcb-e49160c1145d#26",
      "final_rank": 2,
      "final_score": 0.0824919,
      "bm25_rank": 7,
      "bm25_contribution": 0.0370370,
      "dense_rank": 2,
      "dense_contribution": 0.0454545,
      "legs_present": ["bm25", "dense"],
      "provenance": { "...": "existing provenance fields" }
    }
  ]
}
```

- `final_rank` is 1-based.
- `bm25_rank` / `dense_rank` are integers when present; **null**, never absent and never `-1`/`0`, when the leg missed.
- Missing leg contribution is exactly `0.0`; the field is present.
- `legs_present` is a convenience only, fixed order `["bm25", "dense"]` filtered to present. Single-leg responses contain exactly the returned leg (`["bm25"]` or `["dense"]`). The UI must render from rank fields, not from this array.
- Query-type fallback: exact → conceptual → **mixed**; if neither exact nor conceptual matches, return `mixed`, never null/undefined.
- `final_score` is a JSON number, not a string. The server does not format/round; use a raw double or BigDecimal without scale reduction. The UI formats for display.
- Empty result set: HTTP **200** with `{"results": []}`. Do not return 404 for a supported query with no hits.
- No `gold` field. Gold remains a runner/evaluation annotation.
- `k = 20`; contribution = `1 / (20 + rank)`.
- `final_score = bm25_contribution + dense_contribution`.

### Dense-miss example (`rb010`)

```json
{
  "chunk_id": "0110d461-0321-5085-874a-ec716a610909#0",
  "final_rank": 1,
  "final_score": 0.0476190,
  "bm25_rank": 1,
  "bm25_contribution": 0.0476190,
  "dense_rank": null,
  "dense_contribution": 0.0,
  "legs_present": ["bm25"]
}
```

## Tie-break

`(final_score DESC, bm25_rank ASC, chunk_id ASC)`; missing BM25 rank sorts after present ranks.

**Bias note:** the rule favors BM25-present chunks over Dense-only chunks on final-score ties. This is a deterministic demo-ordering constraint, not a ranking-quality claim.

## Contribution tests (backend)

1. 2-leg happy path: `abs(bm25 + dense - final) <= 1e-12`.
2. Dense miss: use `Double.compare(dense_contribution, 0.0) == 0`; `bm25_contribution == final_score` exactly.
3. BM25 miss: symmetric to (2).
4. Direct computation: `bm25 == 1/(20+rank)` and `dense == 1/(20+rank)` within `1e-12`; catches swapped contributions.
5. Independent final score: sum-of-contributions and `Σ 1/(20+rank)` agree within `1e-12`. Document the epsilon: it tolerates Java arithmetic order but catches a wrong formula.

### Serializer and edge tests (raw JSON, not parsed object)

6. Dense-miss serialization: raw response text contains `"dense_rank":null` and `"dense_contribution":0.0`; a parsed-object assertion is insufficient because `NON_NULL`/`NON_DEFAULT` omits them.
7. BM25-miss serialization: symmetric (`"bm25_rank":null`, `"bm25_contribution":0.0`).
8. Dense-only tie: **unit test on the comparator**, not real data. Two synthetic records with `bm25_rank=null`, equal `final_score`, same synthetic dense rank; assert `chunk_id ASC` order. Two chunks cannot share a real dense rank, so do not search the corpus for this case.
9. Single-leg presence: raw response `legs_present` has exactly one entry matching the returned leg.
10. Auth failures on `/v1/demo/search`: no token → 401; tenant A token returns only A; unknown tenant → zero/404, not 500.

### Runner/backend parity

Run `pdf005`, `pdf004`, `rb003`, `rb010` through both `scripts/demo/run-query.sh` and `POST /v1/demo/search`; assert identical chunk-id order, `bm25_rank`, `dense_rank` (int/null), and `legs_present`. Do **not** compare floats in parity; contribution sums are covered by the backend tests (spot-check ±1e-12 if desired). The runner and parity test mint fresh tokens per invocation/endpoint call; a slow CI run must not reuse a 900s token. Compare structured fields, not text/JSON bytes.

## Slice 1 done

- Four retrieval queries return the flat shape; five contribution invariants pass.
- Raw JSON has literal null ranks and `0.0` missing contributions.
- UI attribution strip renders `rb010` as `BM25 #1 · Dense — · RRF #1`.
- Search retries once on 401 by minting a fresh token; only the retry failure surfaces.
- Query-type badge shows exact/mixed/conceptual; `rb010` exact, `pdf004` conceptual, `pdf005` mixed.
- Runner and backend fused orders/ranks are identical for the four queries.

## Backend test count (minimum)

5 contribution + 2 raw serializer + 1 Dense-only synthetic tie + 1 single-leg + 1 legs_present ordering + 1 empty-results + 3 auth + 1 query-type fallback = **15 tests**.

## UI badge strip

Fixed left-to-right order: `BM25 #7 · Dense #2 · RRF #2`; missing leg `Dense —`. RRF value is `final_score` formatted for display, not server-rounded.
