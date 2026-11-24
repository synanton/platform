# P0-2 — Search contract baseline

Status: draft for #859. Verified against DTO/controller code; tests not executed. Paths are repo-relative.

## Interface matrix

| Interface | Method / path | Version/stability | Request → response | Consumers |
|---|---|---|---|---|
| Public search | `POST /v1/search` | v1; frozen Gen-A | `SearchQueryRequest` → `SearchPage` | demo/integration scripts; no UI |
| Legacy search | `POST /search` | unversioned legacy | `SearchRequest` → `SearchResponse` | gateway, retrieval-eval, benchmark, scripts |
| Legacy ops | `/reindex`, `/health`, `/index/stats` | unversioned legacy | query → map/`IndexStats` | retrieval-eval/benchmark/scripts |
| Projection | `/v1/search-projection/rebuild|entities|GET` | v1 | records | demo/integration, entity projection tool |
| Provenance | `GET /v1/knowledge/{objectId}/provenance` | v1 frozen additive | path → resource | `/v1/search` clients |
| Demo | `POST /v1/demo/search` | v1 demo-only | `DemoSearchRequest` → `DemoResponse` | demo scripts; not production |
| Gateway | `POST /query` | unversioned internal | `QueryRequest` → `QueryResponse` | synapt, UI indirectly, MCP indirectly |
| Synapt | `POST /search` | unversioned; `top_results` deprecated | `SearchRequest` → gateway response passthrough | UI Chat, MCP |
| MCP | `POST /mcp` `search` | MCP 2025-03-26; D4 input | `{query,top_k}` → text | external MCP |
| Relix graph | `POST /graph/query` | unversioned | `{tenant,shape,params}` → graph response | gateway, MCP |

## `POST /v1/search`

Request: `query` required non-blank; body `tenant_id`/`tenant` rejected; `X-Tenant-ID` rejected; tenant from verified identity only. `filters/limit/profile/consistency/page_token` not in DTO and silently ignored (Gen-B reserved). Evidence: `KnowledgeSearchController.java:56-63,202-217`.

Response `SearchPage{results[]}`; hit fields: `object_id` (`{content_ref}:{ordinal}`), `score`, `title`, `snippet`, `provenance_ref`, `source_version_ids[]`, `page_start/page_end` nullable, `section` deprecated alias, `section_path[]`, `metadata.strategy` (`bm25`), `metadata.configuration_generation`. Fixed page 20; no `next_page_token`. Evidence: `KnowledgeSearchController.java:76-92,182-200`; `docs/api/search-model.md` Gen-A.

Errors:
- 400 tenant in body/header: `application/problem+json` code `TENANT_IN_REQUEST_BODY`/`TENANT_HEADER_NOT_SUPPORTED`; no request_id/retryable.
- 400 blank query: Spring default, body unknown.
- 401 filter body.
- 404 empty body unknown tenant.
- 503 empty body + `Retry-After: 5` building projection (retryable).
- 500 empty body unexpected (including embedding/rerank failures; not signalled retryable).

Evidence: `:56-99,156-159`; `TenantContextFilter.java:56-127`.

## Legacy `/search` and ops

`SearchRequest` fields: `tenant` required semantically; `query` unvalidated; `top_k` default 20; `top_k_dense/lexical` default 100; `rrf_k` 60; `rerank` false; `rerank_candidates` 50; `expand` (`"section"`); `expand_max_chunks` 8; `execution_mode` parallel|sequential. Response `SearchResponse{hits[],trace,query_usage}` with camel/snake? DTO fields: `content_ref_id,chunk_ordinal,score,score_dense,score_lexical,rank_dense,rank_lexical,snippet,source_uri,page_start,page_end,section_path(joined),heading,source_elements,token_count,structured_content,is_partial_section,ingest_usage,score_rerank,section_id,expanded_from`; trace timings/index generation; usage. `/reindex` 200 `{status:"done",tenant}`; `/health` 200/503 `{status}`; `/index/stats` `IndexStats`.

Errors: 401 blank/missing tenant; 503 starting/embedding unavailable; 500 IO; no controller tests except legacy tenant rejection. **Tenant comes from body/query without comparing to verified JWT** for legacy endpoints. Evidence: `SearchController.java:24-63`; `SearchResponse.java:7-11`; `Hit.java:8-36`; `SearchTrace.java`; `IndexStats.java:12-25`.

## Projection

`POST /v1/search-projection/rebuild` → `{status,tenant_id,chunk_count,strategy}`; idempotent.
`POST /v1/search-projection/entities` body `{entities:EntitySearchDocument[]}` → `{status,tenant,entities}`.
`GET /v1/search-projection` → `{ready,tenant_id,chunk_count,strategy}`.
Tenant from verified context; errors 401, 500 empty. `/entities` lacks controller test. Evidence: `KnowledgeSearchController.java:107-154,170-180`.

## Provenance

`GET /v1/knowledge/{objectId}/provenance`; objectId `{content_ref}:{ordinal}`; response `object_id,content_ref_id,source_version_ids[],source_identities[],content_cache_reference,source_uri,source_sha256,chunk_sha256,processing_run_id,page_start/page_end,section(deprecated),section_path[]`. Only COMPLETED/manifest-visible; tombstoned excluded; cross-tenant 404. Errors 400 malformed, 404 absent/invisible, 500 no handler. Tests strong; one test uses `#` ordinal contradiction. Evidence: `KnowledgeProvenanceController.java:49-184`.

## Demo

`POST /v1/demo/search` `{query}` (top_k documented but ignored); 400 `{code:"VALIDATION_ERROR",detail}` blank; 404 empty; 503 building; embedding failure degrades to BM25. Response `{query_type,results[]}` with `final_rank,score,bm25_rank/contribution,dense_rank/contribution,legs_present,chunk_id(UUID#ordinal),title,snippet,provenance{...}}`; K=20. Evidence: `DemoSearchController.java:26-57`; `DemoSearchService.java:42-298`.

## Gateway `POST /query`

Request `{tenant,query,top_k=10,hints{prefer_graph,prefer_retrieval}}`; tenant body-first; missing 401. Response `{hits[{content_ref_id,chunk_ordinal,score,score_dense,score_lexical,graph_promoted,snippet,source_uri}],graph_result{entities,edges,paths},answer?,execution_trace,cache_hit,acl_layers_applied}`. `cache_hit`/ACL always defaults; AclInjector unwired. Errors: 401 missing tenant; planner unavailable/both backends fail/synthesis fail → 200 with warnings/empty hits; malformed 400 default. Evidence: `QueryController.java:23-37`; `QueryRequest.java:3-13`; `QueryResponse.java:8-18`; `QueryService.java:61-131`; `PlanExecutor.java:95-127`.

## Synapt `POST /search`

Request `{query @QueryText max 10000, top_k 1..100 default 10, top_results deprecated, hints}`; tenant from verified envelope. Response is gateway `QueryResponse` passthrough. Errors: 401/403/503 envelope; 400 validation `{error:"validation_failed",field_errors,trace_id}`; gateway any error → 502 `{error:"upstream_failed"}`; missing tenant in controller can become 500 due broad handler. Evidence: `SearchController.java:28-42`; `SearchRequest.java:9-34`; `Synapt GlobalExceptionHandler.java:23-54`.

## MCP search

`POST /mcp` `tools/call name=search` params `{query,top_k=10}`; scope `search|*`; tenant from MCP identity; response JSON-RPC `result.content[{type:"text",text}]`, `isError`. Handler formats `hits[].contentRef/excerpt` but backend uses `content_ref_id/snippet` → likely `[unknown]`; no Authorization header while synapt requires it. Evidence: `SearchToolHandler.java:32-95`; `McpEndpoint.java:37-143`.

## Relix `/graph/query`

Request `{tenant,shape,params}`; shapes and params; response `{entities[],edges[],paths[],trace}`. Errors 401/403, unknown shape 500, invalid UUID 500. Consumers gateway/MCP currently send incompatible shapes and no auth. Evidence: `GraphController.java:20-41`; `GraphQueryRequest.java:9-38`; `GraphQueryService.java:49-89`.

## Cross-cutting gaps

- Error envelope nonconformance: empty 404/500/503 for `/v1/search`; empty provenance/projection errors; bespoke synapt envelope; missing request_id/retryable.
- Consumer mismatches: gateway→synquest no auth; gateway/MCP→Relix wrong shape; MCP field names; gateway graph DTO names.
- Tenant binding asymmetry: legacy search/ops and gateway trust body tenant; `/v1/search` verifies.
- Gen-B fields ignored silently; fixed page 20.
- Docs/code drift: reindex 409 vs serialise; health starting vs READY; body tenant ignored vs rejected; demo top_k ignored; synapt warning not emitted.
- Missing tests: legacy ops, projection entities, Relix controller E2E, gateway orchestration, MCP invoke, synapt forwarding/errors.
- No document-scope ranking/public `document_id`/`promotion_state`; ACL/classification filter unwired; graph search only via gateway.
