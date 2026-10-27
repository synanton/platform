# Demo attribution contract (Slice 1)

**Endpoint:** `POST /v1/demo/search` — demo-only, separate from the frozen public `/v1/search` contract. Body: `{"query": "..."}`. Optional `top_k`, default 20.

`/v1/demo/search` is a demo surface, not the production search contract. It carries attribution fields that `/v1/search` does not. Promoting attribution to the production endpoint requires a separate contract and does not reuse this path.

**Auth:** requires the same `POST /v1/auth/tenant` token as `/v1/search`; results are scoped to the tenant in the token. Do not expose an unauthenticated demo endpoint on a NodePort.

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
- `legs_present` is a convenience only, fixed order `["bm25", "dense"]`. Single-leg responses contain exactly the returned leg (`["bm25"]` or `["dense"]`). The UI must render from rank fields, not from this array.
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

### Serializer and edge tests (raw JSON, not parsed object)

6. Dense-miss serialization: raw response text contains `"dense_rank":null` and `"dense_contribution":0.0`; a parsed-object assertion is insufficient because `NON_NULL`/`NON_DEFAULT` omits them.
7. BM25-miss serialization: symmetric (`"bm25_rank":null`, `"bm25_contribution":0.0`).
8. Dense-only tie: two chunks with `bm25_rank=null`, equal `final_score`, assert deterministic `chunk_id ASC` order.
9. Single-leg presence: raw response `legs_present` has exactly one entry matching the returned leg.

### Runner/backend parity

Run `pdf005`, `pdf004`, `rb003`, `rb010` through both `scripts/demo/run-query.sh` and `POST /v1/demo/search`; assert identical fused order and identical per-leg ranks. The runner's comparator is the reference.

## Slice 1 done

- Four retrieval queries return the flat shape; five contribution invariants pass.
- Raw JSON has literal null ranks and `0.0` missing contributions.
- UI attribution strip renders `rb010` as `BM25 #1 · Dense — · RRF #1`.
- Query-type badge shows exact/mixed/conceptual.
- Runner and backend fused orders are identical for the four queries.

## UI badge strip

Fixed left-to-right order: `BM25 #7 · Dense #2 · RRF #2`; missing leg `Dense —`. RRF value is `final_score` formatted for display, not server-rounded.
