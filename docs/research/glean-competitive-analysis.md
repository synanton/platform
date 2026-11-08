# Glean competitive analysis: accessibility vs. computability

**Status:** Research  
**Last reviewed:** 2026-11-08  
**Scope:** Product and architectural comparison; not a claim of feature parity or production readiness.

## Executive summary

Glean is a mature enterprise AI platform that connects organizational systems and makes their information useful through permission-aware search, chat, assistants, agents, and developer APIs. Synanton is pursuing a different center of gravity: a knowledge-processing platform in which extracted knowledge is structured, validated, traceable, and recalculable as source data or processing rules change.

> **Glean makes enterprise knowledge accessible. Synanton aims to make enterprise knowledge computable and maintainable.**

This is a positioning hypothesis, not a claim that Glean lacks graph capabilities, provenance, security, or workflow automation. Glean publicly documents a Knowledge Graph, source-aware permissions, real-time indexing, citations, connectors, agents, and developer APIs. The differentiation to validate is narrower: Synanton's explicit contracts for formal ontology validation, provenance/dependency lineage, and dependency-aware recalculation are not the center of Glean's publicly documented product proposition.

Do not position Synanton as a Glean clone or compete primarily on connector count, polished chat, or agent-builder breadth.

## 1. Glean product reference

Glean's public product surface includes:

- **Enterprise Search:** unified retrieval across connected applications, with relevance, personalization, and source permissions.
- **Assistant / Chat:** grounded answers, citations, research, content creation, and work execution.
- **Agents and Skills:** configurable multi-step workflows, tool use, orchestration, and observability.
- **Enterprise Graph / Knowledge Graph:** relationships across content, people, and activity signals.
- **Connectors:** ingestion and normalization of content, metadata, activity, and source permissions.
- **Protect and governance:** controls for sensitive information, agent safety, and enterprise governance.
- **Developer platform:** Search, Chat, Agents, Indexing, Web SDK, MCP, and custom actions.

Glean's connector architecture is especially instructive. Connectors do more than copy documents: they map content, metadata, activity signals, and permissions into a shared data layer used by search, chat, and agents. Its Enterprise Graph captures relationships among content, people, and activity to support relevance and personalization.

### Primary public references

- [Glean platform](https://www.glean.com/platform)
- [Glean enterprise search](https://www.glean.com/enterprise-search)
- [Glean Assistant](https://www.glean.com/ai-assistant)
- [Glean Agents](https://www.glean.com/ai-agents)
- [Glean developer platform](https://developers.glean.com/)
- [How connectors power Glean](https://docs.glean.com/connectors/connectors-power-glean)
- [Glean Knowledge Graph](https://docs.glean.com/security/knowledge-graph)

Product capabilities and packaging change; revisit these references before making external competitive claims.

## 2. Product and architecture comparison

| Dimension | Glean's documented center of gravity | Synanton's direction | Assessment |
|---|---|---|---|
| Enterprise discovery | Search across connected enterprise applications | Synquest hybrid retrieval and Synapt APIs | Direct overlap; Glean is more mature as a product |
| Relevance | Deep learning, personalization, enterprise context | BM25, dense retrieval, RRF, reranking and benchmark evaluation | Synanton must prove relevance with reproducible metrics |
| Grounded answers | Assistant / Chat with citations and enterprise context | Evidence-backed retrieval and a developing synthesis path | Product gap for Synanton |
| Enterprise graph | Content, people, activity, and organizational context | Relix graph reasoning and graph projections | Related but different graph semantics |
| Formal ontology | Not a central capability in the public product description reviewed | Syntology, ontology contracts and SHACL validation | Potential differentiation; validate through working use cases |
| Provenance | Source citations and links | Source/chunk provenance plus explicit annotations and dependencies | Synanton aims for deeper machine-readable lineage |
| Change propagation | Connector synchronization, indexing, and live retrieval | Resolutor impact analysis and Equalix-controlled recalculation | Strong potential differentiator; implementation evidence is required |
| Connectors | Broad mature connector ecosystem with permission synchronization | Synvault/Synflux and evolving source-adapter contracts | Major Synanton product gap |
| Security | Mature source-permission-aware access and enterprise governance | Shared security contract; platform-wide enforcement and negative tests remain work | P0/P1 engineering priority |
| Agents and actions | Mature agent and tool ecosystem | Eventing/workflow and controlled execution foundations | Do not equate infrastructure primitives with a user-facing agent product |
| Analytics | Product and agent observability | Dedicated analytics plane using platform-derived events and facts | Different scope; opportunity for operational analytics |
| Temporal knowledge | Not the central public product proposition | Versioned knowledge and point-in-time semantics in the architecture | Potential differentiation; designs are ahead of implementation |

### Important comparison discipline

- Glean does have a knowledge graph. Do not describe Synanton as having a graph while Glean does not.
- Glean supports citations, permissions, real-time indexing, agents, and write-capable tools. Do not claim those capabilities are absent.
- The distinction is the *kind of graph and lifecycle contract*: Glean publicly emphasizes enterprise context and discovery; Synanton is designed to make semantic constraints, derivation lineage, invalidation, and recalculation explicit.
- "Not a central publicly documented capability" is not proof that a vendor has no internal or customer-specific implementation.

## 3. Synanton's intended differentiation

### 3.1 Knowledge engineering

Synanton should make domain meaning explicit and inspectable:

- Typed entities and relationships.
- Versioned ontology definitions.
- SHACL validation and constraint results.
- Graph reasoning and bounded graph exploration.
- Traceability from derived facts to source artifacts and processing runs.

A graph visualization alone is not differentiation. The value is being able to explain why a fact is valid, which rules were applied, and what evidence supports it.

### 3.2 Knowledge operations

Synanton's deeper thesis is that derived knowledge is managed state, not a disposable side effect of indexing.

A target lifecycle:

1. Ingest and preserve a versioned source artifact.
2. Extract structured content and semantic chunks.
3. Apply classification, ontology, and validation rules.
4. Record provenance and derivation dependencies.
5. Build search, vector, and graph projections.
6. When a source, model, rule, dictionary, or policy changes, identify affected derived state.
7. Plan and execute bounded recalculation.
8. Publish updated projections with traceable run and version metadata.

Resolutor and Equalix are intended to support the impact-analysis and controlled-execution parts of this lifecycle. This must be demonstrated by restart-safe, idempotent, reproducible tests before claiming operational reliability.

### 3.3 Search as an entry point, not the whole product

Search is the most visible initial experience and a useful way to acquire users. It should lead into richer knowledge operations:

- Search result → source evidence.
- Evidence → extracted entities and relationships.
- Entity → ontology definition and validation.
- Relationship → derivation and provenance graph.
- Source change → affected dependencies and recalculation plan.

This connects the user-facing search experience to the platform's deeper engineering proposition.

## 4. Current implementation reality

The repository's [Platform Architecture 1.0 implementation status](../architecture/synanton-platform-architecture-1.0.md#15-current-implementation-status) is authoritative. Architecture approval must not be presented as implementation completion.

The live vertical-slice milestone was recorded as **Demo Complete on 2026-11-06**: a newly uploaded document returned BM25, Dense, and RRF hits; provenance exposed source/chunk SHA-256 and page/section information; the ontology panel loaded entities. See [live dense acceptance evidence](../acceptance/live-dense-2026-11-06.md) and the [vertical-slice execution plan](../planning/vertical-slice-execution.md).

Demo Complete is not Operationally Complete. The immediate hardening work is:

| Priority | Work | Why it matters |
|---|---|---|
| P0 | [#537 automated vertical-slice regression](https://github.com/synanton/platform/issues/537) | Repeatable upload → completed ingestion → dense search → provenance in CI |
| P1 | [#536 outbox idempotency and recovery](https://github.com/synanton/platform/issues/536) | Prove restart after projection but before publish marking does not corrupt or duplicate the index |
| P1 | [#539 tenant-isolation negative tests](https://github.com/synanton/platform/issues/539) | Prove cross-tenant search, vector, manifest, and provenance access is denied |
| P1 | [#538 TEI gateway routing](https://github.com/synanton/platform/issues/538) | Remove the direct cross-namespace demo network-policy exception |
| P1 | Projection lag and retrieval-failure visibility | Make asynchronous behavior diagnosable rather than surprising |

The P0 demo milestone is complete; #537 is the remaining automated regression gate. Do not call the whole vertical slice operationally complete until the acceptance criteria and failure tests are green.

## 5. Product gaps and sequencing

### P0 — Trustworthy foundation

1. Tenant and authorization enforcement across ingestion, projections, search, vectors, and provenance.
2. Automated end-to-end browser regression in CI.
3. Outbox idempotency, recovery, and projection consistency.
4. Reproducible deployment and diagnostic evidence.

### P1 — Make the differentiator visible

1. Ontology and graph navigation integrated with search evidence.
2. Machine-inspectable provenance and derivation dependencies.
3. Retrieval evaluation with pinned datasets, qrels, revisions, and reproducible run records.
4. A small connector contract that preserves source identity/version, deletion, metadata, principal mappings, and permissions.
5. A grounded conversational retrieval path after the evidence and security contracts are reliable.

### P2 — Demonstrate knowledge operations

1. One complete source-change → impact-analysis → recalculation → projection-refresh scenario.
2. Failure injection and restart recovery for each relevant boundary.
3. Temporal/versioned retrieval where the use case requires "what was known when".
4. Analytics over platform events and derived facts.

### Explicitly defer

Do not start a broad Glean-like agent builder as the next milestone. Agent UX, general-purpose tool catalogs, and workflow authoring would expand the product surface before Synanton has proven its core knowledge lifecycle. Continue the eventing and graph-projection work needed for reliable knowledge operations, but do not market those foundations as a finished agent platform.

## 6. Recommended positioning

**Synanton is an open-source enterprise knowledge-processing platform for turning source information into validated, traceable, searchable, and incrementally maintainable knowledge.**

Three capability groups explain the product:

- **Knowledge discovery:** hybrid search, evidence, graph exploration, and grounded answers.
- **Knowledge engineering:** ontology, validation, graph reasoning, provenance, and temporal semantics.
- **Knowledge operations:** dependency analysis, recalculation planning, controlled execution, and analytics.

The concise strategic contrast remains:

> **Glean makes enterprise knowledge accessible. Synanton aims to make enterprise knowledge computable and maintainable.**

This positioning is credible only as a product direction until the operational contracts and end-to-end demonstrations are complete.

## 7. Decision rule for roadmap trade-offs

Prefer work that strengthens at least one of these properties:

1. **Correctness:** can the platform validate that derived knowledge conforms to domain constraints?
2. **Traceability:** can it explain where a fact came from and how it was produced?
3. **Changeability:** can it identify and safely recalculate what a change invalidates?
4. **Isolation:** can it guarantee that users and tenants see only authorized knowledge?
5. **Reproducibility:** can the same pinned inputs and processing versions reproduce the same outputs?

If a feature mainly adds surface area without improving these properties or completing the core discovery experience, it should not displace the current P0/P1 work.
