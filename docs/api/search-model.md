# Search Resource Model

**Status:** Frozen 2026-10-17 — W3 artifact (WS0 contract/governance)
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
| `next_page_token` | string | opaque continuation token (§45); **Gen-B** — absent in Gen-A (single page) |

**No `answer` field.** A synthesised answer is a separate resource at
`/v1/answers` (Appendix A, Appendix G) — 1.30-owned per ADR-014, exposed via
1.32. The search result carries evidence; the answer surface carries synthesis.

## Pagination

Continuation-token based (§45), **Gen-B**. The response carries
`next_page_token`; the next request carries `page_token`. The two land
together — Gen-A returns a single page with no token. The token **must be bound
to the relevant search and security context**: a token issued to one
tenant/principal is not valid for another. Invariant 5's security-context-aware
pagination, applied to search.

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
| `results[]` + `metadata` (single bounded page) | `page_token` (request), `next_page_token` (response), facet metadata, ranking profiles |

**Gen-A returns a single server-bounded page and emits no `next_page_token`.**
Pagination is Gen-B: the request-side `page_token` and the response-side
`next_page_token` land together, so a token is never emitted that a Gen-A
client cannot present. This keeps the request and response sides consistent —
a continuation token without a request-side counterpart is unusable.

## Naming

The response array is `results[]` — Design 1.32 §43, and the design is frozen
(2026-10-15): `hits[]` is not available without a versioned design revision.
This draft conforms to the frozen name. If `hits` is ever intended, it is a
deliberate divergence requiring a design revision, not a silent rename.

## Gen-A projection notes (2026-10-18)

Recorded with the first Gen-A implementation (#255, SNTP-035) so a future
reader can tell frozen text from projection decisions:

- `object_id` is the canonical chunk id `{content_ref}:{ordinal}`, ordinal
  zero-based. The separator is `:` (RFC 3986 pchar, absent from UUIDs) so
  `provenance_ref` needs no client-side encoding — a `#` separator would be a
  URL fragment and never reach the server. The id is stable for unchanged
  content because chunks are content-addressed: identical bytes derive the
  same chunk ordinal.
- `results[].source_version_ids` is an **additive extension** beyond the
  frozen shape: a projection of the chunk schema's provenance set (set
  attribution, #292), not new design surface. `provenance_ref` remains the
  normative resolution path.
- `provenance_ref` resolves via `GET /v1/knowledge/{object_id}/provenance`
  while the producing run is COMPLETED (manifest visibility); otherwise 404 —
  a provenance reference never resolves to invisible knowledge.
- Tenant and principal are derived only from the verified Bearer JWT. A
  tenant in the request body or query is ignored, per the Security section —
  the API creates no parallel access-control system.
