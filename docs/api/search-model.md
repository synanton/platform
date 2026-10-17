# Search Resource Model

**Status:** Draft — W3 artifact (WS0 contract/governance)
**Source:** Design 1.32 §41–46 (Search API), Appendix A (resource list), Appendix F (search flow)

The resource model for `POST /v1/search`. This is the canonical search
contract; the OpenAPI spec for the endpoint `$ref`s it. The MCP search tool
(W5/D4) is a separate, deliberately divergent shape (`{query, top_k}`).

## Request

```json
{
  "query": "distributed tracing architecture",
  "filters": { "language": "en", "source_type": "documentation" },
  "limit": 20,
  "profile": "balanced",
  "consistency": { "mode": "EVENTUAL" }
}
```

| Field | Type | Gen-A | Notes |
|---|---|---|---|
| `query` | string | **yes** | required; the search query |
| `filters` | object | reserved (Gen-B) | opaque key-value; passed through to 1.31, not interpreted by 1.32 (A7) |
| `limit` | integer | reserved (Gen-B) | page size |
| `profile` | string | reserved (Gen-B) | ranking profile |
| `consistency` | object | reserved (Gen-B) | `mode`: `EVENTUAL` \| `SESSION` \| `VERSION` (§44) |

**Tenant and principal context are never read from the body** (§42). They come
only from the verified identity context (see `identity-context.md`).

Gen-A ships `{query}` only; every other field is a reserved additive property
— adding them later is not a breaking change.

## Response

```json
{
  "results": [
    {
      "object_id": "chunk_01...",
      "score": 0.92,
      "title": "Distributed Tracing",
      "snippet": "...",
      "provenance_ref": "/v1/knowledge/chunk_01.../provenance"
    }
  ],
  "metadata": {
    "strategy": "hybrid",
    "configuration_generation": 17
  },
  "next_page_token": "..."
}
```

| Field | Type | Notes |
|---|---|---|
| `results[]` | array | ranked hits |
| `results[].object_id` | string | chunk/knowledge id |
| `results[].score` | number | ranking score |
| `results[].title` | string | display title |
| `results[].snippet` | string | matched excerpt |
| `results[].provenance_ref` | URI | link to the provenance resource |
| `metadata.strategy` | string | retrieval strategy (e.g. `hybrid`) |
| `metadata.configuration_generation` | integer | index/config generation |
| `next_page_token` | string | opaque continuation token (§45) |

**No `answer` field.** A synthesised answer is a separate resource at
`/v1/answers` (Appendix A, Appendix G) — 1.30-owned per ADR-014, exposed via
1.32. The search result carries evidence; the answer surface carries synthesis.

## Pagination

Continuation-token based (§45): `next_page_token` in the response, sent back
on the next request. The token **must be bound to the relevant search and
security context** — a token issued to one tenant/principal is not valid for
another. This is invariant 5's security-context-aware pagination applied to
search specifically.

## Consistency

Optional consistency hint (§44), modes `EVENTUAL` / `SESSION` / `VERSION`
depending on implementation capability. Gen-B.

## Security

Search behavior is governed jointly by Identity 1.29 + Security 1.23 +
Search 1.31 (§46). The API **must not create a parallel access-control
system** — authorization is a candidate-eligibility prerequisite, not a
post-filter.

## Gen-A vs Gen-B

| Gen-A | Gen-B (reserved, additive) |
|---|---|
| `{query}` request | `filters`, `limit`, `profile`, `consistency` |
| `results[]` + `metadata` + `next_page_token` | facet metadata, ranking profiles, cursor semantics |

## Naming flag

Design 1.32 §43 names the response array `results[]`; an earlier direction used
`hits[]`. This draft conforms to the design (`results`). If `hits` is the
intended public name, that is a deliberate divergence from §43 and should be
recorded as such (same shape as the D1/D4 divergence note) — not changed
silently.
