# ADR-023: Graph Storage Abstraction and Logical Projection Contracts

**Status:** Accepted 2026-11-09
**Implementation work:** [#587 — Implement graph storage contracts and adapter conformance](https://github.com/synanton/platform/issues/587)
**Basis:** Synanton's graph pipeline now persists extracted mentions, resolutions, canonical relations, and evidence through Relix/Neo4j. Resolution and promotion consumers, plus the planned Cassandra–graph reconciliation workflow, increase the cost of allowing Neo4j-specific assumptions to spread into domain services. The platform already establishes storage-independent adapter boundaries for search (ADR-021) and extraction (ADR-022). Graph storage needs the same explicit boundary before additional consumers and logical projections are built.

## Decision

1. **Backend-independent graph contracts.** Synanton defines graph operations through domain-owned contracts. The contract layer covers graph reads, writes, resolution, promotion, logical scope selection, and declared transaction capabilities. Domain services and consumers depend on these contracts rather than a graph engine's driver, query language, schema, or physical topology.

2. **Neo4j remains the default implementation.** Neo4j is the currently implemented and default graph engine. It is an adapter, not an architectural dependency. Cypher, driver/session/transaction types, physical database names, labels, relationship types, provider constraints, and provider-specific locking mechanisms remain inside the Neo4j adapter.

3. **Logical graph and projection identity.** Graph operations carry logical graph scope and projection identity/version. Initial logical scopes are `CONTEXT`, `KNOWLEDGE`, `PROVENANCE`, and `DOMAIN`. Graph identity, projection identity, and physical storage identity are distinct; adapters map logical identities to deployment-specific storage.

4. **Explicit capability declarations.** Capabilities are declared per adapter and per logical scope/projection, not as flat adapter-wide booleans. Declarations identify storage identity, scope, projection/version, operation class, guarantee, and limits. Capability claims require positive conformance evidence; unsupported capabilities require explicit rejection and negative conformance. Required capabilities are validated during wiring/startup, and incompatible workflows fail visibly before accepting work. A composite adapter advertises only the lower-bound/intersection of the capabilities of all constituents required for an operation, never their union.

5. **Tenant isolation and deterministic semantics.** Tenant authorization and isolation apply across every adapter and logical scope. Deterministic identifiers, idempotency, stale-write handling, and observable replay outcomes are domain contract guarantees; provider-specific constraints and transactions are implementation mechanisms.

6. **Composite projections are independently recoverable.** A composite adapter may route operations to multiple graph projections, but it is not a distributed transaction manager. Each projection has explicit commit/visibility status, partial outcomes, durable operation identity, retry, and reconciliation semantics. Composite operations are workflows, not transactions; effective capabilities are the lower bound of required constituents. No atomicity is implied across projections or between Cassandra and graph storage.

7. **Workflow ownership remains separate.** Cassandra remains authoritative for workflow claims, leases, attempt fences, retry scheduling, and terminal outcomes. Graph storage remains authoritative for persisted graph state. Consumers and reconciliation coordinate independently committed stores through idempotent, fenced operations.

8. **Single-storage transaction boundary.** `GraphTransactionPort` is bound to one storage identity and reports atomicity, fencing/conditional-write, read-after-write, and rollback guarantees separately. It cannot coordinate across projections or storage identities; such coordination is represented as an independently recoverable workflow.

9. **Behavior-preserving adoption.** The existing Neo4j implementation remains the production path during contract extraction. A second in-memory adapter is introduced for contract testing; the same applicable conformance suite runs against the in-memory and Neo4j adapters. This ADR does not authorize a Neo4j replacement, composite graph rollout, or premature Relix rewrite.

Consequences:
- **Portability:** a future graph engine can implement the domain contracts without changing graph-consuming services, provided it satisfies the required capabilities.
- **Multiple projections:** logical scopes can be mapped independently to physical stores and rebuilt/versioned independently.
- **Honest guarantees:** callers can choose safe workflows based on declared capabilities; unsupported guarantees are explicit rather than silently emulated.
- **Testing:** graph adapter conformance becomes a required safety boundary, supplemented by real provider integration and failure-injection tests.
- **Recovery:** reconciliation operates through logical graph contracts and persisted operation metadata; it does not depend on Cypher outside the Neo4j adapter.
- **Operational complexity:** composite projections require durable per-projection outcomes and recovery. They are introduced only when needed, not as part of the initial extraction.
- **No semantic rewrite:** resolution policy and retraction/supersession remain domain concerns. In particular, #584 owns the policy for retracting or superseding older active resolutions; #578 remains crash reconciliation and must not mutate stale active edges merely because a newer non-RESOLVED decision exists.
- **Rejected:** treating Neo4j labels, relationship types, database names, or Cypher as public graph contracts.
- **Rejected:** a lowest-common-denominator transaction API that hides meaningful backend differences.
- **Rejected:** pretending composite graph writes or Cassandra–graph operations share a transaction.

## Context

Synanton's current RAKG path uses Neo4j for persisted graph state while Cassandra stores durable workflow state. Resolution and promotion are independently retryable, fenced operations. The two stores do not share a transaction; partial outcomes require explicit reconciliation.

The graph model is also expected to grow beyond one canonical knowledge graph. Context, knowledge, provenance, and domain-specific projections may have different lifecycles, workloads, and physical placement. A single Neo4j deployment can host these projections initially, but its labels, relationship types, and database topology must not become the logical model.

The platform already uses adapter boundaries to isolate Lucene in search (ADR-021) and regex/vLLM extraction engines (ADR-022). Applying the same principle to graph storage preserves the existing Neo4j implementation while making backend guarantees and future composite projection behavior explicit.

The detailed normative proposal is [Synanton Design 1.35: Graph Storage Abstraction and Logical Projection Architecture](../proposals/v1.35/graph-storage-abstraction-logical-projection-architecture.md). This decision is accepted on 2026-11-09. Integration into Design 1.25 and the architecture index/version metadata is the next documentation-governance step; this ADR does not itself authorize a runtime migration or composite graph rollout.
