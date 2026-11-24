# P0-2 — Ontology contract baseline

Status: draft for #859. Verified against production controllers/DTOs; tests not executed. Paths are repo-relative.

## Global auth/errors/stability

- Default auth requires HS256 JWT Bearer with `tenant_id`; missing/invalid → 401 `{"status":401,"code":"AUTHENTICATION_REQUIRED","detail":...}` (`shared/common/.../TenantContextFilter.java:76-119`).
- Filter skips `/actuator`, `/capabilities`, `/mcp` by prefix for **all methods**; `GET /mcp` and `POST /mcp` are anonymous and use the default tenant. `/api/v1/ontology/capabilities` does **not** match `/capabilities` and requires auth.
- Error handling is split: `ProblemDetail` for Jena/forbidden; ad-hoc `{"error":...}` for active-version/session-pin; filter body for 401. No OpenAPI/freeze marker; only path versions and internal Javadoc on active-version.
- Admin schema endpoints have no role/permission guard.

## `/api/v1/ontology`

| Interface | Request | Response | Errors | Consumers | Evidence |
|---|---|---|---|---|---|
| `GET /versions` | none | `OntologyVersion[]`: `versionId,tenantId,version,label,description?,graphUri?,status,createdAt`; newest first | 401; 200 `[]` | UI Dashboard/Admin/VersionSelector | `OntologyController.java:35`; `OntologyVersion.java:6-15`; `H2MetadataRepository.java:35-40`; `SyntologyIntegrationTest#shouldSeedOntologyAndReturnGraph` |
| `POST /versions` multipart | `version` required; `label` optional default version; `description` optional; `file` required | `OntologyVersion` ACTIVE; deprecates prior ACTIVE; writes Turtle; invalidates cache; publishes `VersionBumped` | 401; 403 `ERR_FS_PERMISSION`; 400 IllegalArgument/State; 500 IO; not idempotent | UI upload; Relix provisioner; E2E | `OntologyController.java:40-48`; `OntologyService.java:82-107,340-346`; `test/e2e/tests/permission.spec.ts` |
| `GET /entities?label&version` | `label` required; `version` default `active` | `EntityType{uri,label,superTypes[],properties{}}`; case-insensitive label/URI/local-name; properties always empty | 404 entity/version; 400 no active; 401 | UI unused helper; MCP bridge | `OntologyController.java:50-55`; `EntityType.java:6-11`; `JenaTdb2Adapter.java:199-204,333-335`; `IT#shouldResolveEntityByLabel` |
| `GET /relations?label&version` | `label` required; `version` default active | `RelationType{uri,label,domain,range}` | 404 unknown/version; 400 no active; 401 | none found | `OntologyController.java:58`; `RelationType.java:3-8`; no test found |
| `GET /graph?version` | `version` default active | `OntologyGraph{nodes[{id,label,type}],edges[{source,target,label}]}` | 404/400/401 | UI Viewer/Dashboard | `OntologyController.java:66`; `OntologyGraph.java:5-13`; `IT#shouldSeedOntologyAndReturnGraph`; `GraphTransformerTest` |
| `GET /lint?version` | `version` default active | `{ok,violations[{focusNode,resultPath,sourceShape,resultMessage}],orphans[],entity_count,relation_count}` | 404/400/401 | none found | `OntologyController.java:71`; `OntologyLintService.java:16-34`; no test |
| `POST /validate` | JSON `{label,uri}` non-blank; missing returns 200 valid=false | `{valid,message,conforms?,shacl?}` | 400 malformed/no active; 401 | none | `OntologyController.java:76-91`; `OntologyService.java:133-162`; `IT#shouldValidateConceptStub` |
| `POST /validate-relation` | JSON `{subjectType,predicate,objectType,ontologyVersion}` | `{conformant,valid,violations[{code,message,path?}],subject_type,predicate,object_type,version}`; semantic violations 200 | 400 no active; 404 unknown explicit version; 401 | Synflux relation validator | `OntologyController.java:81-91`; `OntologyService.java:170-259`; `IT#shouldValidateRelationDomainAndRange` etc. |

## Admin schema `/api/v1/admin/ontology`

| Interface | Request | Response | Errors | Evidence |
|---|---|---|---|---|
| `POST /schemas` multipart | `version` required; optional `label,description`; `entry` default `schema.hcl`; `file` required | `OntologyVersion` ACTIVE; HCL→IR→TBox/shapes/schema.json; deprecates prior; publishes `VersionBumped(HCL)` | 400 compile/path; 500 IO; 401; no FS/role check; not idempotent | `OntologyAdminController.java:34-40,74-82`; `SchemaLoadService.java:97-122`; `IT#shouldLoadHclSchemaBundleAndExposeIr` |
| `POST /schemas/preview` | `entry` default `schema.hcl`; `file` required | `{ir,tbox_turtle,shapes_turtle}`; `OntologySchemaIr{ontology,classes,relations}` | 400/500/401 | `OntologyAdminController.java:46`; `SchemaLoadService.java:88-95`; `OntologySchemaIr.java:8-16`; no test |
| `POST /schemas/from-path` | JSON `{relativePath,version,entry,label,description}`; only `entry` default | `OntologyVersion`; bundle under `syntology.schema.git-root` with containment | 400 escape/missing; 500 NPE if required absent; 401; no FS check | `OntologyAdminController.java:55-66,84-93`; no test |
| `GET /schemas/{version}` | path version incl. `active` | stored `OntologySchemaIr` | 400 not found/no IR (not 404); 401 | `OntologyAdminController.java:69`; `SchemaLoadService.java:124-147`; `IT:151` |

## Active version / Relix identity

`GET /v1/tenants/{tenantId}/active-ontology-version`:

- Request path `tenantId`; service JWT must match.
- `200 {tenantId, activeVersion, versionRef:"uuid:"+UUID, activatedAt}`; `activatedAt` is ontology creation time, not activation time.
- Errors: 401 `{"error":"unauthorized"}`; 403 `{"error":"tenant_scope_mismatch"}`; 404 `{"error":"no_active_ontology","tenantId":...}`; 503 `{"error":"syntology_unavailable"}`.
- Relix typed client maps 200 → record; 401/403 → auth exception; 404 → unavailable; other/connection → retryable lookup failure.
- Relix provisioning/bootstrap consumes `activeVersion` but **not** `versionRef`; de facto release identity is mutable version string. No content digest/release manifest.
- Evidence: `ActiveOntologyVersionController.java:31-70`; `HttpSyntologyActiveVersionClient.java:35-64`; `CanonicalGraphMetadataBootstrap.java:51-59`; `ActiveOntologyVersionControllerTest`; `HttpSyntologyActiveVersionClientTest`.

## Session pins `/api/v1/ontology/session-pin`

| Interface | Request | Response | Errors | Evidence |
|---|---|---|---|---|
| `POST /session-pin` | `{tenantId,version}`; tenant fallback only | 201 `{pin_id,tenant_id,version,subject_id,pinned_at,expires_at,remaining_seconds}`; 24h TTL; upsert | 401; 400 version not found; retry extends TTL | `SessionPinController.java:34-38,66-99`; `SessionPinService.java:59-70`; no test |
| `GET /session-pin` | none | same DTO or 404; **returns expired pins**; remaining clamps 0 | 401 | `SessionPinController.java:45-51`; `SessionPinRepository.java:98-107`; no test |
| `DELETE /session-pin` | none | 200 empty or 404; invalidates cache | 401 | `SessionPinController.java:57-62`; no test |

Cleanup every 300 s deletes expired rows. **Pins are not enforced on ontology reads:** `SessionPinService.resolveVersion` is unused by `OntologyService`.

## Capabilities and MCP

- `GET /api/v1/ontology/capabilities` → `{module_id:"syntology",module_version:"0.1.0",features:{BASIC_GRAPH_STORAGE,VERSIONING,OWL_REASONING,SHACL_VALIDATION,SESSION_PINNING,MULTI_TENANT_VERSIONING,CROSS_TENANT_INHERITANCE}}`; Jena reports `NATIVE` for storage/versioning/SHACL, `UNSUPPORTED` for OWL. Evidence: `CapabilitiesController.java:20`; `OntologyService.java:301-314`; `IT#shouldExposeCapabilities`.
- `GET /mcp` → `{name:"syntology",protocol:"streamable-http",tools:["syntology.list_ontology","syntology.get_entity","syntology.create_entity"]}` (strings, no schema/version).
- `POST /mcp` JSON-RPC-ish `{jsonrpc,id,method,params}`; always HTTP 200; `tools/list` → tool names/descriptions; `tools/call` → `{result:{content:[object]}}`; errors as JSON-RPC `-32000`. `create_entity` mutates active version; anonymous/default tenant. No `initialize`/protocolVersion/inputSchema; MCP compliance unknown. Evidence: `McpController.java:32-109`.

## Unknowns / gaps

- Multi-ontology named releases absent; HCL parser keeps only first ontology block.
- No ontology `release_id`/content digest; `versionRef` produced/parsed but unused downstream.
- Subset selection absent.
- Session pins not enforced; GET leaks expired pins.
- Multi-tenant version namespace conflict: V1 global `version UNIQUE`, V2 adds `(tenant_id,version)` without dropping global constraint; two tenants cannot share a version string. H2 V2 is a stub.
- Admin schema endpoints lack authorization/FS check.
- Error model non-uniform.
- POST create paths not idempotent; duplicate conflict behavior unknown.
- `/mcp` all methods anonymous; UI expects synanton-mcp shape, not Syntology `/mcp`.
- No endpoint tests for relations, lint, session pins, admin preview/from-path, MCP POST, or HTTP adapters.
