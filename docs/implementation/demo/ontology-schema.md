# Demo ontology schema v2

Status: Phase 1 schema, definitions only. This document does not define runtime code.
Corpus: `demo-v2`, 634 chunks, digest `5cf5b9553610482b2b5a1db2075def77b62c8cad6c8ddebcf080e6e26fd21e37`.

## 1. Scope

The ontology is a shallow, deterministic entity surface over the demo corpus. It feeds the ontology panel and the visualization-only graph fixture. It is **not** a retrieval leg and does not alter BM25/dense/RRF scoring.

The classified artifact is `ontology-v2.jsonl`:

- 273 rows total
- 198 hard
- 72 soft_glossary
- 3 soft_doctrinal
- 31 graph-useful (hard, df 3–15)
- 0 soft graph-useful

Raw extraction is not re-run by the mapper. `ontology-v2.jsonl` is the committed input; its SHA-256 is `00c7e8fb6e1385ffe97710c26523a720dbe57cd922653bf1d099e7dec051a389`.

## 2. Identity

| Field | Rule |
|---|---|
| `entity_id` | `ent:<type>:<sha1(canonical_surface.casefold())[:16]>` |
| `canonical_form` | Human-readable canonical surface; preserve display case where the type requires it. |
| `type` | One of the entity types in section 3. |
| `entity_class` | `hard` or `soft`. Exactly one. |
| `entity_kind` | Visual/API kind: `year`, `date`, `identifier`, `version`, `section_ref`, `case_name`, `glossary`, `filenet`, `legal_doctrine`. |
| `df` | Number of distinct chunks containing the entity. |
| `chunk_ids` | Sorted distinct canonical chunk IDs. |
| `soft_subtype` | Required only for `entity_class=soft`: `soft_glossary` or `soft_doctrinal`. |

Chunk identity is `<content_ref_id>#<chunk_ordinal>`; it is never a bare ordinal. Entity identity never merges two types.

## 3. Entity types

### Hard entities

Hard entities are structurally recognizable and are the only entities eligible for graph edges.

| `type` | `entity_kind` | Definition | Examples |
|---|---|---|---|
| `identifier` | `identifier` | Exact identifiers, error codes, standards, VLAN/IP/port tokens. | `ERR_…`, `SEC-…`, `IATF 16949`, `VLAN001`, `192.168.10.12` |
| `version` | `version` | Explicit versions, build/slice/API version tokens. | `v2.1`, `slice-2024`, `TEI 1.2.3` |
| `date` | `year` or `date` | Years and full calendar dates. A bare four-digit year uses `entity_kind=year`. | `2024`, `July 15, 2004` |
| `section_ref` | `section_ref` | Legal/technical section, article, or restatement references. | `§ 74`, `Section 6.2`, `Article IV` |
| `case_name` | `case_name` | Legal case citations. | `Lucy v. Zehmer` |

Hard entities may be graph nodes only when `3 <= df <= 15`.

### Soft entities

Soft entities are glossary or doctrinal terms. They render in the collapsed ontology panel, never as graph nodes or graph edges.

| `type` | `soft_subtype` | Definition | Panel treatment |
|---|---|---|---|
| `glossary` | `soft_glossary` | Glossary/product/architecture terms. | Collapsed Glossary section. |
| `filenet` | `soft_glossary` | FileNet product and class terms. | Collapsed Glossary section. |
| `legal_doctrine` | `soft_doctrinal` | Doctrinal phrases from the legal casebook. | Italic in the Glossary section. |

`quoted_phrase` is extraction output, not an ontology type. It is excluded from `ontology-v2.jsonl`.

## 4. Relations

| Relation | Direction | Weight | Meaning |
|---|---|---|---|
| `mention` | chunk → entity | 1 | The entity surface occurs in the chunk. |
| `co_occurs` | entity ↔ entity | `1 / log(1 + df)` | Both entities occur in the same chunk; stored for graph construction/analysis. |
| `alias_of` | surface → canonical | 1 | Surface form resolves to a canonical entity. Aliases never cross entity types. |

Graph edges are derived from shared hard entities, not from soft co-occurrence. The graph endpoint is visualization-only.

## 5. DF bands

`df` is the number of distinct chunks containing the entity.

| `df_band` | Range | Use |
|---|---|---|
| `1` | `df = 1` | Singleton; retained in ontology, not graph-useful. |
| `2` | `df = 2` | Pair; retained in ontology, not graph-useful. |
| `3-15` | `3 <= df <= 15` | **graph_useful** for hard entities. |
| `16-50` | `16 <= df <= 50` | Frequent; retained, not graph-useful. |
| `>50` | `df > 50` | High-frequency; retained, not graph-useful. |

`graph_useful = entity_class == hard && 3 <= df <= 15`.

Soft entities are never graph-useful, regardless of DF. The `df_band=graph_useful&entity_class=soft_glossary` result must be 0.

## 6. Graph projection

Graph fixture: `graph-v2.json`.

- Center chunk: the requested `chunk_id`.
- Entity nodes: hard entities attached to the center with `3 <= df <= 15`.
- Neighbor chunks: distinct chunks sharing at least one such entity.
- Edges: one per neighboring chunk, carrying the connecting entities in `via`.
- `neighbor_count`: number of neighboring chunks; equals `neighbors.length` and `neighbor_chunks.length`.
- `by_kind`: number of distinct neighboring chunks per `entity_kind`.
- Known chunk with no graph-eligible entities: HTTP 200 with empty arrays.
- Unknown chunk: HTTP 404.
- Coverage: 69/634 chunks (11%). This is expected sparse coverage, not an error.

## 7. API filters

Ontology endpoints require the same demo tenant token pattern as `/v1/demo/search` (`POST /v1/auth/tenant`, `Authorization: Bearer …`, silent 401 retry in the panel).

Supported filters:

| Filter | Values | Meaning |
|---|---|---|
| `df_band` | `graph_useful`, `all` | `graph_useful` = hard + df 3–15; `all` = all 273. |
| `entity_class` | `hard`, `soft`, `soft_glossary`, `soft_doctrinal` | Class/subtype filter. |

Pinned results:

| Query | Expected |
|---|---|
| `df_band=all` | 273 |
| `df_band=graph_useful` | 31 |
| `df_band=graph_useful&entity_class=hard` | 31 |
| `df_band=graph_useful&entity_class=soft_glossary` | 0 |

## 8. Invariants

1. Every row has exactly one `entity_class`; every soft row has a `soft_subtype`.
2. No soft entity becomes a graph node or graph edge.
3. `graph_useful` is exactly `hard && 3 <= df <= 15`.
4. `neighbor_count == neighbors.length == neighbor_chunks.length`.
5. A known chunk without graph entities returns empty arrays, not 404.
6. The mapper consumes `ontology-v2.jsonl`; it does not re-run extraction.
7. Future ontology amendments must change the SHA and counts deliberately; reproduce 378 raw / 273 classified or document the new count and reason.

## 9. Versioning

- Schema version: `v2`.
- Corpus: `demo-v2`.
- Ontology artifact: `ontology-v2.jsonl`.
- Reconciliation: `ontology-v2-classification-report.json`.
- This schema is the Phase 1 input contract; runtime mapping and endpoints implement it.
