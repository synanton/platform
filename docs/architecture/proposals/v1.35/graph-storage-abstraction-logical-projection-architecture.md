# Synanton Design 1.35 Proposal: Graph Storage Abstraction and Logical Projection Architecture

**Status:** Integrated into Design 1.25 §§15/18 by reference — 2026-11-09
**Normative authority:** The detailed graph contract, capability model, and adapter conformance requirements in this document remain authoritative; Design 1.25 owns the knowledge-projection lifecycle boundary and references this document rather than duplicating it.
**Proposal Date:** 2026-11-09
**Target design:** Synanton Design 1.35
**Architectural owner:** Knowledge Processing / Graph
**Related designs:** 1.0 Platform Architecture; 1.25 Knowledge and Derived Projections; 1.27 Eventing and Workflow; 1.31 Search and Retrieval; 1.34 Temporal Versioned Knowledge and Retrieval
**Related decisions:** ADR-021 Pluggable Search Engine Boundary; ADR-022 Pluggable Knowledge Extraction Pipeline
**Related implementation work:** [#587 graph storage contracts and adapter conformance](https://github.com/synanton/platform/issues/587); #578 crash reconciliation; #584 resolution retraction/supersession policy

---

## 1. Executive Summary

Synanton's graph plane must support canonical knowledge, source context, provenance, and domain-specific projections without making a particular graph engine part of the domain model.

This proposal establishes a backend-independent graph contract layer. Neo4j remains the default and currently implemented graph engine, but it is an adapter behind those contracts—not an architectural dependency. The same logical graph operations must be implementable by another graph engine or by a composite adapter routing operations to multiple graph projections.

The design distinguishes three identities that must never be conflated:

- **Graph identity:** the logical graph or knowledge scope, such as `KNOWLEDGE`.
- **Projection identity:** a particular materialization of a logical graph, including its projection version/generation.
- **Storage identity:** the physical backend and location that stores a projection.

Logical scopes travel with graph operations. Adapters map those scopes and projection identities to physical storage. Domain services and workflow consumers do not address Neo4j databases, labels, relationship types, or Cypher.

This is an architecture proposal, not a claim that the full contract layer or composite graph implementation already exists. Existing Neo4j behavior remains the working implementation while the boundary is introduced incrementally.

## 2. Goals

1. Define backend-independent contracts for graph reads, writes, resolution, promotion, scope selection, and transaction capability reporting.
2. Make logical graph identity explicit and independent of physical storage topology.
3. Support multiple projections and logical scopes, including `CONTEXT`, `KNOWLEDGE`, `PROVENANCE`, and `DOMAIN`.
4. Preserve tenant isolation, authorization, deterministic identity, idempotency, and auditability across adapters.
5. Express backend transaction and consistency guarantees honestly through declared capabilities.
6. Define composite projection behavior as independently committed, partially successful, and recoverable work—not as an atomic multi-store transaction.
7. Make contract conformance testable independently of Neo4j, with an in-memory test adapter and the Neo4j adapter running the same applicable contract suite.
8. Keep existing graph behavior and the current Neo4j deployment stable during extraction.

## 3. Non-Goals

- Replacing Neo4j now or selecting a second production graph engine.
- Implementing every logical graph scope in the first delivery.
- Rewriting the existing Relix persistence implementation as part of this design approval.
- Introducing a distributed transaction coordinator or claiming atomicity across Cassandra and Neo4j.
- Requiring all graph engines to provide identical transaction, isolation, indexing, or consistency semantics.
- Moving durable workflow ownership from Cassandra into a graph backend.
- Redefining resolution policy, relation validation, ontology semantics, or retraction/supersession policy.
- Expanding #578 crash reconciliation into composite-graph orchestration.
- Exposing physical graph topology through the public Platform API.

## 4. Ownership and System Boundaries

The architecture has distinct authorities:

| Concern | Authority | Responsibility |
|---|---|---|
| Durable workflow state | Cassandra / Ingestion Cache | Work claims, owners, leases, attempt fences, retry scheduling, terminal outcomes, promotion status |
| Canonical graph state | Graph storage plane | Persisted graph entities, resolutions, canonical relations, evidence and graph projection state |
| Graph semantics and validation | Knowledge domain services, including Syntology | Ontology constraints, relation validation, domain decisions |
| Cross-store coordination | Consumers and reconciliation workflows | Execute authorized work, retry idempotently, detect and repair interrupted operations |
| Physical graph execution | Graph adapter | Translate logical operations into backend-specific queries, constraints and transactions |

Cassandra and graph storage do not share a transaction. A workflow can commit in one store and fail before committing in the other. Such outcomes must be retryable, reconcilable, or surfaced as contradictions.

The graph adapter does not become the owner of consumer leases or workflow retry state. Conversely, Cassandra workflow rows do not become a substitute for authoritative graph reads.

## 5. Identity Model

### 5.1 Graph identity

A graph identity names a logical knowledge scope and is independent of the engine or deployment. Initial scopes are:

- `CONTEXT` — source-local, task-local, session-local, or otherwise bounded contextual relationships.
- `KNOWLEDGE` — canonical entities and validated semantic relationships.
- `PROVENANCE` — extraction, evidence, derivation, and lineage relationships.
- `DOMAIN` — domain-specific graph projections governed by an explicit domain contract.

These are logical names, not Neo4j database names, labels, or relationship types. A scope does not imply a separate physical database.

### 5.2 Projection identity

A projection identity identifies a particular materialization of a logical graph. It includes enough information to distinguish independently rebuilt or versioned projections, for example:

- logical graph identity;
- tenant boundary;
- projection name or role where a scope has multiple projections;
- projection schema/contract version;
- generation or immutable projection revision.

Projection identity is the stable reference used for lifecycle, rebuild, observability, and reconciliation. A generation change must not silently reinterpret the identity of an earlier materialization.

### 5.3 Storage identity

A storage identity identifies the physical implementation and location selected by deployment configuration: engine/provider, cluster or service, database/namespace, and provider-specific routing data.

Storage identity is adapter/deployment metadata. It is not a domain identifier and must not leak into graph operations, persisted domain decisions, or external API contracts.

### 5.4 Required distinction

Graph identity, projection identity, and storage identity are separate dimensions. One logical graph may have multiple projections; one projection may be moved or rebuilt in another physical store; one composite adapter may route different projections to different stores. None of these changes should require changing domain semantics.

## 6. Contract Layer

Graph-consuming services depend on domain-oriented ports. The proposed contract surface is:

| Contract | Responsibility |
|---|---|
| `GraphReadPort` | Tenant-authorized reads of graph objects, relationships, evidence, and projection state |
| `GraphWritePort` | Deterministic, idempotent graph mutations and deletion/tombstone operations where authorized |
| `GraphResolutionPort` | Persist, inspect, and supersede resolution decisions with explicit fencing and audit semantics |
| `GraphPromotionPort` | Promote validated relation assertions into canonical graph relations and evidence |
| `GraphScope` | Carry logical graph identity and projection selection as a typed value, not a physical storage identifier |
| `GraphTransactionPort` | Describe and expose transaction-scoped execution only where supported, together with the backend's declared guarantees |

The table describes responsibilities, not a requirement that every method live in one Java interface or module. The implementation should keep ports cohesive and avoid a single generic graph repository that combines unrelated lifecycle and semantic responsibilities.

### 6.1 Domain-neutral contract types

Contract inputs and outputs use domain-owned identifiers and value objects. They must not expose provider sessions, driver records, Cypher fragments, database handles, native graph paths, or engine-specific exception types.

Provider-specific query languages and transaction APIs remain inside adapters. The domain contract should express intent (for example, resolve a mention to an entity, or promote a validated assertion) rather than allowing callers to submit arbitrary provider queries.

### 6.2 Scope propagation

Every graph operation carries an explicit logical scope and projection identity (or a typed selection policy that resolves to one). The adapter resolves that selection against deployment configuration. A caller cannot use a caller-supplied physical identifier to bypass tenant or scope policy.

Where a workflow spans multiple projections, each projection operation is explicit and independently observable. A generic write must not silently fan out to every projection.

## 7. Backend Capability Declarations

Port compatibility does not mean capability equivalence. Each adapter declares the guarantees it can actually provide. Capability declarations are versioned, inspectable, and backed by conformance tests before they are enabled.

At minimum, capabilities cover:

| Capability | Question the adapter must answer |
|---|---|
| Atomicity scope | Which mutations are atomic together: one object, one graph operation, one projection, or a larger backend-defined transaction? |
| Conditional writes / fencing | Can writes be conditioned on an expected version, owner, or monotonic fencing token? What is the exact scope of enforcement? |
| Tenant-scoped uniqueness | Can uniqueness be enforced across the tenant and logical projection boundary, including concurrent writers? |
| Read-after-write consistency | What visibility guarantee follows a successful write, and under which read route/replica conditions? |
| Projection rebuild | Can one projection be rebuilt and activated independently while readers continue using the prior generation? |
| Idempotent mutation support | Which deterministic keys and uniqueness primitives are required to make retries safe? |
| Transaction support | Which operations can share a transaction, and what isolation/rollback guarantees apply? |

A capability declaration is made per adapter and per logical scope/projection, not as a flat adapter-wide boolean set. The same physical backend may provide different guarantees for different projections or operation groups. Each declaration identifies its storage identity, logical scope, projection identity/version, operation class, guarantee, and any relevant limits. For example, an adapter may provide atomic resolution-plus-evidence writes within the `KNOWLEDGE` projection while providing no atomicity spanning `KNOWLEDGE` and `PROVENANCE`.

A capability may be unsupported, limited, or provider-specific. The adapter must not claim a stronger guarantee than the underlying engine and deployment configuration provide. Consumers and workflows must select a safe strategy based on required capabilities or fail closed when a required guarantee is absent. Capability declarations are validated during dependency wiring/startup: if a configured workflow requires a guarantee that its selected adapter/scope does not advertise, startup or activation fails with an explicit configuration error before work is accepted. Runtime checks remain defensive, but are not the primary discovery point for an unsupported guarantee.

For a composite adapter, the effective capability for an operation is the lower bound (intersection) of the guarantees of every constituent projection participating in that operation. A composite may narrow a constituent's advertised guarantee to reflect routing or coordination overhead; it must never advertise the union of capabilities across constituents or a stronger atomicity/consistency guarantee than all required constituents jointly provide.

### 7.1 GraphTransactionPort

`GraphTransactionPort` is bound to exactly one physical storage identity. It describes that storage implementation's transaction boundary and per-guarantee semantics; it is not a coordinator for multiple storage identities or logical projections. The contract reports guarantees independently rather than exposing a monolithic `supportsTransactions` flag. At minimum, the declaration distinguishes:

- atomicity for named operation groups within one projection (for example, resolution plus evidence);
- conditional writes and fencing-token enforcement, including the scope over which the fence is checked;
- read-after-write visibility and its applicable read path/consistency conditions;
- rollback behavior for the declared transaction boundary.

A capability declaration may say, for example, that a particular storage adapter atomically writes a canonical relation and its evidence within the `KNOWLEDGE` projection, while another operation spanning `KNOWLEDGE` and `PROVENANCE` is not atomic. The API must not accept a transaction scope containing multiple storage identities or projections. Cross-projection coordination belongs to a workflow with explicit per-projection outcomes and reconciliation, not to `GraphTransactionPort`.

Transactions across graph projections, graph stores, or Cassandra are never implied by this contract.

## 8. Tenant Isolation and Authorization

Tenant isolation is invariant across all graph implementations.

1. Tenant identity comes from validated security context, not from an untrusted body field or a physical storage name.
2. Every read, write, uniqueness key, deterministic identifier namespace, and reconciliation lookup is tenant-scoped.
3. An adapter must enforce tenant isolation at the storage boundary as well as through service authorization; application filtering alone is insufficient where the backend can enforce stronger isolation.
4. Cross-tenant references fail closed and must not reveal whether another tenant's graph object exists.
5. Composite adapters propagate verified tenant context to every constituent projection and must fail closed if any required projection cannot establish the tenant boundary.
6. Capability claims for tenant-scoped uniqueness and isolation require concurrent and negative conformance tests.

Logical graph scope is not a security boundary by itself. Tenant authorization remains mandatory for every scope.

## 9. Deterministic Identity and Idempotency

Stable identity and retry semantics are part of the domain contract, not incidental properties of a particular database query.

- Mutations use deterministic domain identifiers derived from the documented tenant, source/version, assertion, evidence, and logical scope dimensions applicable to the operation.
- The identity formula and its version are explicit and stable; changing an identity formula requires a migration/versioning plan.
- Repeating the same authorized operation must not create duplicate logical objects, relations, or evidence.
- Reusing an identifier with contradictory semantic content must be detected and surfaced, not silently overwritten.
- Idempotency must hold under concurrent execution, not only sequential replay.
- Supersession and retraction preserve the audit history required by the relevant domain contract.
- Provider-specific uniqueness constraints, anchors, mutex nodes, or compare-and-set statements are implementation mechanisms for satisfying these domain guarantees.

The contract defines the observable result of a replay (created, already present/matched, superseded, stale, or conflict) without requiring every adapter to use the same physical mechanism.

## 10. Transaction and Consistency Boundaries

The design recognizes three separate boundaries:

1. **Within one graph projection:** an adapter may provide an atomic transaction for a declared set of graph mutations, subject to its capability declaration.
2. **Across projections:** no atomicity is assumed. Each projection can succeed, fail, or lag independently.
3. **Across Cassandra and graph storage:** no shared transaction exists. Durable workflow coordination and graph effects are connected through idempotent consumers and reconciliation.

A successful operation must identify what was committed and at which projection identity/generation. Partial completion is an explicit outcome, not a success-shaped response.

The design does not require synchronous consistency across all projections. It requires the consistency model, visibility delay, and recovery behavior to be explicit for each operation.

## 11. Composite Graph Projections

A composite adapter may route logical graph operations to multiple physical graph stores or projection implementations. It is a routing and coordination boundary, not a distributed transaction manager.

Examples include:

- a context graph optimized for short-lived or source-local traversal;
- a knowledge graph optimized for canonical entity and relation access;
- a provenance graph optimized for evidence and derivation history;
- domain projections optimized for a particular ontology or workload.

A composite adapter must declare:

1. which logical scopes and projection identities it owns;
2. the routing rule for each operation;
3. whether an operation affects one or multiple projections;
4. the independent commit and visibility semantics of each projection;
5. the partial-success result returned to the caller;
6. the durable operation metadata needed to retry or reconcile each affected projection;
7. how a projection is rebuilt, activated, retired, and audited independently.

A fan-out write is not reported as globally complete merely because one target succeeded. The result identifies each projection's outcome. Failure of one projection does not cause an unsafe rollback of another projection unless that rollback is itself an explicitly supported and safe operation. Composite projection operations are workflows, not transactions: each target has its own status, commit/visibility result, retry identity, and reconciliation path. A composite adapter's effective capabilities are the lower bound/intersection of the capabilities of all constituents required for the operation; it must not advertise a union of capabilities or imply that one constituent's stronger guarantee covers another constituent.

Cross-projection workflows use deterministic operation IDs and independently retryable projection effects. Reconciliation compares desired logical state with each projection's observed state. It does not assume a shared commit point.

## 12. Reconciliation and Recovery

Reconciliation is state-driven, idempotent, and expressed through logical graph contracts. It does not depend on provider-specific recovery queries outside the adapter.

Persisted operation metadata must be sufficient to identify the authorized mutation and its target projection, including as applicable:

- tenant and logical graph scope;
- projection identity and schema/generation;
- deterministic resolution, relation, and evidence identifiers;
- source version and originating assertion;
- workflow attempt fence and operation identity;
- expected entity/endpoint identity;
- intended operation and contract version.

The workflow store remains authoritative for claims, leases, attempt fences, retry scheduling, and terminal workflow outcomes. A graph adapter reports observed graph state and enforces graph-side conditional writes where supported.

Recovery must distinguish:
- an interrupted authorized operation that can safely be retried;
- an already committed graph effect whose workflow completion was lost;
- a stale operation superseded by a higher fence or newer authorized decision;
- contradictory state that cannot be repaired automatically.

A healthy in-flight lease must not be stolen by reconciliation. Workflow repairs use the workflow store's owner/lease conditional-write mechanism. Graph writes are retried only when the persisted authorization, deterministic IDs, endpoint identities, and fencing conditions still match.

Reconciliation does not redefine resolution policy. In particular, a newer terminal `UNRESOLVED` or `AMBIGUOUS` decision alongside an older active resolution edge is a decision-policy/retraction concern, not a crash-recovery instruction. Such stale edges are detected and reported; reconciliation does not mutate them without an explicit retraction policy.

### 12.1 Current two-store workflow

For the current implementation, Cassandra owns durable workflow state and Neo4j owns canonical graph state. Resolution and promotion consumers coordinate those stores through independently retryable operations. #578 remains scoped to crash reconciliation for this two-store workflow and must not introduce composite-graph fan-out or new Neo4j-specific dependencies into domain consumers.

## 13. Neo4j Reference Implementation

Neo4j remains the default and currently implemented graph engine. Its adapter is the reference implementation for the first contract extraction.

The following are Neo4j implementation mechanisms and must remain behind the adapter boundary:

- Cypher and Neo4j driver/session/transaction types;
- physical database names and provider-specific routing;
- labels and relationship types;
- property constraints and indexes;
- `ResolutionSlot` uniqueness/mutex nodes;
- `CanonicalRelationAnchor` nodes used to enforce canonical relation uniqueness;
- provider-specific transaction statements and locking patterns.

Existing Neo4j behavior—including tenant-scoped resolution, fencing, canonical relation promotion, evidence persistence, and concurrent uniqueness protections—must remain unchanged during the initial extraction. The proposal does not authorize a graph schema migration or a backend replacement.

Domain services may depend on logical graph operations and declared capabilities. They must not construct Cypher or branch on Neo4j labels/relationship types.

## 14. In-Memory Adapter and Contract Conformance

A second in-memory adapter is required as a test implementation, not as a production graph engine. It provides a fast, deterministic environment for exercising domain contract behavior without requiring Neo4j.

The contract test suite must be reusable by every adapter and cover applicable guarantees, including:

- tenant isolation and cross-tenant rejection;
- logical scope and projection identity routing;
- deterministic identity and sequential idempotent replay;
- concurrent duplicate writes and uniqueness behavior;
- resolution fencing and stale-attempt rejection;
- promotion of validated assertions and evidence matching;
- read-after-write behavior as declared;
- transaction rollback/atomicity within the declared scope;
- projection rebuild/activation behavior where supported;
- explicit partial outcomes and retry behavior for composite projections;
- capability declaration validation and unsupported-capability failure;
- positive conformance: every advertised capability is exercised and the adapter must demonstrate the promised behavior;
- negative conformance: an adapter that advertises fencing but accepts a stale fence must fail the suite; an adapter that does not advertise fencing must reject a fencing-dependent operation explicitly;
- composite lower-bound conformance: a composite that advertises stronger atomicity or consistency than any required constituent can jointly provide must fail the suite; cross-projection transaction requests must be rejected rather than silently degraded.

Not every adapter must pass tests for capabilities it does not claim. It must, however, reject unsupported operations explicitly and pass all mandatory safety tests. An adapter that advertises a capability and fails its positive behavior test is non-conformant; an adapter that does not advertise a capability and silently emulates, ignores, or weakens a request is also non-conformant. Adapter-specific integration tests remain necessary for provider behavior, failure injection, transaction rollback, and deployment configuration.

The Neo4j adapter must run the same applicable contract suite. The in-memory adapter supplements, but does not replace, real Neo4j/Testcontainers tests.

## 15. Migration and Implementation Roadmap

Implementation is incremental and behavior-preserving.

### Phase 0 — Architecture approval

- Review and approve this proposal.
- Record the decision in ADR-023.
- Integrate the approved graph architecture into Design 1.25 and the architecture index/version metadata.

### Phase 1 — Contract inventory and extraction

- Inventory current Relix graph ports, graph operations, identifiers, and Neo4j-specific assumptions.
- Define cohesive domain contract types and capability declarations.
- Keep the existing Neo4j adapter as the only production implementation.
- Preserve current behavior and public service/API semantics.

### Phase 2 — Contract conformance

- Implement the in-memory test adapter.
- Extract reusable contract tests.
- Run the applicable suite against both in-memory and Neo4j adapters.
- Add capability tests and negative tests for unsupported guarantees.

### Phase 3 — Consumer and recovery alignment

- Ensure resolution and promotion consumers depend only on graph contracts.
- Ensure #578 reconciliation uses logical graph operations and declared capabilities.
- Keep workflow claims, leases, and attempt fences in Cassandra.
- Keep consumer activation independently controlled and gated by tested reconciliation.

### Phase 4 — Logical projection support

- Introduce logical scope and projection identity into persisted operation metadata.
- Map logical scopes to physical storage through adapter configuration.
- Support independent rebuild and lifecycle of projections where the backend declares the capability.

### Phase 5 — Composite projections

- Implement composite routing only when at least two real projections require it.
- Define per-projection outcomes, durable operation records, retries, and reconciliation.
- Demonstrate partial failure and independent recovery under process restart and lost responses.
- Do not introduce cross-projection transaction claims.

### Separate domain-policy work

Resolution retraction and supersession semantics, including audit history and concurrent promotion interaction, remain a separate domain-policy concern tracked by #584. They must not be smuggled into crash reconciliation.

## 16. Acceptance Criteria

The design is ready for implementation when all of the following are satisfied:

1. Neo4j remains the default working implementation behind a graph contract boundary.
2. Domain consumers do not depend on Cypher, Neo4j driver types, physical database names, labels, or relationship types.
3. Graph identity, projection identity, and storage identity are distinct typed concepts.
4. Operations carry explicit logical scope and projection identity/version.
5. Backend capability declarations cover atomicity scope, conditional writes/fencing, tenant-scoped uniqueness, read-after-write consistency, idempotency support, and independent projection rebuild.
6. Capability claims are backed by adapter conformance tests.
7. Tenant isolation and authorization are enforced for every adapter and every logical scope.
8. Deterministic identifiers and idempotency semantics are defined at the contract/domain boundary.
9. The in-memory test adapter implements the same contracts and passes the mandatory conformance suite.
10. The Neo4j adapter passes the same applicable conformance suite and retains its real integration tests.
11. Composite projections expose per-projection outcomes and explicit partial-failure/reconciliation semantics.
12. No contract implies atomicity across graph projections or between Cassandra and graph storage.
13. Reconciliation uses logical contracts, respects workflow ownership/leases/fences, and does not redefine decision policy.
14. Existing behavior remains unchanged during the initial adapter extraction.

## 17. Risks and Design Controls

| Risk | Control |
|---|---|
| A generic port hides provider differences | Capability declarations and explicit unsupported-operation behavior |
| Lowest-common-denominator transactions weaken useful guarantees | Transaction scope is described per adapter; domain operations state required guarantees |
| Neo4j concepts leak through DTOs or exceptions | Contract type review and package/dependency boundary tests |
| Composite fan-out is mistaken for atomicity | Per-projection outcomes, durable operation identity, independent reconciliation |
| Tenant isolation is implemented only in service filters | Adapter-level tenant enforcement and negative/concurrency conformance tests |
| In-memory tests create false confidence | Same contract suite plus real Neo4j/Testcontainers integration and failure-injection tests |
| Projection rebuild changes identity unexpectedly | Explicit projection generation/version and lifecycle contract |
| Reconciliation changes domain decisions | Crash recovery and retraction/supersession remain separate responsibilities |

## 18. Open Questions for Architecture Review

1. Should `GraphScope` be a closed enum for the initial release with a governed extension mechanism, or an extensible registered identifier? The initial scopes remain the four listed in §5.1 either way.
2. Should projection schema version and generation be separate fields in the projection identity, or should generation reference an immutable manifest containing schema version and source watermark?
3. Which graph reads require read-after-write consistency, and which may use bounded-staleness projections?
4. Which current Relix operations require an atomic transaction as a domain invariant, rather than as a convenience of the Neo4j implementation?
5. Should composite routing be a separate adapter module or a decorator around the base graph ports? This is an implementation decision after the routing contract is approved.

---

## 19. Proposed Architectural Invariants

1. Neo4j is a default implementation, never the graph domain contract.
2. Domain services address logical graph scopes and projection identities, not physical storage identities.
3. Graph identity, projection identity, and storage identity are distinct.
4. Every graph operation is tenant-authorized and tenant-scoped.
5. Deterministic identity and idempotency are domain contract guarantees.
6. Backend capabilities are explicit, versioned, and tested.
7. Transaction guarantees are scoped to the adapter and projection that provide them.
8. No atomicity is implied across graph projections or across Cassandra and graph storage.
9. Composite projection writes expose partial outcomes and are independently recoverable.
10. Workflow ownership, claims, leases, and attempt fences remain in the workflow store.
11. Reconciliation repairs interrupted authorized operations; it does not invent or redefine domain decisions.
12. Provider-specific query languages, schemas, constraints, and transaction mechanisms remain inside adapters.
13. The in-memory adapter is a conformance/testing implementation, not evidence of production backend portability by itself.
14. The Neo4j reference adapter must pass the same applicable contract suite as other adapters.

## 20. Approval

**Approved 2026-11-09.** Design 1.35 is approved as the normative architecture for backend-independent graph contracts and logical graph projections, with Neo4j retained as the default implementation. The capability model is scoped per adapter and logical projection; `GraphTransactionPort` is bound to one storage identity; composite projection operations are workflows with explicit partial outcomes and lower-bound capability declarations. Following this approval, integrate the relevant knowledge-projection changes into Design 1.25 and update the architecture index and version metadata. Implementation remains incremental and behavior-preserving; no composite graph rollout or Neo4j replacement is authorized by this approval alone.
