# Demo graph endpoint contract

`GET /v1/demo/graph/neighbors/{chunk_id}` is demo-only and requires the demo tenant token. The chunk id contains `#`, so callers must encode it as `%23`.

## Response fields

| Field | Meaning |
|---|---|
| `chunk_id` | The center chunk being viewed. |
| `entities` | Entity nodes attached to the center: hard entities in df 3–15. |
| `neighbor_chunks` | Sorted distinct chunk IDs connected to the center via at least one entity. Convenience list for the outer ring. |
| `neighbors` | Edge list: one object per neighboring chunk, with the connecting entities in `via`. This is the drawing source, not a duplicate of `neighbor_chunks`. |
| `neighbor_count` | `neighbors.length`; equal to `neighbor_chunks.length`. Used for the panel badge. |
| `by_kind` | Number of distinct neighboring chunks reached by each `entity_kind`. |

Response example:

```json
{
  "chunk_id": "0110d461-0321-5085-874a-ec716a610909#0",
  "neighbor_count": 12,
  "entities": [
    {"canonical_form": "2026", "type": "date", "entity_kind": "year", "df": 5}
  ],
  "neighbor_chunks": ["03219438-50b5-58c7-8aea-44378f987de8#0"],
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

Known chunks with no graph-eligible entities return 200 with `neighbor_count: 0`, `entities: []`, `neighbor_chunks: []`, and `neighbors: []`. Unknown chunks return 404. The graph is visualization-only: hard entities, df 3–15; no soft entity becomes an edge. It does not participate in ranking. Coverage is 69/634 chunks (11%).
