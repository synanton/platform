# Phase 4 — Document graph endpoint evidence

Status: draft for #880. No storage-engine replacement and no UI/search change.

## Contract

`GET /v1/documents/{documentId}/graph` and alias `GET /documents/{documentId}/graph`.

- `documentId` is the persisted immutable source-version id (`source_version_id`/`version_id`). The response repeats it as `document_id` and `source_version_id`.
- Tenant comes only from verified JWT/filter context.
- Nodes: `mention_id`, `surface_form`, `proposed_type`, `chunk_id`, chunk/document offsets, `resolution_state`, `resolved_entity_id`, `extraction_generation`.
- Edges: `relation_id`, `subject_mention_id`, `object_mention_id`, `predicate`, evidence chunk/offsets, `promotion_state`, `canonical_relation_id`, `source_generation`, `extraction_generation`.
- Bounded at 5,000 nodes/edges with `truncated`.

## Evidence

| Claim | Test |
|---|---|
| Document/tenant-scoped nodes and edges | `InMemoryGraphWriteTest#documentGraphIsScopedAndCarriesGeneration`; `Neo4jGraphWriteIntegrationTest#documentGraphReturnsScopedMentionsRelationsAndExtractionGeneration` |
| Mention IDs preserved | Same tests assert stable `mentionId`/`subject_mention_id`/`object_mention_id`. |
| Promotion state represented | Edges return `UNPROMOTED` when no `RelationEvidence`; Neo4j query returns `ev.status` and `canonical_relation_id` when evidence exists. |
| Extraction generation traceable | Graph writes carry `extraction_generation` from the extraction result generation; Neo4j/in-memory reads return it. |
| Endpoint/auth/JSON contract | `DocumentGraphControllerTest#returnsDocumentScopedGraph`, `#unversionedAliasIsAlsoProtected`. |
| Existing graph behavior preserved | Neo4j write/promotion/resolution suites; `GraphWriterTest`; `GraphCommitConsumerTest`; `ExtractionConsumerTest`. |

## Gate A

The endpoint returns extraction-backed document graph data with mention identity, promotion state, and generation identity, scoped by tenant and source version.

## Gate B

Graph writes add only `extraction_generation`; existing mention/relation fields and endpoints remain compatible. GraphCommitConsumer retries load the generation from persisted extraction results. Full Build All is the phase gate.

## Boundaries

No F1/gold/gate/profile/ADR-044 change. Graph UI/search remain Phases 5/6.
