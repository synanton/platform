# Demo graph endpoint contract

`GET /v1/demo/graph/neighbors/{chunk_id}` is demo-only and requires the demo tenant token. The chunk id contains `#`, so callers must encode it as `%23`.

Response:

```json
{
  "chunk_id": "0110d461-0321-5085-874a-ec716a610909#0",
  "neighbor_count": 12,
  "neighbors": [
    {
      "chunk_id": "03219438-50b5-58c7-8aea-44378f987de8#0",
      "via": [
        {"canonical_form": "2026", "type": "date", "entity_kind": "year", "df": 5}
      ]
    }
  ],
  "by_kind": {"year": 12}
}
```

Unknown chunks return 404. The graph is visualization-only: hard entities, df 3–15; no soft entity becomes an edge. It does not participate in ranking.
