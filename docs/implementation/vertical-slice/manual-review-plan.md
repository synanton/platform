# Platform Codebase Review Plan

**Status:** Proposed review procedure  
**Repository:** synanton/platform  
**Scope:** Repository, modules, APIs/contracts, tests, runtime/deployment, documentation and developer instructions  
**Method:** Manual, step-by-step, evidence-driven review  
**Policy:** Review first; implement fixes separately; do not merge automatically.

## 1. Purpose and principles

The purpose is to establish what is implemented, how modules and contracts fit together, whether behavior matches the applicable design, and whether another developer can build, test, operate, and diagnose the system.

For every reviewed capability, answer:

1. What code exists and is actually built?
2. Which module owns the responsibility and state?
3. Which API or internal contract connects its producers and consumers?
4. Does behavior match the applicable design and documented scope?
5. Do tests prove production-shaped behavior, including failure paths?
6. Can another developer reproduce and diagnose it?
7. Are remaining gaps bounded, owned, and measurable?

Rules:

- Code and executable evidence are the implementation truth. Approved design is not proof of implementation.
- Distinguish proposed, approved design, contract frozen, in progress, implemented, validated with dated evidence, and production-ready.
- Check document status and dates. Do not treat historical plans as current implementation truth.
- Follow callers, callees, adapters, persistence, configuration, tests, and deployment wiring.
- Review negative paths: invalid identity, missing data, duplicate delivery, retries, timeouts, restarts, partial failure, stale projections.
- Every finding must identify a location, evidence, expected and actual behavior, impact, correction, regression test, and acceptance criterion.
- Avoid broad redesign until a concrete boundary or correctness problem is demonstrated.
- Keep review and implementation separate. Fixes should be proposed in separate PRs.

## 2. Step 0 — Repository baseline

### 2.1 Establish the exact baseline

- [ ] Record date, reviewer, branch, commit SHA, and working-tree state.
- [ ] Confirm default branch and branch divergence.
- [ ] Review recent merged and open PRs affecting the vertical slice and modules in scope.
- [ ] Identify open issues linked from implementation plans, TODOs, tests, and gap registers.
- [ ] Identify generated, vendored, archived, experimental, deprecated, and active code.

**Deliverable:** baseline note with commit, PR/issue references, and scope.

### 2.2 Map the repository

Inspect:

- [ ] README.md and project status statements.
- [ ] settings.gradle.kts, root build.gradle.kts, convention plugins, version catalogs, Gradle wrapper.
- [ ] Root configuration, .github workflows, Docker/Compose, Kubernetes manifests, scripts, environment templates.
- [ ] Active directories: java/, docs/, deployments/, tests/, scripts/, and other top-level areas.
- [ ] CI workflows and the exact Gradle tasks they execute.
- [ ] License, contribution guide, CODEOWNERS, security policy, and developer/agent instructions such as CLAUDE.md if present.

Create a repository map:

| Area | Record |
|---|---|
| Build | Gradle/JDK versions, plugins, conventions, build tasks |
| Modules | Gradle path, directory, purpose, dependencies |
| Contracts | REST/OpenAPI, protobuf/gRPC, events, storage/SPI |
| Runtime | Applications, libraries, workers, adapters, deployment units |
| Tests | Unit, integration, acceptance, contract, security, benchmark |
| Delivery | CI, images, manifests, release/version process |
| Docs | Architecture, implementation, API, operations, user guides |

### 2.3 Establish build and CI baseline

- [ ] List Gradle projects and compare with settings.gradle.kts.
- [ ] Run documented unit tests.
- [ ] Run documented acceptance tests with stated prerequisites.
- [ ] Run CI-equivalent build in a clean environment where possible.
- [ ] Identify tests skipped, excluded, conditional on Docker/databases, quarantined, or absent from CI.
- [ ] Compare local instructions with workflow YAML.
- [ ] Record prerequisites and expected environment failures separately from regressions.

For each command record exact command, environment, result, duration, and skipped tests. A partial task is not a repository-wide green build.

**Exit gate:** active repository structure and reproducible build/test baseline are known.

## 3. Step 1 — Module inventory and dependency map

The current settings.gradle.kts lists the following Gradle project paths. Confirm this inventory against the branch under review; it is navigation, not a claim that every module is complete or deployed.

| Review group | Gradle project paths | Focus |
|---|---|---|
| Shared foundation | :java:shared:common, :java:security, :java:topology | Shared types, security primitives, configuration and topology |
| Knowledge/control | :java:syntology, :java:control-plane, :java:annotations | Ontology, validation, control-plane responsibilities, provenance |
| Ingestion/content | :java:ingestion-cache, :java:synvault, :java:synflux, :java:synflux-router, :java:extraction-contract, :java:extraction-client, :java:content-cache-api, :java:content-cache-postgres, :java:content-cache-grpc | Source identity, extraction, chunking, artifact lifecycle |
| Retrieval/graph | :java:synquest, :java:relix | Indexing, query semantics, graph references, freshness and attribution |
| Query/API ingress | :java:planner, :java:gateway, :java:synapt, :java:synanton-mcp, :java:synanton-llm-client | Request flow, orchestration, external boundary and MCP |
| GPU execution | :java:gpu-contract, :java:gpu-client, :java:gpu-gateway | Versioned RPC, isolation, deadlines, model provenance |
| Eventing | :java:eventing-contract, :java:eventing-publisher, :java:eventing-outbox | Envelope, durable publication, retries, replay |
| Storage contracts/adapters | :java:storage-contract, :java:synvault-api, :java:synquest-api, :java:storage-testkit, :java:synvault-inmemory, :java:synquest-inmemory, :java:synvault-cassandra, :java:storage-provider, :java:synvault-ydb, :java:synvault-postgres, :java:synquest-cassandra, :java:synquest-ydb, :java:synquest-postgres, :java:synquest-milvus, :java:synquest-qdrant, :java:synquest-lucene, :java:synvault-migrate | Port/adapter separation, backend parity, migrations and consistency |
| Benchmarks | :java:synanton-bench-convergence, :java:synanton-bench-corpus, :java:synanton-bench-baseline, :java:synanton-bench-emitter | Dataset identity, reproducibility and measurement validity |

### 3.1 Create a module card for every active module

- [ ] Gradle project path and source directory.
- [ ] Type: application, library, contract, adapter, testkit, migration, benchmark, or experimental.
- [ ] Responsibility and explicit non-responsibilities.
- [ ] Public packages/classes and runtime entry points.
- [ ] Direct dependencies and reverse dependents.
- [ ] Owned domain state and persistence tables, collections, or indexes.
- [ ] Inbound/outbound APIs, events, and ports.
- [ ] Configuration keys, defaults, secrets, and feature flags.
- [ ] Runtime/deployment unit, if any.
- [ ] Tests and CI tasks that cover it.
- [ ] Owning design, ADR, implementation plan, issues, and known gaps.
- [ ] Evidence-based implementation status.

### 3.2 Review module boundaries

- [ ] Domain logic does not depend directly on concrete storage, broker, web, or cloud SDKs without justification.
- [ ] Contract modules do not import implementation modules.
- [ ] No cyclic Gradle or package dependencies.
- [ ] Shared/common modules are not dumping grounds.
- [ ] No duplicate ownership of the same entity, identifier, policy, or lifecycle state.
- [ ] Adapters implement equivalent port semantics and error contracts.
- [ ] Module names and packages match actual responsibilities.
- [ ] Identify unused modules, dead code, duplicate implementations, and abandoned migration paths.

**Deliverable:** module inventory, dependency graph, module cards, and boundary findings.

## 4. Step 2 — Module-by-module code review

Review in dependency order: foundation and contracts, persistence, ingestion, knowledge/retrieval, orchestration, external ingress.

### 4.1 Shared foundation: common, security, topology

- [ ] Inspect identifiers/value types, validation, serialization, error types, and time abstractions.
- [ ] Check nullability and invalid-state handling.
- [ ] Verify security context and tenant scope are explicit, immutable, and passed across boundaries.
- [ ] Check for default tenant, ambient system scope, or caller-controlled identity.
- [ ] Inspect configuration binding, validation, defaults, and secret handling.
- [ ] Identify shared utilities that bypass domain contracts or create hidden coupling.

### 4.2 Ingestion and source lifecycle: synvault, synflux, router, extraction clients

- [ ] Trace source registration, stable source identity, source version, acquisition, extraction, chunking, enrichment, and persistence.
- [ ] Distinguish source identity, content digest, artifact ID, and generation.
- [ ] Check content-type routing and unsupported-feature behavior.
- [ ] Verify partial extraction/feature states are explicit, not silently reported as full success.
- [ ] Check provenance, classification, representation, and tenant scope propagation.
- [ ] Inspect idempotency for repeated uploads, re-ingestion, retries, and replay.
- [ ] Test large/malformed files, timeouts, cancellation, and extractor failure.
- [ ] Verify streams, temporary files, and resources are closed and cleaned up.
- [ ] Check delete/replace semantics and downstream invalidation.

### 4.3 Content artifact/cache: ingestion-cache and content-cache modules

- [ ] Identify authoritative artifact contract and generation/version semantics.
- [ ] Verify manifest, chunks, embeddings, and generation pointer remain consistent.
- [ ] Check artifact lookup by ID and generation.
- [ ] Inspect transaction boundaries and atomic publication of completed generations.
- [ ] Test missing, partial, stale, corrupt, and superseded artifacts.
- [ ] Check retention, deletion, garbage collection, and orphan cleanup.
- [ ] Do not equate an ingestion cache with the content artifact contract without evidence.

### 4.4 Knowledge and ontology: syntology, annotations

- [ ] Trace ontology/schema loading, versioning, validation, and update lifecycle.
- [ ] Verify SHACL/equivalent validation errors are deterministic and actionable.
- [ ] Check ontology identity, version, tenant scope, and provenance.
- [ ] Verify annotation definitions, processing runs, dependencies, and derived-state lineage.
- [ ] Ensure failed/partial processing cannot publish apparently valid derived state.
- [ ] Test schema evolution, incompatible versions, missing terms, and invalid references.
- [ ] Determine whether state is canonical or a derived projection; keep ownership unambiguous.

### 4.5 Search and retrieval: synquest and adapters

- [ ] Trace indexing from durable artifact to searchable projection.
- [ ] Distinguish implemented lexical/vector/hybrid paths from planned or disabled behavior.
- [ ] Check index generation, rebuild, refresh, and deletion semantics.
- [ ] Verify tenant, classification, representation, and authorization eligibility before ranking.
- [ ] Confirm result IDs resolve to the deployed corpus and source artifacts.
- [ ] Verify attribution, snippets, citations, scores, and provenance are grounded in returned records.
- [ ] Test empty/malformed query, no results, uncached query, unavailable vector backend, stale index, and partial backend failure.
- [ ] Compare adapter semantics across Lucene, Cassandra, YDB, Postgres, Milvus, Qdrant, and in-memory implementations where supported.
- [ ] Review ranking/evaluation datasets for corpus identity and reproducibility.

### 4.6 Graph: relix

- [ ] Identify node/edge identity and mapping to canonical content/chunks.
- [ ] Verify tenant, classification, representation, and provenance on nodes and edges.
- [ ] Check graph projection build/update/delete and consistency with source corpus.
- [ ] Test missing node, dangling edge, stale reference, duplicate edge, and rebuild.
- [ ] Ensure graph expansion cannot bypass search authorization or expose cross-tenant references.
- [ ] Check traversal bounds, cycles, depth limits, and query cost.
- [ ] Ensure graph results can be traced to source and generation.

### 4.7 Planner, gateway, API ingress, MCP, LLM client

- [ ] Trace request from ingress through authentication/context, planner, search/graph, execution, and response.
- [ ] Verify each component has one responsibility and does not duplicate another layer's policy.
- [ ] Check validation, deadlines, cancellation, retries, bulkheads, and downstream error mapping.
- [ ] Ensure internal topology/storage implementation does not leak into public responses.
- [ ] Inspect LLM input construction, output validation, model/version provenance, token/time limits, and fallback behavior.
- [ ] Ensure synthesis cannot invent source attribution or override authorization.
- [ ] Review MCP discovery, authorization, scope enforcement, transport conformance, and error semantics.
- [ ] Check startup wiring for duplicate beans, accidental component scanning, and hidden conditional configuration.

### 4.8 GPU and extraction RPC clients/gateways

- [ ] Compare generated stubs and wire definitions with versioned protobuf contracts.
- [ ] Check compatibility and unknown-field behavior.
- [ ] Verify mTLS/authentication, endpoint identity, and isolation assumptions.
- [ ] Test deadlines, retries, cancellation, channel lifecycle, and unavailable server.
- [ ] Verify model/extractor identity and artifact digest are retained as provenance.
- [ ] Check transport failures map to stable domain errors and are observable.

### 4.9 Eventing and outbox

- [ ] Verify envelope schema, event identity, tenant scope, correlation/causation IDs, and schema version.
- [ ] Trace event creation through durable outbox commit and broker publication.
- [ ] Confirm business state and outbox record are atomic where required.
- [ ] Test duplicate/out-of-order delivery, poison messages, retry exhaustion, DLQ, replay, and restart.
- [ ] Verify consumers are idempotent and cannot regress newer projection state.
- [ ] Check backpressure, bounded queues, shutdown, and metrics.
- [ ] Ensure events represent facts and commands represent intent.

### 4.10 Storage adapters and migrations

For each adapter:

- [ ] Compare port contract with adapter implementation.
- [ ] Check transaction/isolation semantics, optimistic concurrency, uniqueness, and locking.
- [ ] Verify tenant scoping and data-layer enforcement.
- [ ] Check pagination ordering, cursor stability, and filtering semantics.
- [ ] Test timeouts, connection loss, pool exhaustion, and retry behavior.
- [ ] Verify resource cleanup and lifecycle management.
- [ ] Check migration forward-safety, recovery/rollback, and old/new application compatibility.
- [ ] Run migration tests against the real database engine and deployment migration path.
- [ ] Compare adapter behavior and document intentional capability differences.
- [ ] Verify backup/restore and rebuild paths for deployed stateful components.

**Module exit gate:** responsibility, dependencies, contracts, persistence, failure behavior, tests, and status are documented; no unresolved P0/P1 issue is hidden.

## 5. Step 3 — API and contract review

Treat public REST, gRPC/protobuf, MCP, event schemas, storage ports/SPI, and internal module interfaces as first-class APIs.

### 5.1 Build an API inventory

For every API/contract, record:

| Field | Required information |
|---|---|
| Name | Contract/API name and version |
| Location | OpenAPI, proto, schema, interface, or event definition |
| Producer | Owning module/service |
| Consumers | Modules, UI, external clients, workers |
| Stability | Internal, experimental, frozen, public/versioned |
| Identity | Resource IDs, tenant scope, correlation/causation IDs |
| Compatibility | Additive/breaking-change rules |
| Errors | Error codes, retryability, HTTP/gRPC mapping |
| Security | Authentication, authorization, classification, tenant enforcement |
| Evidence | Contract, consumer, and compatibility tests |

### 5.2 REST/API boundary

- [ ] Check resource naming, HTTP methods, status codes, and versioning.
- [ ] Verify request/response schemas and required/optional fields.
- [ ] Check pagination, sorting, filtering, cursor stability, and authorization-aware counts/facets.
- [ ] Verify consistent machine-actionable error schema, retryability, and request/correlation ID.
- [ ] Check idempotency keys and duplicate mutation behavior.
- [ ] Verify async acceptance means durable Operation state exists before success is returned.
- [ ] Keep Resource lifecycle separate from Operation lifecycle.
- [ ] Check deprecation and compatibility policy.
- [ ] Ensure clients cannot address internal services or select storage/broker topology.

### 5.3 gRPC/protobuf

- [ ] Verify service/method names, field numbers, optionality, enum evolution, and package/version conventions.
- [ ] Check deadline propagation, cancellation, authentication, and metadata.
- [ ] Verify compatibility with generated client/server code.
- [ ] Check status/error mapping and retry safety.
- [ ] Confirm payload-size limits and reference-vs-inline payload policy.
- [ ] Add compatibility tests for supported client/server combinations.

### 5.4 Events and asynchronous contracts

- [ ] Check envelope and schema evolution.
- [ ] Verify event identity and ordering assumptions.
- [ ] Check durable acceptance, replay classification, idempotency, and deduplication.
- [ ] Ensure tenant/security context survives async boundaries and is not reconstructed from untrusted fields.
- [ ] Check retryability and permanent-failure semantics.
- [ ] Verify consumers tolerate additive fields and reject incompatible changes safely.

### 5.5 Contract-to-code traceability

For every important field and invariant:

- [ ] Find producer implementation.
- [ ] Find consumer implementation.
- [ ] Find positive test.
- [ ] Find negative/security/failure test.
- [ ] Confirm tests use production-shaped objects and serialization.
- [ ] Confirm API documentation describes actual behavior.

**API exit gate:** each reviewed contract has an owner, known consumers, compatibility rules, security behavior, and executable evidence.

## 6. Step 4 — Cross-module vertical-slice review

A module can pass in isolation while the integrated flow is broken. Trace these flows end to end.

### Flow A — Upload/ingestion to searchable content

- [ ] Client request and identity context.
- [ ] Source registration and stable source identity.
- [ ] Upload/storage and content digest.
- [ ] Extraction and feature-state reporting.
- [ ] Chunking and embedding generation.
- [ ] Artifact manifest and generation publication.
- [ ] Event/outbox publication where applicable.
- [ ] Search index and graph/ontology projection update.
- [ ] Search by the same source/corpus and verify returned references.

At each handoff record identifiers, tenant scope, generation/version, provenance, and failure behavior.

### Flow B — Search to attributed answer

- [ ] Authentication and trusted tenant scope.
- [ ] Request validation and query planning.
- [ ] Search candidate eligibility before ranking.
- [ ] Ontology/graph enrichment with valid references.
- [ ] Synthesis/reranking if enabled.
- [ ] Response citations and source attribution.
- [ ] UI rendering and navigation to underlying source.

**Critical check:** evaluation artifacts, graph nodes, ontology entries, embeddings, and gold-rank files must refer to the same deployed corpus and generation.

### Flow C — Change/recalculation

- [ ] Source, ontology, model, policy, or definition change is identified.
- [ ] Dependency/impact analysis finds affected derived state.
- [ ] Recalculation plan is deterministic and reviewable.
- [ ] Execution is bounded and does not starve interactive work.
- [ ] Derived artifacts/projections publish atomically or expose explicit generation state.
- [ ] Failure resumes without duplicate or regressive state.
- [ ] Provenance links outputs to inputs, versions, and processing run.

### Flow D — Tenant isolation

- [ ] Validate signed identity and tenant claim.
- [ ] Convert once into immutable tenant scope at boundary.
- [ ] Propagate scope through services, events, repositories, caches, graph, and search.
- [ ] Enforce at persistence layer, including RLS where applicable.
- [ ] Test direct lookup, search, graph traversal, pagination/counts, cache hits, and async consumers across tenants.
- [ ] Verify fail-closed behavior and anti-oracle error semantics.

### Flow E — Restart and recovery

- [ ] Restart each service during active work.
- [ ] Verify durable work resumes or reports explicit terminal state.
- [ ] Check outbox and consumer recovery.
- [ ] Rebuild search/graph projections from authoritative artifacts.
- [ ] Confirm no duplicate publication or stale-generation regression.
- [ ] Verify another developer can follow recovery instructions.

**Flow exit gate:** each accepted capability has a clean, scripted, reproducible path and negative-path evidence.

## 7. Step 5 — Test quality review

### 7.1 Inventory test layers

- [ ] Unit: domain logic and deterministic algorithms.
- [ ] Component: module wiring and configuration.
- [ ] Contract: producer/consumer agreement.
- [ ] Integration: real persistence, broker, and RPC boundaries.
- [ ] Acceptance: user-visible end-to-end behavior.
- [ ] Security: tenant isolation, authorization, classification, fail-closed.
- [ ] Failure/recovery: restart, retry, duplicate, timeout, partial outage.
- [ ] Performance: representative workload, latency, throughput, resource use.
- [ ] Migration: schema upgrade and application compatibility.

### 7.2 Audit whether tests prove what they claim

For each critical test:

- [ ] Could it pass while production is broken?
- [ ] Does it use the production dependency-injection/configuration path?
- [ ] Does it use the real serialized request/event/record shape?
- [ ] Are mocks hiding the boundary under review?
- [ ] Does it assert meaningful outcomes, not only status/non-null?
- [ ] Does it clean up fixtures and avoid dependence on previous runs?
- [ ] Is it deterministic and isolated?
- [ ] Does CI actually execute it?
- [ ] Does the test name match its assertions?

### 7.3 Minimum failure matrix

| Boundary | Required scenarios |
|---|---|
| HTTP/gRPC | Timeout, connection refused, malformed response, cancellation |
| Database | Unavailable, rollback, constraint violation, pool exhaustion |
| Broker/outbox | Duplicate, delayed, out-of-order, poison event, restart |
| Search/vector | Missing index, stale generation, backend unavailable, partial results |
| Graph/ontology | Missing reference, invalid schema, stale projection |
| Identity/security | Missing claim, invalid signature, unauthorized tenant, cross-tenant object |
| File/extraction | Oversized, malformed, unsupported, partial extraction, interrupted upload |
| Lifecycle | Startup failure, graceful shutdown, crash during work, restart/replay |

**Exit gate:** critical tests run in CI and exclusions/untested risks are explicit.

## 8. Step 6 — Runtime, deployment, and operations

- [ ] Map each application to deployment unit, image, configuration, service account, and dependencies.
- [ ] Review Dockerfiles, Compose, Kubernetes manifests, Helm/Kustomize resources, and environment templates.
- [ ] Check probes, startup ordering, graceful shutdown, resource requests/limits, autoscaling, and rollout strategy.
- [ ] Verify persistent-volume access modes and rollout compatibility.
- [ ] Check secrets are externalized and never committed/logged.
- [ ] Verify migrations run once and safely under rolling/recreate deployment.
- [ ] Review network policies, TLS/mTLS, service identity, and exposed ports.
- [ ] Check logs, metrics, traces, correlation IDs, dashboards, and actionable alerts.
- [ ] Test backup/restore and projection rebuild for stateful components.
- [ ] Attempt diagnosis using only the runbook.
- [ ] Check saturation, backpressure, queue growth, and slow downstream dependencies.

Record the exact topology tested. A local demo or homelab deployment is not production-readiness evidence by itself.

## 9. Step 7 — Documentation and developer instructions

Review documentation after understanding the code, then compare claims back to implementation.

### 9.1 Repository-level docs

- [ ] README.md: purpose, architecture, modules, build, tests, quick start, project status.
- [ ] docs/INDEX.md: navigation and canonical sources of truth.
- [ ] docs/DOCUMENTATION-INVENTORY.md: current, historical, duplicated, superseded docs.
- [ ] Contribution and developer setup guides.
- [ ] Developer/agent instruction files such as CLAUDE.md if present: commands, conventions, security, tests, review rules.
- [ ] .github templates, CODEOWNERS, security policy, CI documentation.

Can a new contributor follow the documented path without private knowledge or manual undocumented steps?

### 9.2 Architecture and ADRs

- [ ] Architecture index and capstone.
- [ ] Normative design documents for each reviewed plane.
- [ ] ADRs for module ownership, contracts, security, storage, and deployment.
- [ ] Diagrams match actual module/runtime dependencies.
- [ ] Invariants map to code and tests.
- [ ] Design status matches implementation evidence.
- [ ] Superseded documents link to canonical replacements and are labelled historical.

Do not use an older design snapshot as the current checklist when a newer capstone or plane-specific document supersedes it.

### 9.3 Module and implementation docs

For every module/plane:

- [ ] Implementation plan exists and has current status/last-reviewed date.
- [ ] It names actual code paths, interfaces, tests, and deployment units.
- [ ] Completed items link to PRs/tests/evidence.
- [ ] Planned capabilities are distinguished from implemented behavior.
- [ ] Non-goals and deferred capabilities are explicit.
- [ ] Known gaps link to issues and resolution criteria.
- [ ] Diagrams are consistent with code.

### 9.4 API and integration docs

- [ ] REST/OpenAPI reference matches controller behavior.
- [ ] gRPC/protobuf docs match checked-in definitions.
- [ ] Event schemas/examples match serialized payloads.
- [ ] Authentication, authorization, tenant, and error semantics are explicit.
- [ ] Retry, idempotency, pagination, timeout, and compatibility rules are documented.
- [ ] Examples are runnable and use safe placeholder credentials.
- [ ] Public-contract deprecation/migration guidance exists.

### 9.5 Operations and user docs

- [ ] Operations index links to current runbooks.
- [ ] Deployment, configuration, upgrade, migration, backup, restore, rollback steps are executable.
- [ ] Troubleshooting starts with symptoms and ordered checks.
- [ ] User guides describe supported behavior, not roadmap capabilities.
- [ ] Demo guides specify corpus, fixture versions, expected outputs, and cleanup.
- [ ] Benchmarks specify hardware, dataset, configuration, warm-up, repetitions, and measurement method.

**Documentation exit gate:** no critical instruction is missing, misleading, stale, or contradicted by code; gaps have owners and closure criteria.

## 10. Step 8 — Revalidate known priorities

Recheck issue state and current code before relying on these starting points.

| Priority | Area | Starting point | Review focus |
|---|---|---|---|
| P0/P1 | Corpus/artifact identity | [#508](https://github.com/synanton/platform/issues/508) | Deployed books vs evaluation artifacts; search, graph, ontology, embeddings, gold ranks must share corpus identity |
| P1 | Deployment rollout | [#506](https://github.com/synanton/platform/issues/506) | RWO PVC behavior with RollingUpdate; actual manifest and rollout strategy |
| P1 | Gateway startup | [#500](https://github.com/synanton/platform/issues/500) | Duplicate bean registration and startup behavior |
| P1 | Tenant enforcement | [#339](https://github.com/synanton/platform/issues/339) | RLS, transaction scope, scoped repositories, cache keys, cross-tenant tests |
| P1 | API/MCP security | Platform API plane plan and MCP audit | Scope enforcement, pre-auth discovery, bearer propagation, error semantics |
| P1 | Vertical-slice trust | [trust-bar.md](./trust-bar.md), [gap-register.md](./gap-register.md) | Independent reproduction, acceptance-test audit, failure coverage, diagnostics, bounded gaps |
| P2 | Eventing reliability | [eventing-workflow-plane](../eventing-workflow-plane/INDEX.md) | Durable outbox, idempotency, restart/replay, permanent failure |
| P2 | Storage parity | Storage modules/testkit | Contract parity, migrations, CI coverage, backend semantics |

Merged changes such as #516/#517 must be reviewed as current code; they do not by themselves prove that broader corpus/graph consistency is resolved.

## 11. Finding format and severity

Create one finding per independently actionable problem.

### Finding template

    ### [P1] Short observable problem statement
    - Area: repository / module / API / docs
    - Location: path, class/function, line or symbol
    - Evidence: test, command, log, trace, reproduction
    - Expected: behavior required by contract/design
    - Actual: observed behavior
    - Impact: user, data, security, reliability, maintainability, delivery
    - Root cause: confirmed cause; mark hypotheses explicitly
    - Correction: smallest coherent change
    - Regression test: test that fails before the fix
    - Acceptance criteria: measurable closure conditions
    - Related: design, ADR, issue, PR

| Severity | Meaning | Examples |
|---|---|---|
| P0 | Critical security exposure, data corruption/loss, or core workflow unusable without safe workaround | Cross-tenant disclosure, destructive migration, unrecoverable artifact corruption |
| P1 | Major correctness, security, reliability, or contract failure | Search references wrong corpus; async acceptance is not durable |
| P2 | Material maintainability, test, observability, or operational weakness | Integration suite excluded from CI; no diagnosis path |
| P3 | Low-impact inconsistency or polish | Minor wording or non-blocking duplication |

Severity reflects impact and likelihood, not implementation effort.

## 12. Step 9 — Execution sequence and deliverables

| Phase | Work | Deliverable | Exit condition |
|---|---|---|---|
| 0. Baseline | Repo map, build/CI, current issues/PRs | Baseline report | Structure and test baseline reproducible |
| 1. Critical flows | Upload-to-search, search-to-attribution, tenant isolation, restart | Flow traces and failure matrix | Broken handoffs identified |
| 2. Modules | Module cards in dependency order | Module review records | In-scope modules have evidence-based status |
| 3. APIs/contracts | REST, gRPC, MCP, events, storage SPI | Contract inventory | Owners, consumers, security, tests traced |
| 4. Tests | Integrity, CI coverage, failure cases | Test audit | Critical tests prove production-shaped behavior |
| 5. Operations | Deployment, observability, recovery | Operational findings | Another developer can deploy and diagnose |
| 6. Docs | README, architecture, API, module docs, instructions | Documentation findings | Instructions match code and are reproducible |
| 7. Stabilization | Prioritize findings and agree fixes | Ordered backlog and acceptance gates | No hidden P0/P1; residual risks explicit |

Suggested effort for one engineer: **8–15 working days for a broad first pass**, excluding implementation and retesting.

### Recommended first pass

1. Map the actual codebase and establish the build baseline.
2. Reproduce the deployed demo and trace corpus identity end to end.
3. Review synquest, its storage adapters, relix, syntology, and ingress/gateway.
4. Review tenant/security propagation through that same path.
5. Review eventing/content-artifact integration only where the current accepted path uses it.
6. Expand to remaining modules and architecture planes after the first path is stable.

Do not start with UI polish, broad abstractions, a new retrieval algorithm, or production topology redesign. First make the supported path correct, reproducible, attributable, and diagnosable.

## 13. Review tracker

Use one row per module/API/document group. Status values: Not started, In progress, Blocked, Reviewed, Accepted with gaps. Link review records and findings.

| Order | Area | Status | Evidence/findings |
|---:|---|---|---|
| 0 | Repository baseline and build | Not started | |
| 1 | Shared foundation: common/security/topology | Not started | |
| 2 | Contracts: storage, extraction, GPU, eventing | Not started | |
| 3 | Storage adapters and migrations | Not started | |
| 4 | Synvault and Synflux ingestion | Not started | |
| 5 | Content artifact/cache lifecycle | Not started | |
| 6 | Syntology and annotations | Not started | |
| 7 | Synquest and retrieval adapters | Not started | |
| 8 | Relix graph | Not started | |
| 9 | Planner and gateway | Not started | |
| 10 | Synapt and Platform API | Not started | |
| 11 | MCP and LLM integration | Not started | |
| 12 | Eventing and outbox | Not started | |
| 13 | GPU/extraction runtime integrations | Not started | |
| 14 | Cross-module vertical slices | Not started | |
| 15 | Tests and CI | Not started | |
| 16 | Deployment and operations | Not started | |
| 17 | Architecture and ADRs | Not started | |
| 18 | API and contract documentation | Not started | |
| 19 | Developer instructions and contribution docs | Not started | |
| 20 | User guides, demos, benchmarks | Not started | |
| 21 | Stabilization and acceptance | Not started | |

## 14. Final acceptance checklist

- [ ] Repository/module inventory reflects the reviewed branch.
- [ ] Build and CI baseline is documented, including excluded tests.
- [ ] Every in-scope module has a review record.
- [ ] Public/internal contracts have owners and consumer traceability.
- [ ] Critical vertical slices are reproducible from a clean environment.
- [ ] Tenant/security behavior has positive and negative evidence.
- [ ] Persistence, migration, retry, duplicate, restart, and recovery behavior is tested.
- [ ] Test claims have been audited against production behavior.
- [ ] Deployment and diagnosis steps have been exercised by another developer where feasible.
- [ ] Documentation and developer instructions match the code.
- [ ] Every P0/P1 is fixed, explicitly accepted with safe mitigation, or blocks acceptance.
- [ ] Remaining P2/P3 gaps have owners, triggers, and resolution criteria.
- [ ] Acceptance names an explicit scope and commit SHA; it is not generalized to the whole platform.

### Final review statement

    Reviewed scope: [modules / flows / APIs / docs]
    Commit: [SHA]
    Date and environment: [details]
    Result: [Accepted / Accepted with gaps / Not accepted]
    Blocking findings: [IDs or none]
    Accepted residual risks: [IDs or none]
    Evidence: [test runs, traces, PRs, reports]
