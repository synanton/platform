# P0-2 — UI contract baseline

Status: draft for #859. UI is a consumer of backend contracts. Source/test-source inspection; tests not executed. Only app: `ui/syntology-admin`.

Status: `verified` = UI/backend DTO/route agree; `broken` = cannot succeed as written; `documented-only`; `unknown`.

## Auth/session

| UI call | Backend | Request | Response used | Auth/proxy | Status |
|---|---|---|---|---|---|
| `authApi.ts:6-16` from `Login.tsx` | `POST /auth/login` | `{username,password}` | Expects `{token,expires_in}`; backend emits `{token,expiresIn}` (`AuthController.java:53-54`) | JSON; no auth; dev `/auth`→8081; prod `/auth/`→security | **broken** field mismatch; token works if JWT parses |
| `auth.ts:6-33`; `Settings.tsx` | client-only | none | `AuthSession` in `sessionStorage` | No expiry/role check; logout clears storage | verified client-only |

## Topology/admin

| UI call | Backend | Request | Response used | Auth/proxy | Status |
|---|---|---|---|---|---|
| `adminApi.ts:47-50` from Layout/Admin | `GET /topology/tenants` | none | `Tenant{tenantId,displayName,createdAt}` | Bearer attached but topology has no filter; dev `/topology`→8082; **prod nginx has no `/topology`** | verified dev; **broken prod** |
| `adminApi.ts:52-63` | `POST /topology/tenants` | `{tenantId,displayName,ownerSubjectId}` | `Tenant`; reload list | same | verified dev; broken prod |
| `adminApi.ts:65-80` | `GET/POST /auth/api-keys`, `DELETE /auth/api-keys/{id}` | POST `{label,scopes}`; response `{keyId,key,label,scopes,createdAt}` | list uses keyId/label/scopes/createdAt; create uses key | Bearer; prod `/auth` works | verified for demo tenant; backend list hardcodes `tenant_id='demo'` |

## Ontology

| UI call | Backend | Request | Response used | Status |
|---|---|---|---|---|
| `ontologyApi.ts:4-7`, `apiClient.ts:4-18` | `GET /api/v1/ontology/versions` | none | `OntologyVersion[]` fields versionId/version/label/status/createdAt | verified dev; **broken base-compose prod** (nginx→8080, compose syntology 8083) |
| `ontologyApi.ts:9-12` | `GET /api/v1/ontology/graph?version=` | query version | `{nodes[{id,label,type}],edges[{source,target,label}]}` | verified dev; broken prod |
| `ontologyApi.ts:14-17` unused | `GET /api/v1/ontology/entities?label&version` | query | `EntityType{uri,label,superTypes,properties}` | verified, unused |
| `ontologyApi.ts:19-34` | `POST /api/v1/ontology/versions` multipart | version/label/description/file | ignores body, reloads | verified; browser boundary unknown |
| `ontologyApi.ts:36-38`, Dashboard direct | `GET /api/v1/ontology/capabilities` | none | `{module_id,module_version,features{}}` | verified dev; broken prod |

## Entity graph

`entityGraphApi.ts:18-46` → `GET /api/v1/entities/{id}/neighbors`; response `{entity,neighbors[{entity,relation,direction,canonical_relation_id}],evidence[{relation,neighbor_entity_id,chunk_id,source_version,offset_start,offset_end,canonical_relation_id}],truncated}`; Bearer only; dev rewrite `/api`→Relix; prod nginx regex rewrite forwards Authorization/strips X-Tenant; UI maps 401/403 auth, 404 not-found, others server; retry button. Tests: `EntityGraphPage.test`, mock/live E2E, backend controller tests. **verified; strongest-covered flow.**

## MCP

`adminApi.ts:82-85` → `GET /mcp` (synanton-mcp); expects `{protocolVersion?,tools:[{name,description,inputSchema}]}`. Dev `/mcp`→8091; **prod nginx has no `/mcp`**. Syntology also exposes `GET /mcp` with a different shape (`{name,protocol,tools:string[]}`), but UI targets synanton-mcp. **verified dev; broken prod.**

## Chat/search

`Chat.tsx:9-27` → `POST /search` (synapt) with `{query,top_k}` and **no Authorization**; expects `body.answer` or `body.hits`. Backend requires verified Bearer and explicit tenant; response is `SearchResponse{hits,trace,query_usage}` with no `answer`. No dev/prod `/search` proxy. **broken on route, auth, tenant, response shape.**

## Explicit mismatches

1. Login `expires_in` vs `expiresIn`.
2. Grants path double-prefix: base `/topology` + `/topology/users/...` → `/topology/topology/...`; backend `/topology/users/...`. **broken**.
3. Chat/search four failures: no proxy, no Authorization, no tenant, no `answer`.
4. Prod nginx lacks `/topology`, `/mcp`, `/search`.
5. Ontology proxy port 8080 vs compose syntology 8083.
6. Tenant switcher cosmetic; requests use JWT-derived tenant; only ontology sends `X-Tenant-ID`, backend ignores it.
7. UI tenant fallback `"demo"` contradicts no-fallback invariant.
8. API-key list hardcodes demo tenant; UI assumes tenantId/subjectId in create response (absent).
9. MCP producer ambiguity (synanton-mcp vs syntology `/mcp`).
10. Only Entity Graph consumes problem+json/status; others ignore code/request_id and have no retries/global 401.
11. Unused `fetchEntity`/`fetchCapabilities`; nginx `/capabilities` dead route.
12. No UI ingestion upload, processing, results, provenance beyond Entity Graph evidence, or promote action. No UI feature flags.

## Unknowns

Runtime not executed; multipart boundary behavior; base-compose Relix envelope; other ingress; JWT base64url parsing; topology intentional auth; OAuth/PKCE future; API-key field casing.
