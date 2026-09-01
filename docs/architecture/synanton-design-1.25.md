# Synanton Design 1.24/1.25

## Annotation, Derived Knowledge, Recalculation, Analytics and Reporting Plane

> **Document type:** Architecture design document
> **Version:** 1.25 (consolidates 1.24 and 1.25)
> **Document ID:** `synanton-design-1.25`
> **Date:** 2026-09-01
> **Status:** Approved (architecture) - implementation phased per §90; not yet started
> **Consolidates:** proposal-stage work tracked as `synanton-design-1.24` (Annotation, Derived Knowledge & Recalculation) and `synanton-design-1.25` (Analytics & Reporting Plane) into this single merged design document - no separate design documents were ever published under those names, so this is their first consolidated publication rather than a supersession of prior architecture docs
> **Normative security baseline:** Design 1.23 (see §2.1)
> **Audience:** Architects, platform engineers, developers, SREs, security engineers, data engineers, technical decision makers and system integrators
> **Related docs:** [synanton-design-1.23.md](./synanton-design-1.23.md), [ADR-002](./decisions/adr-002-annotations-analytics-plane.md)

> **Implementation principle:** Design 1.24/1.25 extends the existing Synanton architecture. It does not replace the security and representation contract established by Design 1.23.

---

# 1. Executive Summary

Synanton transforms heterogeneous enterprise content into structured, annotated, connected and security-aware knowledge.

Design 1.24 establishes first-class annotations, annotation definitions and versions, dependencies, provenance, processing runs, derived knowledge and dependency-aware recalculation through **Resolutor** and **Equalix**.

Design 1.25 extends that model with an **Analytics and Reporting Plane** for analytical facts, aggregates, metrics, reports, operational measurements, knowledge-state measurements, annotation analytics, processing analytics, security analytics, cost analytics and analytical lineage.

The consolidated architectural principle is:

> **Knowledge is derived state, and analytics is derived state over knowledge and platform activity.**

The lifecycle is:

```text
  Source Content
     │
     ▼
  Extraction
     │
     ▼
  Semantic Content
     │
     ▼
  Semantic Chunks
     │
     ├────────────── Security Classification
     │
     ▼
  Annotation
     │
     ├────────────── Provenance
     ├────────────── Processing Run
     └────────────── Dependencies
     │
     ▼
  Derived Knowledge
     │
     ├────────────── Reverse Index
     ├────────────── Vector Store
     └────────────── Graph
     │
     ▼
  Search / Applications
     │
     ▼
  Protected Analytics Boundary
     │
     ▼
  Analytics Events
     │
     ▼
  Analytical Facts
     │
     ▼
  Aggregates
     │
     ▼
  Metrics
     │
     ▼
  Reports
  ```

When the interpretation of knowledge changes:

```text
  Rule / Model / Dictionary / Source / Policy Change
             │
             ▼
           Resolutor
             │
             ▼
         Dependency Analysis
             │
             ▼
         Recalculation Plan
             │
             ▼
            Equalix
             │
             ▼
        Controlled Recalculation
             │
             ▼
         Updated Knowledge
             │
             ▼
         Updated Analytics
  ```

The architecture deliberately separates:

> **What the source contains → what Synanton understands → how knowledge is projected → how knowledge is authorized → how knowledge is measured.**

---

# 2. Design Goals

## 2.1 First-Class Knowledge Interpretation

Annotations are independently addressable, versioned, explainable and provenance-aware.

## 2.2 Incremental Recalculation

Changes affect only derived state that depends on the changed inputs.

## 2.3 Strong Provenance

Derived results remain traceable to source content, definitions, producers, processing runs and dependencies.

## 2.4 Security Consistency

Annotations, projections, analytics and reports preserve the security and representation model of Design 1.23.

## 2.5 Analytical Independence

Analytics observes canonical knowledge and platform activity. It never becomes authoritative knowledge.

## 2.6 Storage Independence

Analytics storage is behind a contract. ClickHouse is the initial implementation candidate, not the architectural dependency.

## 2.7 Multi-Tenant Isolation

Tenant boundaries apply consistently to facts, aggregates, metrics, reports, storage, APIs, MCP and caches.

## 2.8 Operational Scalability

Interactive workloads, incremental processing and historical recalculation are isolated through workload controls and Equalix.

## 2.9 Rebuildability

Derived analytics must be rebuildable from a durable analytical event boundary without rewriting canonical knowledge.

## 2.10 Explicit Governance

Metrics, reports, aggregate policies and security declarations are versioned and governed through an Analytics Registry.

---

# 2.1 Relationship to Design 1.23

This design inherits the security and representation model from **Design 1.23**, which remains normative for security behavior.

The inherited model includes:

- resource ACLs combined with `class_grants`;
  - chunk-level `classification[]`;
  - deterministic classification detection before downstream publication;
  - masking as a representation transformation;
  - **Single**, **Dual**, and **Masked-only** representation outcomes;
  - `store_original: false` for classes whose original representation must never be persisted;
  - compile-time representation selection before search statistics and vector candidate generation;
  - query-side sanitization;
  - security-sensitive cache invalidation;
  - fail-closed behavior for missing or invalid security state;
  - security negative testing through `test:security`;
  - remediation and reindexing after classification policy changes.

Design 1.23 explicitly defines the representation decision and propagation model: a chunk may have one representation, masked and original representations, or masked-only content where the original is never stored.

**Design 1.23 remains normative. Design 1.24/1.25 extends it rather than replacing it.**

Full reference:

```
https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.23.md
```

---

# 2.2 Relationship to Design 1.24

This document consolidates the annotation and recalculation architecture previously described by Design 1.24.

The following remain normative concepts:

- annotation identity;
  - annotation definitions and versions;
  - producer and producer version;
  - provenance;
  - processing runs;
  - dependency DAGs;
  - derived knowledge;
  - Resolutor;
  - Equalix;
  - selective recalculation;
  - projection rebuilds;
  - historical traceability.

Design 1.25 adds analytics downstream of that model.

---

# 2.3 Document Location

This document is stored at:

```
docs/architecture/synanton-design-1.25.md
```

It consolidates the proposal-stage work tracked as `synanton-design-1.24` (Annotation, Derived Knowledge & Recalculation) and `synanton-design-1.25` (Analytics & Reporting Plane) into a single merged design document. Neither was ever published as a separate architecture file, so this document does not supersede prior published designs - it is their first publication.

Previous designs, including 1.19–1.23, remain available for historical and normative-reference purposes. Where this document discusses security, Design 1.23 is the authoritative baseline.

---

# 3. Non-Goals

This design does not mandate:

- a universal ontology;
  - one LLM provider;
  - one programming language;
  - one search engine;
  - one vector database;
  - one graph database;
  - one analytics database;
  - one BI product;
  - one dashboard framework.

These remain implementation or integration decisions subject to the contracts defined here.

---

# 4. Architectural Principles

## 4.1 Extraction and Interpretation Are Separate

Extraction determines what can be recovered from the source.

Annotation determines what Synanton understands about extracted content.

```text
  Source
   │
   ▼
  Extraction
   │
   ▼
  Semantic Content
   │
   ▼
  Annotation
  ```

Changing an annotation rule must not require re-extracting the original source.

## 4.2 Canonical Knowledge and Derived Projections Are Separate

The canonical semantic model remains authoritative. Search indexes, vectors, graph projections and analytics are derived state.

## 4.3 Security Is a Pipeline Property

Security cannot be implemented only at the final API response. Classification, representation selection, storage, indexing, query planning, caching, aggregation and reporting must all preserve the security boundary.

## 4.4 Dependencies Are Explicit

If a derived object depends on another object, that dependency must be represented explicitly enough for impact analysis.

## 4.5 Recalculation Is Controlled Execution

Resolutor determines impact. Equalix schedules and executes work under resource and priority controls.

---

# 5. Semantic Chunks Are Knowledge Units

A semantic chunk is an independently addressable knowledge unit.

A chunk may participate in:

- annotations;
  - classification;
  - search;
  - embeddings;
  - relationships;
  - graph projection;
  - provenance;
  - analytics;
  - recalculation.

Chunk boundaries therefore affect downstream computation.

```text
  Document
    │
    ├── Section
    ├── Paragraph
    ├── Table
    └── Image
      │
      ▼
  Semantic Chunks
  ```

A chunk must retain stable identity sufficient for downstream lineage and recalculation.

---

# 6. Annotation as First-Class Knowledge

An annotation represents structured interpretation of content.

Core types include:

```text
  Annotation
  ├── Tag
  ├── Classification
  ├── Entity
  ├── Attribute
  └── Signal
  ```

Annotations may be generated by:

- deterministic rules;
  - dictionaries;
  - regular expressions;
  - ML models;
  - LLMs;
  - external services;
  - custom code;
  - human operators.

The generation mechanism is separate from the annotation contract.

---

# 7. Annotation Identity

An annotation is identified by semantic identity rather than producer alone.

Conceptually:

```text
  Annotation
  ├── annotation_id
  ├── definition_id
  ├── definition_version
  ├── annotation_type
  ├── namespace
  ├── name
  ├── target_type
  ├── target_id
  ├── value
  ├── producer
  ├── producer_version
  ├── confidence
  ├── provenance
  ├── processing_run_id
  ├── created_at
  └── invalidated_at
  ```

Multiple versions of an interpretation may coexist or be compared.

Example:

```text
  payment-detection
  v3
  v4
  ```

Analytics can compare versions without destroying historical provenance.

---

# 8. Annotation Definitions

An annotation definition describes how an annotation is produced.

Example:

```yaml
  definition_id: payment-detection
  version: 4
  inputs:
   - invoice_number
   - payment_reference
   - payment_terms
  producer: payment-rule-engine
  producer_version: 4.2
  output:
   type: annotation
   name: payment
  ```

Definitions are immutable once published. A new definition version must be explicitly registered.

---

# 9. Taxonomy and Dependency Are Different

Taxonomy describes semantic organization.

```text
  support
  └── billing
    └── payment
  ```

Dependency describes computation.

```text
  payment
   +
  duplicate-charge
     │
     ▼
  billing-issue
  ```

Therefore:

> **Taxonomy describes meaning. Dependency describes derivation.**

A taxonomy hierarchy must not automatically become a processing dependency.

---

# 10. Annotation Dependencies

Annotations may depend on other annotations.

```text
  payment
    +
  duplicate-charge
      │
      ▼
  billing-issue
      │
      ▼
  escalation-required
  ```

Dependencies form a directed acyclic graph.

```text
  A → B → C
  ```

Circular dependencies are rejected:

```text
  A → B → C → A
  ```

Dependency metadata enables incremental recalculation.

---

# 11. Dependency Graph

The platform maintains dependency information between derived knowledge artifacts.

```text
  Source
   │
   ▼
  Chunk
   │
   ├── payment
   │   │
   │   ▼
   │ billing-issue
   │
   └── customer
       │
       ▼
    enterprise-customer
  ```

The graph determines which downstream artifacts may become stale after a change.

---

# 12. Processing Runs

Every substantial derived-knowledge operation should belong to a processing run.

A processing run identifies:

- execution context;
  - producer;
  - producer version;
  - configuration;
  - input scope;
  - start/end time;
  - status;
  - affected objects;
  - errors;
  - resource consumption.

Example:

```yaml
  processing_run_id: run-2026-00182
  producer: annotation-engine
  version: 4.2
  scope: tenant-17
  definition: payment-detection-v4
  ```

Processing runs are permanent provenance objects subject to retention policy.

---

# 13. Provenance

Derived knowledge must remain explainable.

A provenance chain may be:

```text
  Source
   ↓
  ECM Element
   ↓
  Chunk
   ↓
  Annotation
   ↓
  Processing Run
   ↓
  Knowledge Projection
   ↓
  Analytics Event
   ↓
  Analytical Fact
   ↓
  Aggregate
   ↓
  Metric
   ↓
  Report
  ```

Every externally visible analytical result should have a lineage path back to the underlying canonical state where technically applicable.

---

# 14. Derived Knowledge

Annotations, embeddings, indexes and graph projections are derived state.

Examples:

```text
  Source changes
  → extraction may change

Extraction changes
  → chunks may change

Chunking changes
  → annotations may change

Annotation rule changes
  → dependent annotations may change

Embedding model changes
  → embeddings may change
  ```

The platform therefore requires explicit recalculation semantics.

---

# 15. Knowledge Projections

Canonical semantic knowledge may be projected into multiple technologies.

```text
  Semantic Knowledge
      │
  ┌─────┼─────┐
  ▼   ▼   ▼
  Index Vector Graph
  ```

Projections are derived, replaceable and lineage-aware.

---

# 16. Reverse Index

The reverse index is optimized for:

- lexical search;
  - exact matching;
  - filtering;
  - annotation lookup;
  - metadata;
  - security constraints.

It references canonical chunks rather than becoming the authoritative knowledge source.

For classified chunks, the index must preserve the representation contract inherited from Design 1.23.

---

# 17. Vector Projection

Vector projection supports semantic similarity.

```text
  Chunk
   │
   ▼
  Embedding Model
   │
   ▼
  Vector
  ```

Embeddings are derived state.

Changing an embedding model may trigger selective or complete vector recalculation without changing source content.

Classified original representations must not share vectors with masked representations.

---

# 18. Graph Projection

The graph represents relationships between knowledge objects.

```text
  Customer
    │
    └── submitted → Ticket
              │
              ├── contains → Invoice
              ├── concerns → Product
              └── discussed-in → Call
  ```

Graph data is derived from canonical knowledge and annotations.

For Dual representations, graph entities and edges retain classification and representation metadata, following Design 1.23.

---

# 19. Security Classification

Security classification remains governed by Design 1.23.

Classification belongs to the content/knowledge object:

```text
  security = CONFIDENTIAL
  ```

Authorization is separate:

```text
  User
  ↓
  Group / Role
  ↓
  class_grants
  ↓
  Allowed Classification
  ↓
  Authorization
  ```

The security model is:

```text
  resource_acl ∧ class_grants
  ```

not either control independently.

---

# 20. Classify Once, Authorize Dynamically

The central security principle is:

> **Classification is content state. Authorization mapping is policy state.**

If a chunk is:

```text
  classification = CONFIDENTIAL
  ```

and a group loses access to that class, the chunk itself does not need to be rewritten.

Current authorization is evaluated against current policy.

---

# 21. Masking and Representation

Masking is distinct from classification.

Classification answers:

> How sensitive is this content?

Masking answers:

> Which representation may be exposed?

The inherited representation model is:

```text
  Single
  Dual
  Masked-only
  ```

A **Single** representation exists when masking makes no change.

A **Dual** representation exists when masking changes content and the policy permits original storage.

A **Masked-only** representation exists when masking changes content and `store_original: false`.

Design 1.23 defines the important invariant that a Masked-only original is never persisted downstream.

---

# 22. Analytics Plane

The Analytics Plane observes platform activity and knowledge state.

Its responsibilities include:

- metrics;
  - statistics;
  - aggregates;
  - reports;
  - operational measurements;
  - knowledge-state measurements;
  - annotation analytics;
  - processing analytics;
  - security analytics;
  - cost analytics.

It is not authoritative for source content or canonical knowledge.

> **Analytics is derived state.**

---

# 23. Analytics Architecture

```text
  Platform Activity
      │
      ▼
  Analytics Events
      │
      ▼
  Analytical Facts
      │
      ▼
  Aggregates
      │
      ▼
  Metrics
      │
      ▼
  Reports
  ```

Analytics consumes protected knowledge state rather than bypassing security boundaries.

---

# 24. Analytics Event Boundary

Analytical events must be emitted after the applicable security classification and representation decision.

Prohibited:

```text
  Extraction
    │
    ├── analytics ← prohibited pre-security path
    │
    ▼
  Classification / Masking
  ```

Preferred:

```text
  Extraction
    ↓
  Semantic Content
    ↓
  Classification / Masking
    ↓
  Protected Knowledge Layer
    │
    ├── Search
    ├── Projection
    └── Analytics Events
  ```

Analytics must never become an uncontrolled side channel for sensitive source data.

---

# 25. Analytical Facts

An **analytical fact** is an observed or derived measurable property.

Examples:

```text
  documents_processed = 1248
  ```

or:

```text
  annotation_type = payment
  count = 731
  ```

Analytical facts retain sufficient provenance, tenant scope and security metadata to support authorization and lineage.

Formal schema/type names use `AnalyticalFact`.

The conceptual term is always written as **analytical facts**.

---

# 26. Analytical Fact Security

Analytical facts derived from protected content inherit applicable security constraints.

Conceptually:

```text
  AnalyticalFact
  ├── tenant_id
  ├── source_classification
  ├── representation_used
  ├── derived_classification
  ├── access_scope
  └── provenance
  ```

Default propagation:

```text
  derived_classification =
    maximum applicable source classification
  ```

unless a stricter registered policy applies.

A fact must never be classified less restrictively merely because aggregation has removed the original literal.

---

# 27. Representation Rules for Analytics

If a source is Masked-only:

```text
  Source
  → Masked-only
  → Analytics may derive only from masked representation
  ```

If a source is Dual:

```text
  Source
  → Original + Masked
  → Analytics records which representation was used
  ```

If a source is Single:

```text
  Source
  → Single
  → Analytics uses the available representation
  ```

Analytics must never recreate an unavailable original representation.

---

# 28. Aggregate Side-Channel Protection

Aggregates can reveal sensitive information even when individual records are hidden.

Protection mechanisms include:

- minimum group size;
  - suppression;
  - rounding;
  - bucketing;
  - noise where explicitly approved;
  - restricted dimensions;
  - restricted time windows.

A concrete policy example:

```yaml
  aggregate_policy:
   classification: RESTRICTED
   minimum_group_size: 5
   suppression: true
   rounding: 2
   allowed_dimensions:
    - tenant
    - month
   prohibited_dimensions:
    - customer_id
    - employee_id
    - exact_location
  ```

For this policy, a group containing fewer than five qualifying subjects is suppressed. Permitted values are rounded to two decimal places. `customer_id`, `employee_id` and `exact_location` cannot be used as dimensions.

Tenant configuration may strengthen protection but must not weaken platform security policy.

Aggregate protection is enforced at query time and/or materialization time for pre-aggregated views.

---

# 29. Analytics Security Registry

Analytics security policies are centrally governed.

The registry defines:

```text
  Metric
  Report
  Aggregate
  Classification
  Tenant Scope
  Allowed Dimensions
  Suppression Policy
  Minimum Group Size
  Representation Requirements
  ```

The registry validates metric security declarations against underlying source classification policies.

A metric cannot declare itself `PUBLIC` if its source facts violate the applicable sharing policy.

---

# 30. Annotation Analytics

Annotation analytics measure knowledge interpretation.

Examples:

```text
  annotation count
  annotation rate
  annotation confidence
  annotation processing time
  annotation failure rate
  annotation version distribution
  ```

Version-aware analytics allow direct comparison between definition versions.

---

# 31. Annotation Fact Schema

The formal table/schema is `analytical_facts` and the annotation-specific type is `AnalyticalFact`.

An annotation analytical fact should include:

```text
  AnalyticalFact
  ├── fact_id
  ├── tenant_id
  ├── source_id
  ├── chunk_id
  ├── definition_id
  ├── definition_version
  ├── annotation_type
  ├── namespace
  ├── name
  ├── value
  ├── producer
  ├── producer_version
  ├── target_type
  ├── target_id
  ├── confidence
  ├── processing_duration
  ├── evaluation_run_id
  ├── source_classification
  ├── representation_used
  ├── provenance
  ├── observed_at
  └── invalidated_at
  ```

---

# 32. Analytics Tables

The initial analytical model may contain:

```text
  analytics_events
  content_facts
  chunk_facts
  annotation_facts
  processing_facts
  projection_facts
  search_facts
  security_facts
  recalculation_facts
  cost_facts
  ```

The architecture standardizes the concept as **analytical facts** while reserving `AnalyticalFact` for a formal schema/type.

New fact types may be introduced without changing the storage contract.

---

# 33. Security Facts

Security analytics are first-class analytical data.

Examples:

```text
  classification distribution
  masking outcomes
  authorization decisions
  policy changes
  security remediation
  classification transitions
  ```

Security facts remain subject to the same security model as all other analytical facts.

---

# 34. Processing Analytics

Processing facts capture:

```text
  documents processed
  chunks created
  annotations generated
  processing duration
  success/failure
  retry counts
  resource consumption
  ```

These support operations, capacity planning and cost analysis.

---

# 35. Search Analytics

Search analytics may include:

```text
  query count
  latency
  result count
  search type
  filter usage
  zero-result rate
  ```

Sensitive query content requires additional protection.

Top-search-term reports must apply query-side sanitization consistent with Design 1.23.

---

# 36. Cost Analytics

Cost analytics may include:

```text
  LLM tokens
  model invocation count
  processing cost
  storage cost
  compute cost
  cost per tenant
  cost per document
  cost per annotation
  ```

Cost data can reveal sensitive business information and therefore inherits appropriate classification and tenant controls.

---

# 37. Analytics Lineage

The complete lineage chain is:

```text
  Source
  → ECM Element
  → Chunk
  → Annotation
  → Processing Run
  → Knowledge Projection
  → Analytics Event
  → Analytical Fact
  → Aggregate
  → Metric
  → Report
  ```

A report should be explainable down to the metric version and, where applicable, to the fact and source lineage.

---

# 38. Analytics Storage Contract

The Analytics Plane uses a storage abstraction:

```text
  Analytics Storage Contract
       │
    ┌─────┼─────┐
    ▼   ▼   ▼
  ClickHouse Other Future
  ```

The initial implementation may use ClickHouse because it supports:

- columnar analytics;
  - large event volumes;
  - aggregation;
  - time-series workloads;
  - materialized views;
  - compression.

However:

> **ClickHouse is an implementation choice, not an architectural contract.**

---

# 39. ClickHouse Independence

The implementation must avoid unnecessary coupling to ClickHouse-specific behavior.

The platform should document:

- partitioning;
  - compression;
  - materialized views;
  - replication;
  - cluster topology;
  - retention;
  - backup;
  - restore.

No ClickHouse-specific procedure is part of the architectural contract.

`analytics_events` remains the replayable source boundary from which derived analytical state can be rebuilt.

---

# 40. Analytics Event Stream

The analytical event stream provides a durable reconstruction boundary.

```text
  Protected Knowledge
      │
      ▼
  Analytics Events
      │
      ├── Facts
      ├── Aggregates
      ├── Metrics
      └── Reports
  ```

If the analytical store changes:

```text
  analytics_events
      │
      ▼
  New Analytics Storage
  ```

Derived state can be rebuilt.

Events must contain stable identifiers, schema version and sufficient metadata to support deterministic replay.

---

# 41. Analytics Freshness

Metrics use explicit freshness classes:

```text
  Real-time
  Near-real-time
  Hourly
  Daily
  ```

Freshness belongs to the metric/report definition.

Example:

```text
  Operational latency:
  Near-real-time

Executive report:
  Daily
  ```

Freshness is observable and included in acceptance criteria.

---

# 42. Analytics API

The Analytics API provides programmatic access to:

- metrics;
  - reports;
  - aggregates;
  - analytical facts where permitted;
  - lineage;
  - definitions.

Customer-facing APIs require:

- authentication;
  - authorization;
  - tenant isolation;
  - rate limiting;
  - quotas;
  - aggregate protection;
  - result sanitization.

---

# 43. Analytics MCP Boundary

MCP may expose selected capabilities:

```text
  get_metric
  query_report
  inspect_analytics
  explain_metric
  retrieve_lineage
  ```

Every analytics MCP request must pass through the canonical authorization and aggregation pipeline.

MCP is an interface, not a security bypass.

---

# 44. Analytics Query Pipeline

The canonical query path is:

```text
  Request
   ↓
  Authentication
   ↓
  Tenant Resolution
   ↓
  Authorization
   ↓
  Classification Filtering
   ↓
  Representation Selection
   ↓
  Query Sanitization
   ↓
  Aggregate Protection
   ↓
  Metric / Report Query
   ↓
  Result Sanitization
   ↓
  Response
  ```

No external interface may bypass this sequence.

---

# 45. Report-Level Sanitization

Reports may expose information indirectly through:

- search terms;
  - dimensions;
  - counts;
  - error messages;
  - user identifiers;
  - timing patterns;
  - small populations.

Report generation therefore applies query-side sanitization and aggregate protection consistently with Design 1.23.

Report definitions must identify security and aggregate policies explicitly.

---

# 46. Multi-Tenant Analytics

Every tenant-scoped analytical fact must have an explicit tenant scope.

```text
  tenant_id
  ```

is mandatory for tenant-scoped facts.

### Platform-wide metrics

Platform-wide metrics that are not attributable to a single tenant use a reserved tenant scope:

```text
  tenant_id = system
  ```

These metrics are:

- accessible only to administrative users with explicit authorization;
  - excluded from tenant-scoped APIs;
  - excluded from ordinary tenant aggregates;
  - excluded from cross-tenant customer reporting;
  - explicitly marked as `platform_scope = SYSTEM`.

A tenant-scoped query must never implicitly expand to `tenant_id = system`.

Cross-tenant sharing otherwise follows the Design 1.23 policy.

---

# 47. Analytics Data Isolation

Tenant isolation exists at multiple layers:

```text
  Storage
  Query
  Cache
  API
  MCP
  Materialized Views
  Reports
  ```

No tenant may infer another tenant's protected analytical state through aggregation, cache reuse, error behavior or timing.

---

# 48. Recalculation

Recalculation brings derived knowledge and analytics back into consistency.

Two related forms exist:

```text
  Knowledge Recalculation
      │
      ▼
  Updated Derived Knowledge

Analytics Recalculation
      │
      ▼
  Updated Derived Analytics
  ```

Analytics follows knowledge recalculation rather than becoming an independent source of truth.

---

# 49. Resolutor

Resolutor determines **what needs to change**.

Inputs may include:

```text
  Source changes
  Extraction changes
  Chunking changes
  Annotation rule changes
  Annotation definition changes
  Model changes
  Dictionary changes
  Dependency changes
  Security classification changes
  ```

Output:

```text
  Affected objects
  Affected dependencies
  Affected projections
  Affected analytics
  Recalculation plan
  ```

Resolutor is deterministic for a given dependency graph and change set.

---

# 50. Equalix

Equalix determines **how changes are executed safely**.

Workloads include:

```text
  Incremental ingestion
  Interactive processing
  User-triggered recalculation
  Historical recalculation
  Analytics rebuilds
  Projection rebuilds
  ```

The central principle is:

> **Background maintenance must not starve incremental and interactive workloads.**

Equalix therefore applies priority, concurrency, resource and retry policies.

---

# 51. Change Impact Model

The impact model is explicit:

| Change | Extraction | Chunking | Annotation | Index | Vector | Graph | Analytics |
  |---|---:|---:|---:|---:|---:|---:|---:|
  | Source content | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
  | Extraction logic | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
  | Chunking logic | — | ✓ | ✓ | ✓ | ✓ | ✓ | ✓ |
  | Annotation rule | — | — | ✓ | ✓ | maybe | maybe | ✓ |
  | Annotation dependency | — | — | ✓ | ✓ | maybe | maybe | ✓ |
  | Embedding model | — | — | — | — | ✓ | — | ✓ |
  | Classification logic | — | — | ✓ | ✓ | policy | policy | ✓ |
  | Group/class mapping | — | — | — | — | — | — | query policy |
  | Masking policy | — | policy | policy | policy | policy | policy | ✓ |
  | Metric definition | — | — | — | — | — | — | ✓ |
  | Aggregate policy | — | — | — | — | — | — | ✓ |

The exact scope is determined by dependency metadata and security policy.

---

# 52. Annotation Recalculation

When an annotation definition changes:

```text
  Definition v3
     ↓
  Definition v4
     ↓
  Resolutor
     ↓
  Affected targets
     ↓
  Equalix
     ↓
  Evaluation Run
     ↓
  New Annotations
     ↓
  Analytics Events
  ```

Historical facts may remain available for comparison unless governance requires invalidation.

---

# 53. Analytics Recalculation

When upstream knowledge changes:

```text
  Knowledge Change
     ↓
  Resolutor
     ↓
  Affected Analytics
     ↓
  Equalix
     ↓
  Incremental Recalculation
  ```

The system should avoid rebuilding unrelated historical analytics.

---

# 54. Security Reclassification

Security classification changes require special handling.

Example:

```text
  CONFIDENTIAL
     ↓
  RESTRICTED
  ```

The platform must not expose a previously authorized analytical result under the new policy.

Three treatment classes are supported:

1. **Recalculation/invalidation** — preferred for current-state metrics.
2. **Historical validity windows** — required where audit/compliance requires historical truth to remain queryable under the policy that existed at the time.
3. **Query-time evaluation** — suitable for authorization mapping changes where the underlying fact has not changed.

The preferred implementation is:

```text
  Historical Fact
   +
  valid_from
   +
  valid_to
   +
  current policy
  ```

with current authorization evaluated at query time.

---

# 55. Security Mapping Changes

A group-to-classification mapping change does not require rewriting all analytical facts.

Example:

```text
  SUPPORT_L2
    ↓
  CONFIDENTIAL denied
  ```

Facts remain unchanged.

The authorization layer evaluates current policy.

Authorization-sensitive analytical caches must be invalidated when security mappings change, consistent with Design 1.23.

---

# 56. Analytics Cache

Caches may be used for:

- dashboard results;
  - expensive aggregates;
  - common reports;
  - metric calculations.

Authorization-sensitive cache entries must include sufficient security scope, tenant scope and policy version.

Invalidation is required when:

- security mappings change;
  - aggregate policies change;
  - metric definitions change;
  - source classification changes in affected data;
  - representation policy changes;
  - report security policy changes.

---

# 57. Schema Evolution

Analytical schemas favor additive evolution.

Consumers must tolerate:

```text
  new optional fields
  new dimensions
  new fact types
  ```

Breaking changes require a new schema version.

Rebuild options include:

```text
  Retained events
     ↓
  Replay
     ↓
  New schema
  ```

or controlled background transformation.

Data must not become unqueryable because a schema evolved.

---

# 58. ClickHouse Schema Migration

The initial implementation should use controlled migrations:

```text
  v1 table
    ↓
  Additive schema
    ↓
  Backfill
    ↓
  Validation
    ↓
  Consumer migration
  ```

For major structural changes:

```text
  table_v1
  table_v2
    ↓
  Parallel validation
    ↓
  Cutover
  ```

Migrations require:

- versioned scripts;
  - staging validation;
  - rollback procedures;
  - integrity checks;
  - zero-downtime consideration.

---

# 59. Event Volume and Retention

Raw analytics events can grow rapidly.

Each event/fact type requires a documented retention class.

Example:

```text
  Operational events → short retention
  Processing facts → medium retention
  Business metrics → long retention
  Audit/security facts → policy-defined retention
  ```

Retention is automatic and observable.

The implementation monitors:

- event volume;
  - storage growth;
  - compression ratio;
  - retention effectiveness;
  - storage cost.

---

# 60. Late and Out-of-Order Events

Analytics must tolerate events arriving after their nominal time window.

The default initial late-event window is:

```text
  24 hours
  ```

Late events should:

1. be accepted where policy permits;
2. update affected aggregates;
3. be observable;
4. contribute to late-event metrics.

Large historical backfills require an explicit rebuild mechanism.

---

# 61. Error Handling

### Transient errors

```text
  Retry
  +
  Exponential backoff
  ```

### Permanent processing errors

```text
  Dead-letter queue
  +
  Alert
  ```

### Schema errors

```text
  Quarantine
  +
  Operator intervention
  ```

### Security policy errors

```text
  Immediate failure
  +
  No automatic retry
  +
  Security alert
  ```

Security failures fail closed.

---

# 62. Operational Isolation

Analytics must not degrade core platform workloads.

Controls include:

- CPU limits;
  - memory limits;
  - I/O limits;
  - query timeouts;
  - result-size limits;
  - workload priorities;
  - resource pools;
  - background queues.

Interactive workloads have priority over large historical analytical jobs.

Equalix coordinates these workloads.

---

# 63. Analytics Alerting

Initial alerts include:

```text
  Consumer lag > 5 minutes
  Event loss rate > 0.1%
  Query latency p95 > 1 second
  Storage capacity > 80%
  Aggregate freshness > 2× expected
  Security policy failure
  Schema validation failure
  Dead-letter queue growth
  Late-event rate anomaly
  ```

Thresholds should become configurable by deployment profile.

---

# 64. ClickHouse Deployment Model

The initial production topology must be selected from workload evidence.

The implementation defines:

- cluster size;
  - replication factor;
  - sharding;
  - Keeper topology;
  - backup strategy;
  - restore procedure;
  - monitoring;
  - failure recovery.

The PoC starts with a minimal topology and validates scale before production sizing.

---

# 65. Storage Estimation

Capacity planning estimates:

```text
  events/day
  ×
  average event size
  ×
  retention
  ×
  replication
  ÷
  compression ratio
  ```

Before production sizing is finalized, measure:

- event size;
  - compressed size;
  - compression ratio;
  - sustained events/second;
  - query workload;
  - storage growth.

---

# 66. First Analytical Report

The first end-to-end report is deliberately simple:

```yaml
  report_id: daily-platform-processing
  version: 1

metrics:
   - documents_processed
   - documents_failed
   - annotations_created
   - processing_latency_p95

dimensions:
   - tenant
   - media_type
   - error_type
   - annotation_type

refresh: daily

security:
   tenant_isolated: true
   classification_aware: true
  ```

This validates:

```text
  Platform
  → Events
  → Facts
  → Aggregates
  → Metrics
  → Report
  → Authorization
  ```

---

# 67. Initial Metrics

### Processing

```text
  documents_processed
  documents_failed
  processing_latency
  throughput
  ```

### Annotation

```text
  annotations_created
  annotation_rate
  annotation_confidence
  annotation_latency
  ```

### Search

```text
  queries
  latency
  zero_result_rate
  ```

### Knowledge

```text
  chunks_created
  entities_detected
  relationships_created
  ```

### Security

```text
  classification_distribution
  masking_outcomes
  authorization_decisions
  ```

### Recalculation

```text
  recalculation_jobs
  affected_objects
  processing_duration
  failure_rate
  ```

### Cost

```text
  llm_cost
  storage_cost
  compute_cost
  cost_per_tenant
  ```

---

# 68. Classification Distribution Metrics

Security posture should be measurable:

```text
  documents by classification
  chunks by classification
  annotations by classification
  classification trend over time
  ```

These metrics remain classification-aware and tenant-isolated.

---

# 69. Masking Outcome Metrics

The platform measures representation outcomes:

```text
  Single
  Dual
  Masked-only
  ```

Example:

```text
  tenant A

Single    82%
  Dual     14%
  Masked-only  4%
  ```

These metrics help operators understand security-policy impact without exposing protected content.

---

# 70. Aggregate Governance

Aggregate protection rules are centrally registered.

Tenant configuration may strengthen but not weaken global constraints.

Policy changes are:

- authenticated;
  - authorized;
  - audited;
  - versioned;
  - observable.

Every metric/report references the aggregate protection policy under which it is evaluated.

---

# 71. Analytics Registry

The Analytics Registry manages:

```text
  Metric definitions
  Report definitions
  Fact schemas
  Aggregate definitions
  Security policies
  Freshness requirements
  Retention policies
  ```

A metric definition includes:

```text
  metric_id
  version
  source_facts
  dimensions
  aggregation
  freshness
  security_policy
  aggregate_policy
  ```

---

# 72. Metric Lifecycle

Metrics follow:

```text
  Draft
   ↓
  Validated
   ↓
  Published
   ↓
  Deprecated
   ↓
  Retired
  ```

Published metric definitions are immutable. Changes create new versions.

---

# 73. Report Lifecycle

Reports follow:

```text
  Draft
   ↓
  Validated
   ↓
  Published
   ↓
  Deprecated
   ↓
  Retired
  ```

Published reports reference explicit metric versions.

---

# 74. Analytics and Annotation Recalculation

When annotations are recalculated:

```text
  Annotation Definition Change
      ↓
  Resolutor
      ↓
  Affected Annotation Targets
      ↓
  Equalix
      ↓
  Processing Run
      ↓
  Updated Annotations
      ↓
  Analytics Events
      ↓
  Affected Analytical Facts
      ↓
  Updated Aggregates
  ```

Analytics remains downstream of knowledge computation.

---

# 75. Historical Analytics

Historical analytical facts may preserve prior versions where useful.

Example:

```text
  payment-detection v3
  payment-detection v4
  ```

This supports:

- model evaluation;
  - rule comparison;
  - regression analysis;
  - historical reporting.

Historical facts remain subject to retention and security policy.

---

# 76. Analytics Does Not Become Source of Truth

Authoritative systems remain:

```text
  Source content
  → source system / object storage

Semantic content
  → canonical content model

Annotations
  → annotation / knowledge model

Security policy
  → security policy system

Relationships
  → canonical relationship model
  ```

Analytics observes these systems.

It must never substitute for authoritative knowledge.

---

# 77. Migration and Replay

Because analytics is derived state:

```text
  analytics_events
     ↓
  new storage implementation
     ↓
  facts
     ↓
  aggregates
     ↓
  metrics
  ```

Migration to another analytical database must not require changes to canonical knowledge.

Replay must be deterministic for the same event stream, schema versions and metric definitions.

---

# 78. Performance Requirements

Initial interactive dashboard target:

```text
  p95 < 500 ms
  ```

under the defined reference workload.

Analytical workloads must not materially degrade interactive platform processing.

Long-running queries must be:

- bounded;
  - observable;
  - cancellable;
  - resource-controlled.

---

# 79. Security Testing

Analytics security testing extends Design 1.23.

CI includes:

```text
  test:security
     │
     └── test:analytics-security
  ```

The analytics security tier validates:

- tenant isolation;
  - classification propagation;
  - masking boundaries;
  - aggregate suppression;
  - cross-tenant restrictions;
  - cache invalidation;
  - report sanitization;
  - MCP authorization;
  - query-path enforcement;
  - platform-scope isolation.

Tests use a representative negative-security corpus.

---

# 80. Security Negative Corpus

The corpus includes:

```text
  PUBLIC
  INTERNAL
  CONFIDENTIAL
  RESTRICTED
  MASKED-ONLY
  DUAL
  ORIGINAL-RESTRICTED
  SYSTEM-SCOPE
  ```

Tests verify that unauthorized users cannot recover protected information through:

- direct results;
  - aggregates;
  - reports;
  - search terms;
  - statistics;
  - cached results;
  - MCP;
  - timing-sensitive behavior.

---

# 81. Auditability

The system provides audit information for:

- metric creation;
  - report publication;
  - security policy changes;
  - aggregate policy changes;
  - analytics queries where required;
  - data export;
  - administrative changes.

Audit records are themselves protected data.

---

# 82. Observability

The Analytics Plane exposes operational telemetry for:

```text
  event ingestion
  consumer lag
  processing throughput
  fact generation
  query latency
  aggregate freshness
  storage utilization
  compression
  retention
  recalculation
  security failures
  ```

Observability distinguishes:

```text
  platform health
  analytics health
  security health
  ```

---

# 83. Failure Recovery

The analytics system tolerates:

- consumer restart;
  - storage restart;
  - duplicate events;
  - out-of-order events;
  - partial aggregation failure;
  - schema incompatibility;
  - temporary ClickHouse failure.

Events support idempotent processing.

---

# 84. Idempotency

Analytics consumers use deterministic event identifiers:

```text
  analytics_event_id
  ```

Repeated delivery must not create unintended duplicate facts.

```text
  Event
  ↓
  Deduplication
  ↓
  Fact
  ```

Fact materialization must be idempotent.

---

# 85. Exactly-Once vs Effectively-Once

The architecture does not require global exactly-once distributed semantics.

Preferred model:

> **At-least-once delivery + deterministic processing + idempotent materialization = effectively-once analytical results.**

This simplifies operational recovery while maintaining correctness.

---

# 86. Data Quality

Analytics detects:

- missing events;
  - duplicate events;
  - invalid dimensions;
  - impossible values;
  - stale aggregates;
  - broken lineage;
  - schema violations.

Quality metrics are themselves observable.

---

# 87. Governance

The Analytics Plane has explicit ownership for:

```text
  Metric definitions
  Retention
  Security policies
  Aggregate policies
  Schema versions
  Report definitions
  Data quality
  ```

No externally visible metric may be published without registry validation.

---

# 88. API and Integration Boundary

The architecture separates:

```text
  Internal Contracts
      │
      ├── Analytics API
      ├── MCP
      └── Reporting Integrations
  ```

External integrations never bypass canonical authorization, representation selection and aggregate protection.

---

# 89. Architectural Invariants

## Invariant 1

Analytics is derived state.

## Invariant 2

Canonical knowledge remains authoritative.

## Invariant 3

Security classification is not authorization mapping.

## Invariant 4

Authorization is evaluated using current policy.

## Invariant 5

Analytics cannot bypass masking or representation rules.

## Invariant 6

Aggregates cannot bypass security.

## Invariant 7

Tenant boundaries apply to analytics.

## Invariant 8

Platform-wide analytics use explicit `system` scope and never enter tenant-scoped APIs.

## Invariant 9

Derived knowledge and analytics remain recalculable.

## Invariant 10

Analytics storage is replaceable.

## Invariant 11

Processing provenance is preserved.

## Invariant 12

Background recalculation cannot starve interactive workloads.

## Invariant 13

No external analytics interface may bypass the canonical query pipeline.

## Invariant 14

A Masked-only original representation is never persisted.

## Invariant 15

Security-sensitive caches are invalidated when their authorization assumptions become stale.

---

# 90. Implementation Phases

## Phase 1 — Annotation Foundation

Implement:

- annotation definitions;
  - schema;
  - versioning;
  - provenance;
  - processing runs;
  - dependency graph.

## Phase 2 — Recalculation

Implement:

- change detection;
  - Resolutor;
  - dependency analysis;
  - recalculation plans;
  - Equalix;
  - lifecycle handling.

## Phase 3 — Knowledge Projections

Implement:

- reverse index projection;
  - vector projection;
  - graph projection;
  - projection provenance.

## Phase 4 — Analytics PoC

Implement:

- analytics events;
  - analytical facts;
  - ClickHouse adapter;
  - initial aggregates;
  - initial metrics.

## Phase 5 — Analytics Security

Implement:

- classification propagation;
  - tenant isolation;
  - representation boundaries;
  - aggregate protection;
  - query sanitization;
  - security CI.

## Phase 6 — Reporting

Implement:

- Analytics Registry;
  - metric definitions;
  - report definitions;
  - freshness;
  - dashboards/API.

## Phase 7 — Production Hardening

Implement:

- operational runbook;
  - load testing;
  - storage sizing;
  - retention;
  - backups;
  - disaster recovery;
  - alerting.

## Phase 8 — MCP / External Integration

Expose selected analytics capabilities through:

- Analytics API;
  - MCP;
  - integration adapters.

---

# 91. ClickHouse Evaluation

The PoC evaluates:

## Performance

```text
  ingestion throughput
  aggregation throughput
  query latency
  concurrent queries
  ```

**Ingestion target:** sustained ingestion must meet or exceed the expected peak event rate plus **50% headroom**.

The exact target is workload-dependent and must be filled from workload modelling:

```text
  target_ingestion_eps >= peak_expected_event_rate_eps × 1.5
  ```

For the initial evaluation, record:

```text
  expected_peak_eps: [X]
  target_sustained_eps: [1.5X]
  observed_sustained_eps: [Y]
  ```

The evaluation must include the first analytical report workload and a representative mix of inserts, aggregates and concurrent dashboard queries.

## Storage

```text
  compression
  partitioning
  retention
  storage growth
  ```

## Reliability

```text
  node failure
  restart
  recovery
  backup
  restore
  ```

## Operational Complexity

```text
  deployment
  monitoring
  upgrades
  schema migration
  ```

## Migration

Confirm that the Analytics Storage Contract allows replacement without changes to canonical knowledge or external API contracts.

---

# 92. Operational Runbook

Before production adoption, produce a ClickHouse operational runbook covering:

- deployment;
  - scaling;
  - partition management;
  - merge behavior;
  - replication;
  - Keeper;
  - backup;
  - restore;
  - capacity planning;
  - incident response;
  - schema migrations;
  - retention;
  - disaster recovery.

Production-scale load testing must precede final topology selection.

---

# 93. Acceptance Criteria

The consolidated architecture is accepted when:

## Knowledge

- annotations are first-class;
  - definitions are versioned;
  - dependencies are explicit;
  - provenance is preserved;
  - processing runs are traceable.

## Recalculation

- source changes produce correct impact plans;
  - annotation changes trigger only affected recalculation;
  - Resolutor determines affected state;
  - Equalix executes controlled workloads.

## Security

- classification propagates correctly;
  - Masked-only data cannot leak originals;
  - tenant isolation is enforced;
  - cross-tenant rules follow Design 1.23;
  - aggregate side channels are protected;
  - authorization-sensitive caches are invalidated.

## Analytics

- events are emitted after the protected knowledge boundary;
  - analytical facts retain lineage;
  - analytics storage is replaceable;
  - ClickHouse PoC meets the workload-derived ingestion target;
  - retention is automatically enforced;
  - late events are handled;
  - duplicate events are safely processed.

## Reporting

- metric definitions are versioned;
  - reports reference explicit metric versions;
  - freshness requirements are enforced;
  - security policies are validated;
  - the first platform-processing report works end-to-end.

## Performance

- dashboard queries target p95 < 500 ms;
  - ingestion target provides at least 50% headroom above expected peak;
  - analytical workloads do not materially degrade interactive workloads.

## Operations

- monitoring exists;
  - alerts exist;
  - backup/restore is tested;
  - operational runbook exists;
  - schema migration is tested;
  - replay/rebuild is demonstrated.

---

# 94. Architectural Summary

```text
               SYNANTON
                │
         ┌─────────────┴─────────────┐
         │              │
      Content Plane        Knowledge Plane
         │              │
         ▼              ▼
       Extraction          Annotation
         │              │
         ▼              ▼
      Semantic Content      Derived Knowledge
         │              │
         └─────────────┬─────────────┘
                ▼
             Semantic Chunks
                │
         ┌─────────────┼─────────────┐
         ▼       ▼       ▼
       Reverse Index  Vector Store  Graph
         │       │       │
         └─────────────┼─────────────┘
                ▼
               Search
                │
           ┌──────────┴──────────┐
           ▼           ▼
       Query Semantics    Security Policy
                      │
                   Group → Class
                      │
                      ▼
                   Authorization
                      │
                      ▼
                    Results
                      │
                      ▼
                 Analytics Events
                      │
                      ▼
                 Analytical Facts
                      │
                      ▼
                   Aggregates
                      │
                      ▼
                    Metrics
                      │
                      ▼
                    Reports

Rules / Models / Dictionaries / Source / Policy Changes
                │
                ▼
               Resolutor
                │
                ▼
            Dependency Analysis
                │
                ▼
               Equalix
                │
                ▼
              Recalculation
                │
                ▼
             Updated Knowledge
                │
                ▼
             Updated Analytics
  ```

---

# 95. Final Architectural Thesis

Design 1.24/1.25 establishes Synanton as a platform where enterprise knowledge is continuously derived, connected, secured, recalculated and measured.

The lifecycle is:

```text
  Extract
    ↓
  Structure
    ↓
  Chunk
    ↓
  Classify
    ↓
  Annotate
    ↓
  Connect
    ↓
  Project
    ↓
  Search
    ↓
  Authorize
    ↓
  Measure
    ↓
  Report
  ```

When enterprise understanding changes:

```text
  Change
   ↓
  Resolve impact
   ↓
  Recalculate affected knowledge
   ↓
  Update projections
   ↓
  Update analytical state
  ```

The architecture separates four fundamentally different concerns:

```text
  Acquisition
    ↓
  Interpretation
    ↓
  Authorization
    ↓
  Measurement
  ```

while connecting them through explicit contracts, provenance, dependency-aware recalculation and security-aware representations.

The central principle is:

> **Extract once. Interpret flexibly. Preserve provenance. Classify securely. Project deliberately. Recalculate selectively. Measure without becoming the source of truth.**

Synanton therefore becomes a platform for **continuously evolving enterprise knowledge**, rather than a static document-ingestion, search or analytics system.

---

# Appendix A — Unified Glossary

| Term | Definition |
  |---|---|
  | **ECM** | Extracted Content Model; the structured representation  produced from source content before higher-level interpretation. |
  | **Semantic Chunk** | Independently addressable knowledge unit derived from semantic content. |
  | **Annotation** | Structured interpretation attached to a knowledge target. |
  | **Annotation Definition** | Versioned contract describing how an annotation is produced. |
  | **Annotation Definition Version** | Immutable version of an annotation definition. |
  | **Analytical Fact** | Formal measurable observation or derived property used by the Analytics Plane. |
  | **analytical facts** | General conceptual term for facts generated or derived by analytics. |
  | **`analytical_facts`** | Canonical logical table/fact-store concept for analytical facts. |
  | **Aggregate** | Computed summary over analytical facts. |
  | **Metric** | Named, versioned analytical definition that computes a measurable result. |
  | **Report** | Presentation contract composed from explicit metric versions and dimensions. |
  | **Analytics Event** | Durable event describing platform activity or protected knowledge state for analytical processing. |
  | **Analytics Registry** | Governance registry for metrics, reports, schemas, aggregate policies, freshness and retention. |
  | **Classification** | Security sensitivity state attached to content/knowledge. |
  | **Authorization** | Decision determining whether a caller may access a resource/class/representation. |
  | **`class_grants`** | Policy data mapping users, groups or roles to permitted classifications. |
  | **Single Representation** | One representation exists because masking made no change. |
  | **Dual Representation** | Masked and original representations exist  because masking changed content and original storage is permitted. |
  | **Masked-only Representation** | Only masked content exists because policy prohibits original persistence. |
  | **Masking** | Transformation from a source representation to a less revealing representation. |
  | **Provenance** | Information describing where a derived object came from and how it was produced. |
  | **Processing Run** | Traceable execution instance responsible for producing or updating derived state. |
  | **Dependency DAG** | Directed acyclic graph describing computational dependencies between derived objects. |
  | **Derived Knowledge** | Knowledge produced from source content or  other knowledge rather than being authoritative source state. |
  | **Projection** | Derived representation of canonical knowledge optimized for a particular access pattern. |
  | **Resolutor** | Component that determines what derived state is affected by a change. |
  | **Equalix** | Component that coordinates and executes recalculation and other controlled workloads. |
  | **Freshness** | Maximum tolerated age of a metric/report result. |
  | **Aggregate Policy** | Security policy controlling population size,  suppression, rounding and permitted analytical dimensions. |
  | **Tenant Scope** | Explicit boundary identifying the tenant to which a fact belongs. |
  | **System Scope** | Reserved `system` tenant scope for platform-wide metrics not attributable to one tenant. |
  | **Lineage** | Traceable chain connecting reports and metrics back to analytical facts and canonical knowledge. |
  | **Replay** | Reprocessing durable analytics events to rebuild derived analytical state. |
  | **Effectively-once** | Analytical correctness achieved through  at-least-once delivery, deterministic processing and idempotent  materialization. |
  | **Query Sanitization** | Removal or transformation of query-side information that could disclose protected data. |
  | **Security Reclassification** | Change to the classification of existing content/knowledge. |
  | **Security Mapping Change** | Change to authorization mappings without changing the underlying classified content. |
  | **MCP** | Model Context Protocol interface through which selected platform capabilities may be exposed. |
  | **ClickHouse Adapter** | Implementation of the Analytics Storage Contract using ClickHouse. |

---

# Appendix B — Terminology Convention

The following convention is normative:

| Usage | Convention | Example |
  |---|---|---|
  | General concept | lowercase prose | analytical facts |
  | Formal schema/type | PascalCase code | `AnalyticalFact` |
  | Logical table | snake_case code | `analytical_facts` |
  | API field | snake_case code | `tenant_id` |
  | Component | Proper noun | Resolutor, Equalix |
  | Security class | uppercase | `RESTRICTED` |
  | Representation | Title Case in prose / lowercase enum | Masked-only / `masked` |
  | Metric/report identity | code | `metric_id`, `report_id` |

This convention prevents the previous ambiguity between a conceptual analytical fact and the formal `AnalyticalFact` schema.

---

# Appendix C — Normative Security Inheritance

The following Design 1.23 controls are inherited without relaxation:

1. Resource ACLs remain mandatory.
2. `class_grants` remains a separate authorization axis.
3. Classification is carried at chunk level where applicable.
4. Classification detection occurs before downstream publication.
5. Representation selection occurs before security-sensitive search statistics and candidate generation.
6. Masked-only content never persists the original.
7. Dual representations are explicitly representation-aware.
8. Original classified vectors are isolated from masked vectors.
9. Graph entities and edges preserve classification/representation metadata.
10. Query-side sanitization remains mandatory.
11. Security-sensitive caches carry sufficient authorization scope.
12. Classification policy changes trigger remediation/reindexing where required.
13. Security failures fail closed.
14. Negative security tests remain part of CI.

---

# Appendix D — Required Design Decisions Before Production

The following values intentionally remain workload- or deployment-dependent:

- expected peak analytics event rate;
  - sustained ingestion target;
  - ClickHouse production topology;
  - replication factor;
  - retention by fact class;
  - late-event window beyond the initial 24-hour default;
  - dashboard concurrency target;
  - report-specific freshness;
  - aggregate policy per data classification;
  - system-wide metric authorization roles.

These must be resolved through workload modelling, security review and operational validation rather than guessed in the architecture document.

---

# Appendix E — Quick Reference by Audience

| Audience | Primary Sections |
  |---|---|
  | Architects | 1–4, 14–21, 22–29, 37–40, 48–58, 89, 94–95 |
  | Security Engineers | 2.1, 19–29, 44–47, 54–56, 79–81, Appendix C |
  | Data Engineers | 22–41, 59–65, 67–77, 91 |
  | SRE / Operations | 56–65, 81–86, 90–93 |
  | Platform Engineers | 42–56, 62, 77, 90 |
  | Developers | 42–44, 66, 71–77, 84–85 |
  | Technical Decision Makers | 1–4, 89, 93–95 |
  | Analytics / BI Engineers | 22–45, 66–77 |
  | Governance / Compliance | 2.1, 28–29, 54–56, 70–73, 81, Appendix C |

---

# Appendix F — Implementation Readiness

| Area | Status | Notes |
  |---|---|---|
  | Architecture | Ready | Major components and boundaries defined |
  | Design 1.23 security inheritance | Ready | Explicit normative relationship added |
  | Annotation model | Ready | Identity, definitions, versions and provenance defined |
  | Dependency model | Ready | DAG and impact analysis defined |
  | Recalculation | Ready | Resolutor/Equalix responsibilities defined |
  | Analytics model | Ready | Events, facts, aggregates, metrics and reports defined |
  | Tenant isolation | Ready | Tenant and system scopes explicitly separated |
  | Aggregate protection | Ready | Policy model and example added |
  | Storage abstraction | Ready | ClickHouse isolated behind contract |
  | Performance | Conditional | Workload-derived ingestion target must be filled |
  | Operations | Ready for PoC | Production topology remains workload-dependent |
  | Security testing | Ready | Analytics security tier extends `test:security` |
  | Governance | Ready | Registry and lifecycle defined |
  | Replay/rebuild | Ready | Durable event boundary defined |
  | Production adoption | Conditional | Requires PoC, load testing, security validation and runbook |

---

# Appendix G — Design Review Resolution

The following review findings are incorporated:

| Review Finding | Resolution |
  |---|---|
  | Missing explicit Design 1.23 cross-reference | Added §2.1 with normative relationship and repository path |
  | Missing unified glossary | Added Appendix A |
  | Missing document location | Added §2.3 |
  | Aggregate side-channel example | Added concrete YAML policy in §28 |
  | Platform-wide analytics scope | Added reserved `system` scope in §46 |
  | Security reclassification guidance | Added decision guidance in §54 |
  | ClickHouse ingestion target | Added peak + 50% headroom target in §91 |
  | Terminology inconsistency | Standardized in §25, §31, §32 and Appendix B |
  | Security inheritance ambiguity | Added normative Appendix C |
  | Analytics replay boundary | Explicitly defined in §§40 and 77 |
  | Storage/architecture coupling | Explicitly prohibited in §§38–39 |
  | MCP bypass risk | Explicitly prohibited in §§43–44 |
  | Historical/current security semantics | Explicitly separated in §54 |

---

# Appendix H — Normative Source References

1. **Synanton Design 1.23 — Classification-Aware Search and Security Model**

   `docs/architecture/synanton-design-1.23.md`
   `https://github.com/synanton/platform/blob/main/docs/architecture/synanton-design-1.23.md`

2. **Synanton Design 1.25 — this consolidated document**

   `docs/architecture/synanton-design-1.25.md`

The 1.23 security and representation model is normative. This 1.24/1.25 document is the consolidated architectural extension for annotation, derived knowledge, recalculation, analytics and reporting.
