# P0-1 — Ontology do-not-remove inventory

Status: draft for #859. Source/test-source inspection; no live Syntology run. Paths are repo-relative.

## Must be preserved

| Capability | Code path | Test / operational evidence | Status |
|---|---|---|---|
| List ontology versions — `GET /api/v1/ontology/versions` | `java/syntology/src/main/java/org/synanton/syntology/api/rest/OntologyController.java:35`; `domain/service/OntologyService.java:73`; `infra/jdbc/H2MetadataRepository.java:35` | `SyntologyIntegrationTest#shouldSeedOntologyAndReturnGraph` | verified-in-code |
| Create version from Turtle — `POST /api/v1/ontology/versions` multipart | `OntologyController.java:40`; `OntologyService.java:82`; `infra/jena/JenaTdb2Adapter.java:65` | `test/e2e/tests/permission.spec.ts` create-version flow; no unit/integration test found | verified-in-code |
| Entity/relation resolution — `GET /api/v1/ontology/entities`, `GET /api/v1/ontology/relations` | `OntologyController.java:50,58`; `OntologyService.java:110,121`; `JenaTdb2Adapter.java:199,207` | `SyntologyIntegrationTest#shouldResolveEntityByLabel`; relation test not found | verified-in-code |
| Graph read — `GET /api/v1/ontology/graph` | `OntologyController.java:66`; `OntologyService.java:127`; `JenaTdb2Adapter.java:50` | `SyntologyIntegrationTest#shouldSeedOntologyAndReturnGraph`; `GraphTransformerTest#shouldProduceNodesFromTurtleModel` | verified-in-code |
| Ontology lint — `GET /api/v1/ontology/lint` | `OntologyController.java:71`; `domain/service/OntologyLintService.java:16` | No test found | verified-in-code |
| Concept/relation validation — `POST /api/v1/ontology/validate`, `validate-relation`; SHACL | `OntologyController.java:76,81`; `OntologyService.java:133,170,223-249` | `SyntologyIntegrationTest#shouldValidateConceptStub`, `#shouldValidateRelationDomainAndRange`, `#shouldRejectRelationDomainMismatchWithStructuredViolation`, `#shouldValidateTransitiveTypeInheritance`, `#shouldRejectUnknownRelationPredicate` | verified-in-code |
| Capability matrix — `GET /api/v1/ontology/capabilities` | `api/rest/CapabilitiesController.java:20`; `OntologyService.java:301`; `JenaTdb2Adapter.java:125` | `SyntologyIntegrationTest#shouldExposeCapabilities` | verified-in-code |
| HCL schema compile/load — `POST /api/v1/admin/ontology/schemas`, `/preview`, `/from-path` | `api/rest/OntologyAdminController.java:34,46,55`; `domain/service/SchemaLoadService.java:63,88,97`; `infra/schema/SchemaBundleUnpacker.java:15`; `IncludeResolver.java:19` | `SyntologyIntegrationTest#shouldLoadHclSchemaBundleAndExposeIr`, `#shouldValidateTransitiveTypeInheritance`; preview/from-path untested | verified-in-code |
| Stored compiled schema IR — `GET /api/v1/admin/ontology/schemas/{version}` | `OntologyAdminController.java:69`; `SchemaLoadService.java:124`; `JenaTdb2Adapter.java:95` | `SyntologyIntegrationTest#shouldLoadHclSchemaBundleAndExposeIr` | verified-in-code |
| Active-version bootstrap + immutable `versionRef` — `GET /v1/tenants/{tenantId}/active-ontology-version` | `api/rest/ActiveOntologyVersionController.java:31,56-62`; `OntologyService.java:78` | `ActiveOntologyVersionControllerTest` (success, tenant mismatch, 404, 401, 503) | verified-in-code |
| Session version pins — `POST/GET/DELETE /api/v1/ontology/session-pin`; 24h cleanup | `api/rest/SessionPinController.java:34,45,57`; `domain/service/SessionPinService.java:39,59,77,85`; `infra/jdbc/SessionPinRepository.java:40,55`; `app/SessionPinCleanupWorker.java:20`; `resources/db/migration/V3__session_pins.sql` | No test found; documented `SessionPinIT` does not exist | verified-in-code |
| Multi-tenancy and per-tenant version namespaces | `resources/db/migration/V1__init.sql`; `V2__multi_tenant.sql:1-19`; `H2MetadataRepository.java:35-64,87`; `JenaTdb2Adapter.java:275-296`; `ActiveOntologyVersionController.java:33-41` | `SyntologyIntegrationTest#shouldSeedOntologyAndReturnGraph`; `ActiveOntologyVersionControllerTest#rejectsTenantPathMismatch` | verified-in-code |
| Evolution/versioning: deprecate previous ACTIVE, insert new ACTIVE, invalidate cache, publish `VersionBumped` | `OntologyService.java:91-106`; `SchemaLoadService.java:101-120`; `H2MetadataRepository.java:87-92`; `infra/events/SyntologyEventPublisher.java:28-30,43-45` | `SyntologyEventPublisherTest#publishVersionBumped_emitsTheConformantEnvelope`; old-version `DEPRECATED` assertion not found | verified-in-code |
| Write authorization on ontology mutation | `OntologyService.java:340-346`; `FsPermissionGuard` injection `:55,63` | `test/e2e/tests/permission.spec.ts` “bob is denied write access” | verified-in-code |
| HCL → JSON IR → OWL TBox + SHACL pipeline | `SchemaLoadService.java:63`; `infra/schema/HclSchemaParser.java:31,44`; `IncludeResolver.java:19`; `infra/jena/TboxRuntimeMapper.java:22`; `ShaclRuntimeMapper.java:45` | `HclSchemaParserTest`; `IncludeResolverTest`; `ShaclRuntimeMapperTest`; `SyntologyIntegrationTest` | verified-in-code |
| Loaded artifacts: seed TTL, HCL, per-version `ontology.ttl`/`shapes.ttl`/`schema.json` | `app/DemoDataLoader.java:54-70`; `JenaTdb2Adapter.java:65-97`; `java/syntology/src/main/resources/sample-ontology.ttl`; `schemas/ontology/*.hcl`; deployment volumes | `SyntologyIntegrationTest`; `GraphTransformerTest`; no deployment test | verified-in-code |
| MCP ontology surface — `GET/POST /mcp` (`tools/list`, `tools/call`) | `api/mcp/McpController.java:32,41,52-53,69-71,82` | `SyntologyIntegrationTest#shouldListMcpTools` covers GET only | verified-in-code |
| Consumers: Relix active-version bootstrap | `java/relix/src/main/java/org/synanton/relix/syntology/HttpSyntologyActiveVersionClient.java:35-56`; `identity/CanonicalGraphMetadataBootstrap.java:51`; `identity/ProjectionProvisioner.java:44` | `HttpSyntologyActiveVersionClientTest` | verified-in-code |
| Consumers: Relix tenant provisioning creates an ontology version | `relix/provisioning/HttpOntologyProvisioner.java:31-90`; `ProvisionTenantCli.java` | No direct test found; `scripts/provision-tenant.sh:55` | verified-in-code |
| Consumers: Synflux relation validation | `java/synflux/src/main/java/org/synanton/synflux/extraction/graph/HttpSyntologyRelationValidator.java:36-75`; wiring `config/SynfluxConfig.java:126` | No direct adapter test found | verified-in-code |
| Consumers: MCP bridge + Syntology Admin UI | `java/synanton-mcp/.../OntologyResolveToolHandler.java:48-71`; `ui/syntology-admin/src/services/ontologyApi.ts:4-38`; `pages/Dashboard.tsx:14-16` | MCP schema test only; UI API calls untested | verified-in-code |
| Separate Synquest demo ontology surface (`/v1/demo/ontology/*`, SHA-pinned JSONL) | `java/synquest/src/main/java/org/synanton/synquest/ontology/OntologyController.java:36,59`; `OntologyMapper.java:40-50`; `demo-data/eval/retrieval-benchmark/ontology-v2.jsonl` | `OntologyControllerTest`; `OntologyMapperTest#load_expectedRowCount_273`, `#load_abbreviatedSha_rejected`, `#load_wrongSha_rejected` | verified-in-code |

## Not present — do not assume

| Capability | Evidence | Status |
|---|---|---|
| Multi-ontology named releases per tenant | No `ontology_id` in `OntologyVersion`/metadata queries; HCL parser keeps only first `ontology` block (`HclSchemaParser.java:68`). Registry docs are proposals. | unknown / not implemented |
| Subset selection for extraction/prompting | Only proposal text; no selector/compiler in Java code. | documented-only |
| Ontology `release_id` / content digest exposure | `OntologyVersion` exposes UUID version/graph URI; no digest on upload; only `versionRef = uuid:<versionId>`. Governance proposal defines it, not implemented. | unknown / not implemented |
| Pin-aware entity resolution | `OntologyService.resolveVersion` reads metadata only; no `SessionPinService` dependency. | documented-only |
| Endpoint tests for relations, lint, session pins, schema preview/from-path, MCP `tools/call`, HTTP adapters | No tests found. | unknown |
| Old-version deprecation assertion | No test asserts previous ACTIVE becomes DEPRECATED. | unknown |
| Documented `POST /api/v1/ontology/entities` / `entities/resolve` | Not found in controllers; only MCP create-entity path exists. | documented-only |

Tests were not executed for this inventory; P0-3 owns execution status.
