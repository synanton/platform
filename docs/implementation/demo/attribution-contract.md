# Demo attribution contract (Slice 1)

**Endpoint:** `POST /v1/demo/search` — demo-only, separate from the frozen public `/v1/search` contract. Body: `{"query": "..."}`. Optional `top_k`, default 20.

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
- `legs_present` is a convenience only, fixed order `["bm25", "dense"]`. The UI must render from rank fields, not from this array.
- `final_score` is a JSON number, not a string. The server does not format/round; the UI formats for display.
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

1. 2-leg happy path: `abs(bm25 + dense - final) < 1e-9`.
2. Dense miss: `dense_contribution == 0.0` exactly and `bm25_contribution == final_score`.
3. BM25 miss: symmetric to (2).
4. Direct computation: `bm25 == 1/(20+rank)` and `dense == 1/(20+rank)`; catches swapped contributions.
5. Independent final score: sum-of-contributions and `Σ 1/(20+rank)` agree within `1e-9`.

## UI badge strip

Fixed left-to-right order: `BM25 #7 · Dense #2 · RRF #2`; missing leg `Dense —`. RRF value is `final_score` formatted for display, not server-rounded.
