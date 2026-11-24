# P0-2 — Graph contract baseline (Relix)

Status: draft for #859. Verified against controllers/DTOs; tests not executed. Paths are repo-relative.

## Cross-cutting facts

- JSON is global SNAKE_CASE; nulls omitted (`java/relix/src/main/resources/application.yml:11-13`).
- JWT filter covers writes, `/v1/entities/*`, `/v1/resolutions/*`, `/v1/extracted-relations/*`, `/v1/admin/*`; **not** `/graph/query`, `/graph/stats`, `/graph/rebuild`, `/health` (`RelixSecurityConfig.java:19-31`).
- `/graph/query` tenant comes from body first; without envelope/opt-in mock filter, caller can choose tenant.
- JWT reject body: 401 `{"status":401,"code":"AUTHENTICATION_REQUIRED"|"TENANT_CONTEXT_MISSING","detail":...}`.
- Envelope filter, if enabled: 401/403/503 `application/problem+json`; `/health` exempt.
- No Relix-wide `@ControllerAdvice`; unknown shape/bad UUID often become 500.
- No OpenAPI/stability annotation for graph endpoints. Only extracted-mention write seam is marked pinned 2026-11-08 in docs.

## Read/query endpoints

| Method / path | Request | Response | Errors / retry | Consumers | Evidence |
|---|---|---|---|---|---|
| `POST /graph/query` | `{tenant,shape,params}`; shape exactly `entity_lookup`, `one_hop`, `k_hop_path`; params typed | `{entities[],edges[],paths[],trace{shape,graph_generation,candidate_count,traversal_ms,total_ms}}`; entities have `entity_id,label,type,confidence,source_refs`; edges `edge_id,from_entity_id,to_entity_id,verb,confidence,source_refs` | 401 missing tenant; 403 mismatch; 400 malformed JSON; unknown shape/null params/bad UUID → 500 | Gateway, MCP (both wire-incompatible) | `GraphController.java:20-25`; `GraphQueryRequest.java:9-38`; `GraphQueryResponse.java:5-9`; `GraphQueryService.java:49-89`; executor tests only |
| `GET /graph/stats?tenant=` | query tenant | `{tenant,entity_count,edge_count,load_time_ms,graph_generation}`; cached per tenant; `-1` until loaded; excludes extracted nodes | 401/500 | none found; docs claim planner but code mismatch | `GraphController.java:58-62`; `GraphStats.java:5-11`; `Neo4jGraphConnector.java:1029-1031` |
| `POST /graph/rebuild?tenant=` | query tenant | `{status:"done",tenant}`; serializes per tenant; Neo4j additive, in-memory replace | 401/500; no 409 | none found | `GraphController.java:44-51`; `GraphQueryService.java:91-99` |
| `GET /health` | none | `{status:"ready"}` | anonymous; no test | probes | `GraphController.java:53-56` |
| `GET /v1/entities/{id}/neighbors` | path entityId; no honored query params | `EntityNeighborhoodResponse{entity,neighbors[],evidence[],truncated}`; 1 hop, cap 50, evidence cap 200 | 401; 404 unknown; 500 adapter | UI Entity Graph | `EntityNeighborhoodController.java:35-64`; `EntityNeighborhoodResponse.java:7-31`; tests strong |
| `GET /v1/entities/{id}/type` | path entityId | `{entity_id,type}` | 401; 404; 500 | Synquest lookup | `CanonicalEntityController.java:22-30`; tests |

`/graph/query` response `trace.graph_generation` is a long graph generation, not extraction generation. `k_hop_path.paths[].hops` interleaves entity and edge IDs; `score` is product of edge confidence.

## Write/promotion endpoints

| Method / path | Request | Response | Errors | Consumers | Evidence |
|---|---|---|---|---|---|
| `POST /graph/mentions` | `{mentions:[...]}`; fields `mention_id,tenant_id,source_version_id,content_ref_id,chunk_id,chunk_offset_*,document_offset_*,surface_form,proposed_type,extracted_at` | `{status:"ok",count,tenant}`; idempotent MERGE `:ExtractedMention`; never canonical | 401; body tenant mismatch 400; 500 | Synflux GraphWriter | `GraphWriteController.java:41-47,114-128`; `ExtractedMention.java:6-18`; `Neo4jGraphConnector.java:869-913` |
| `POST /graph/relations` | `{relations:[...]}`; `relation_id,tenant_id,source_version_id,predicate,subject/object_mention_id,evidence_*,validated_at,source_id,revision_id,generation,extraction_run_id` | `{status,count,tenant}`; Neo4j MATCH endpoints then MERGE; missing endpoint lowers count | 401; 400; 500 | Synflux GraphWriter | `GraphWriteController.java:49-55`; `ExtractedRelation.java:6-22`; `Neo4jGraphConnector.java:943-962` |
| `POST /graph/resolutions` | `{tenant_id,source_version_id,mention_id,outcome,resolved_entity_id,...}`; outcome RESOLVED/AMBIGUOUS/UNRESOLVED/REVIEW_REQUIRED | `{status:"ok",mention_id,active}`; only RESOLVED writes active `:RESOLVES_TO`; fencing token | 401; 400; 409 stale/missing target; 500 | Synquest resolution writer | `GraphWriteController.java:57-71`; `ResolutionDecision.java:6-21`; `Neo4jGraphConnector.java:662-732` |
| `POST /graph/promotions` | canonical relation fields + source/revision/generation | `{status,tenant,relations_created,evidence_created,evidence_matched}`; consumes matching validation row; atomic REL+evidence | 401; 400; 409 invalid/mismatch; 500 for generation contract exceptions (mapping incomplete) | internal Relix PromotionConsumer only | `GraphWriteController.java:73-87`; `CanonicalRelation.java:4-20`; `RelixCanonicalRelationPromoter.java:68-140` |
| `POST /v1/resolutions/active` | `{mention_ids:[...]}` | active resolutions `{mention_id,canonical_entity_id,canonical_entity_type}` | 401; 500 | Synflux lookup/validator | `GraphWriteController.java:89-94`; `ActiveResolution.java:6-9` |
| `GET /v1/extracted-relations?sourceVersionId=` | optional filter | list `ExtractedRelationView` | 401; 500 | Synflux promotion | `GraphWriteController.java:96-101`; no direct list test |
| `GET /v1/extracted-relations/{id}` | path | `ExtractedRelationView` or 404 | 401; 404; 500 | Synflux validator | `GraphWriteController.java:103-110`; tests |
| `POST /v1/admin/tenants/{id}/provision` | no body; role admin/provisioner | 200 `ProjectionProvisioningResult`; 503 on FAILED | 401/403/503 | Relix provisioner | `ProjectionAdminController.java:25-43`; no failure/client test |

## Synflux wire compatibility

Synflux writes `{"mentions":[...]}` / `{"relations":[...]}` with snake_case fields and checks `count == accepted.size()`; a lower count fails the commit. It reads active resolutions, list/single extracted relations; all non-2xx reads are mapped retryable, including 4xx. Evidence: `HttpRelixGraphWriteClient.java:29-75`; `GraphWriter.java:193-205`; `HttpRelixResolutionLookup.java:45-131`.

## Unknowns / gaps

- `/graph/query` identity gap: body tenant wins; no JWT filter.
- Gateway sends `{tenant,query,slots}`, MCP sends `{query_shape,params}`; Relix expects `{tenant,shape,params}` — both likely 500.
- Production JSON snake_case, but MVC auth tests use camelCase; production write shape not exercised.
- `/graph/relations` count differs by adapter (Neo4j drops missing endpoints; in-memory stores them); Synflux compensates only by count.
- `/graph/promotions` generation exceptions become 500, not 4xx.
- `/graph/rebuild` docs say 409 concurrent; code serializes.
- `/graph/stats` cached, not live.
- `/v1/extracted-relations` list untested.
- Synflux maps all read failures retryable, including 4xx.
- Planner docs `/graph/stats`; code calls `/entities/labels` absent from Relix.
- No generation-aware graph reads; cross-document same-as linking absent.
- Nebula only mocked; no real-cluster contract.
