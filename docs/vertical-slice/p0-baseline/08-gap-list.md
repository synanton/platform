# P0-4 — Gap list for the six-layer vertical slice

Status: draft for #859. Only gaps required for document-in → candidates → graph query → search → UI are listed. Lifecycle compliance work (#830/#845/#842/#839) remains a separate track. Evidence is implementation/test-source inspection; tests were executed only for P0-3.

## Status vocabulary

- **present** — capability exists and is wired; no vertical-slice work required.
- **partial** — some implementation exists, but the required end-to-end contract/wiring is incomplete.
- **missing** — no implementation found.
- **verified absent** — checked and confirmed not present, not a guess.

## Ingestion

| Requirement | Status | Evidence | Required work |
|---|---|---|---|
| Document upload/submit | present | `IngestionRunController.java:39-101`; `FixtureSourceWorkflow.java:209-285`; upload E2E and scripts | Reuse, do not replace |
| Filesystem discovery | present | `FixtureSourceWorkflow.java:497-523`; `FilesystemAdapter.java:22-25` | Reuse |
| Dedup/versioning/idempotency | present | `FixtureIngestionService.java:161-216`; idempotency tests | Reuse; preserve |
| Extraction trigger | present | `FixtureIngestionService.java:213-216,694-710`; `SearchProjectionConsumer.java:72-137`; `ExtractionConsumer.java:158-188` | Reuse |
| Batch/folder upload | missing | Only single-file multipart; legacy `/ingest` processes directories, not upload (`docs/implementation/folder-upload-328.md` is “Plan (not started)”) | Not required for the slice unless “batch upload” is in scope |
| Unified document response with `document_id` + `source_version_id` | partial | `SourceVersionResource` exposes `version_id` (`SourceController.java:359-374`); no document-resource endpoint/DTO found | Decide document identity; add additive response field if required |
| Stable `document_id` across re-uploads | missing/undefined | Content refs/version IDs are stable per revision; no document entity; `SemanticChunk.documentId` exists but no API document identity | Define and wire additive document identity |
| UI document upload | missing | No document upload view under `ui/`; only ontology Turtle upload | Phase 5 UI work |
| Ingestion→extraction with ontology/schema context | partial | Extraction trigger exists; ontology release/schema is not passed | Phase 1/3 |

## Ontology

| Requirement | Status | Evidence | Required work |
|---|---|---|---|
| Existing endpoints/versioning/evolution/multi-tenant namespace | present | `OntologyController.java`; `OntologyService.java:91-107`; tests | Preserve |
| Classes/properties exposure | present | `/graph`, schema IR, `/entities`, `/relations`; classes/properties available | Reuse additively |
| `release_id`/content digest | missing | `OntologyVersion.java:6-15` has UUID/version/graphUri only; no digest on upload; `versionRef=uuid:<id>` exists but unused downstream | Add release identity additively; no breaking change |
| Multi-ontology named releases | verified absent | No `ontology_id` in model/table; HCL parser keeps first `ontology` block (`HclSchemaParser.java:68`) | Deferred unless P0 proves required |
| Subset selection | verified absent | Only proposal text; no selector code | Deferred |
| Session-pin-aware resolution | missing wiring | Pin CRUD/TTL exists; `OntologyService.resolveVersion` does not call `SessionPinService` | Not required for slice; do not break pins |
| Hand-written schema fallback | partial | Existing HCL schema compile/load exists (`SchemaLoadService.java`); #832 compiler not implemented | Use existing loaded/hand-written schema for Phase 1 if #832 absent |

## Extraction

| Requirement | Status | Evidence | Required work |
|---|---|---|---|
| Parse/repair/quarantine/generation/cache/retention | present | P0-1 extraction inventory; tests | Preserve |
| Consumer → accepted candidates | present | `ExtractionConsumer.java`; `extraction_results` | Preserve |
| Source version identity in generation/result | present | `ExtractionConsumer.java:223,279-289`; `extraction_results.source_version_id` | Preserve |
| Stable document identity in generation/result | partial | `QuarantineGeneration.sourceDocumentId` uses `sourceId:resourceId`; no document_id API contract | Add additive document identity |
| Ontology `release_id`/digest in generation | missing | `QuarantineGeneration` has prompt/schema digest but no ontology release/digest; no release_id producer | Add fields/provenance additively |
| Schema version/digest production | missing | `LlmExtractionAdapter.java:218-219` hardcodes null; tests assert null | Phase 1/3 additive schema provenance |
| Cache-hit path | present | `ExtractionConsumer.java:228-247,306-318`; tests | Preserve; cache omission/ontology identity must not silently change |
| Generation promotion wiring | missing | `QuarantineStore.promote` only tests; no production caller | Phase 5 promote action needs explicit promotion wiring; not a cache path |
| `ExtractionResult` compatibility | present | Existing shape/consumers; tests | Keep unchanged except additive |

## Graph

| Requirement | Status | Evidence | Required work |
|---|---|---|---|
| Graph storage | present | Neo4j canonical, in-memory/Nebula adapters | Reuse; do not replace |
| Mention/relation writes | present | `/graph/mentions`, `/graph/relations`; `GraphWriter` | Reuse |
| Entity/relation resolution and promotion | present | `/graph/resolutions`, `/graph/promotions`; tests | Reuse |
| Entity neighborhood/query endpoints | present | `/v1/entities/{id}/neighbors`, `/graph/query`, canonical type | Reuse |
| `GET /documents/{id}/graph` with document-scoped nodes/edges | missing | No document-graph controller/route/DTO found; graph reads are entity/query-centric | Implement document-scoped query over existing graph |
| Nodes carry `mention_id`; edges carry endpoint mention IDs/predicate | partial | Mention/relation writes persist IDs; entity/n-hop responses do not expose mention IDs/predicate per node/edge in a document graph contract | Add response shape over existing data |
| Promotion state + generation identity visible | partial | Promotion state exists in graph model; graph read DTOs expose graph generation, not extraction generation/promotion state per document edge | Add additive fields |
| Cross-document linking/entity resolution | present | Canonicalizer, evidence fusion, resolution writer | Preserve; same-as linking absent but deferred |
| Generation-aware graph reads | missing | `GraphReadPort` has no generation parameter; read-path inventory marks unenforced | Not required unless slice requires current-generation-only graph |

## UI

| Requirement | Status | Evidence | Required work |
|---|---|---|---|
| Existing views preserved | present | Dashboard, Ontology Viewer, Entity Graph, Admin, Settings, Chat; tests/E2E | Preserve |
| Submit view using existing upload flow | missing | No document upload UI; `Chat.tsx` is search; `Admin.tsx` is ontology Turtle | Add view over `/v1/ingestion-runs` multipart |
| Processing view (chunks/segments/stage) | missing | No processing/status view; API status exists (`SourceController.java:99`) | Add polling view; no new backend contract if status suffices |
| Results view (accepted/quarantine/coverage) | missing | No results view; extraction results API exists; quarantine store is not exposed via API | Add view + read path for quarantine/candidates (contract additive) |
| Provenance view (generation, ontology release/digest, prompt) | partial | Entity Graph evidence panel only; no generation/ontology view | Add additive view; depends on release/digest |
| Promote action (`STAGED→ACTIVE`) | missing | No UI action; `QuarantineStore.promote` has no production caller | Wire promotion API + UI; explicit action only |
| Search UI | partial | Chat exists but `/search` route/auth/tenant/response are broken | Fix or replace with working search view |
| Preserve existing UI consumers | present | Existing UI tests/E2E; several production ingress mismatches (P0-2 UI contract) | Fix ingress/contracts without replacing views |

## Search

| Requirement | Status | Evidence | Required work |
|---|---|---|---|
| Existing search/ranking/filters | present | `/v1/search`, Lucene/PG/YDB/Cassandra backends; tests | Preserve |
| Document identity in results | missing in public contract | Public hit has `object_id` (chunk) and no `document_id`; lower-level `SearchHit` has `documentId` | Add additive public `document_id` |
| `generation_id` in results | missing | Public hit has `configuration_generation`, not extraction generation | Add additive extraction generation field |
| `promotion_state` in results | missing | Public hit has no promotion state; gateway has `graphPromoted` | Add additive promotion state |
| Graph-backed search endpoint | partial | Gateway fuses Relix graph hits (`FusionEngine`); synquest engine `graph=false`; no simple graph-backed search endpoint | Reuse gateway or add minimal endpoint; preserve existing search |
| Document-scope ranking | verified absent | Public search is chunk-level; no document-ranked unit | Deferred unless slice requires |
| Existing UI search consumer | broken | `Chat.tsx` no auth/tenant/proxy; response shape mismatch | Fix as Phase 5/6 |

## Cross-layer gaps

| Gap | Evidence | Impact |
|---|---|---|
| Gateway → Relix `/graph/query` wire mismatch (`{tenant,query,slots}` vs `{tenant,shape,params}`) | `RelixClient.java:27-36`; `GraphQueryRequest.java:9-13` | Graph-backed search can fail/return empty |
| MCP → Relix `/graph/query` mismatch (`query_shape`) | `GraphQueryToolHandler.java:39-58` | MCP graph query fails |
| Gateway/MCP → synquest/synapt auth missing | `SynquestClient.java:25-34`; `SearchToolHandler.java:61-67` | Runtime 401 risk |
| UI login/topology/search contract breaks | P0-2 UI contract | UI flows cannot work in packaged deployment |
| `/graph/query` identity gap | `GraphController.java:22-41`; `RelixSecurityConfig.java:28-29` | Caller-selected tenant unless envelope enforced |
| Legacy search/ops trust body/query tenant | `SearchController.java:24-63` | Cross-tenant access risk |

## Deferred / not required for this slice

- #830 capture governance completion, #845 full cascade, #842 integration verification, #839 Docker timeout enforcement.
- Multi-ontology, subset selection, same-as cross-document resolution, generation-aware graph reads, document-scope ranking, Gen-B search filters, ACL/classification filtering.
- F1/gold/gate/profile/ADR-044 status changes.

## Conclusion

The six-layer pipeline has a working ingestion/extraction/graph/search foundation, but the vertical-slice-specific gaps are concentrated in:

1. ontology release identity and schema provenance;
2. extraction generation/document provenance;
3. document-scoped graph endpoint and generation/promotion response fields;
4. UI submit/processing/results/provenance/promote views;
5. public search document/generation/promotion fields;
6. consumer/auth contract fixes across gateway, MCP, and UI.

These are wiring/additive gaps, not replacements of existing capability.
