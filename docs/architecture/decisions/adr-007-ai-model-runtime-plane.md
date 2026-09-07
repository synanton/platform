# ADR-007: AI and Model Runtime Plane Contract

**Status:** Accepted
**Date:** 2026-09-07
**Deciders:** Architecture team
**Design Document:** [synanton-design-1.30.md](../synanton-design-1.30.md)

## Context

Synanton needs to execute a growing and heterogeneous set of AI/ML capabilities — LLM inference, embeddings, reranking, classification, OCR, document understanding, audio/image/speech processing, summarization and extraction — in support of the ingestion, knowledge, annotation and search planes. These workloads have different resource profiles and may run on CPU, on a dedicated GPU cluster, through a remote inference service, or through a future runtime implementation. Without a stable Platform-owned contract, model execution assumptions (a specific GPU vendor, inference engine, model server, or scheduling strategy) would leak into the Knowledge Plane, coupling business logic to infrastructure choices that are expected to change.

Design 1.20 had already established a physical isolation boundary between the primary platform and a dedicated GPU Execution Plane for one gRPC contract (`synanton.gpu.v1`). Design 1.30 generalizes that boundary into a normative, transport-neutral contract that any runtime implementation — the Synanton GPU Runtime reference project (`synanton/gpu-runtime`), a CPU runtime, a remote runtime, or a future runtime — must conform to, independent of accelerator or vendor.

The proposal was developed alongside the cross-plane architecture review covering Designs 1.26–1.33 (GitHub Issue #39; see `docs/architecture/proposals/synanton-architecture-review-resolution.md`). That review's specific finding for this plane — *"AI Platform/GPU Runtime boundary: 1.30 explicitly assigns normative contract/conformance to Platform and physical execution to GPU Runtime"* — confirmed the proposal already drew this boundary explicitly and repeatedly (Executive Summary, §3 WHAT/HOW split, §6.3, §54, §60–§61, §67 Final Thesis). No substantive rewrite of that boundary was required when folding the proposal; the fold instead made the boundary's relationship to the Design 1.20 GPU Execution Plane and to the Platform 1.0 baseline's WHAT/WHY/SECURITY/PROVENANCE vs. HOW/SCHEDULING/HARDWARE framing (`synanton-platform-1.0-proposal.md` §8) explicit and cross-referenced, and made clear that the execution state machine in §18–§21 is a domain contract meant to be carried by the Design 1.27 eventing/workflow substrate rather than by bespoke per-plane asynchronous infrastructure. One cosmetic defect — an inconsistent heading level on the Executive Summary section relative to every other numbered section, an artifact of document export — was corrected; no other citation-tool artifacts, escaped Markdown, or broken anchors were found. No other substantive content changed.

## Decision

Accept and fold in the Design 1.30 AI and Model Runtime Plane Contract, which introduces:

- **A transport-neutral Platform/Runtime boundary** — the Platform owns model registry and identity, versioning and immutability, execution/result contracts, provenance and configuration identity, security-context propagation, and evaluation/reproducibility semantics (determinism vs. bit-identical execution, model-version comparability); a separate runtime implementation owns scheduling, hardware, and execution engines (§1, §3, §54, §61 Invariant 10)
- **Model identity and versioning** — stable logical identity (`model_id` + `model_version` + immutable `artifact_digest`), with model version as part of provenance (§9–§10, §28–§30, §61 Invariant 2, Invariant 8)
- **A normative execution contract** — execution identity and idempotency (§13–§14), a canonical execution state machine (`PENDING → ACCEPTED → QUEUED → RUNNING → COMPLETED/FAILED/CANCELLED`) with runtime lease/heartbeat reconciliation (§18–§19, §61 Invariant 3–6), asynchronous execution and best-effort cancellation (§20–§21)
- **Security inheritance from Design 1.23** — tenant isolation, authorization, classification preservation, fail-closed behavior, and audit, none of which a runtime may bypass or downgrade (§2.3, §37–§42, §61 Invariant 7)
- **Provenance and reproducibility** — full traceability from source through execution to result, configuration identity, prompt identity, and an explicit determinism/reproducibility distinction (§33–§36)
- **Integration with existing planes** — Processing Runs and Resolutor/Equalix-driven recalculation (§47–§49), Content Cache references for large inputs/outputs (§53), and analytics as derived usage facts only (§52)
- **Conformance and a GPU Runtime reference implementation** — a Core/Extended/Production conformance tiering (§60) and a 9-phase implementation plan (§63), with the initial GPU Runtime kept intentionally small (gRPC/Protobuf, PostgreSQL, vLLM; no Kafka/Redis/Cassandra introduced without cause) (§55, §62)

## Consequences

**Enables:**
- Changing GPU hardware, inference engines, model servers, or runtime implementations without touching the Knowledge Plane or any Platform component that depends on `ModelRuntime`
- Reproducible, auditable AI processing: every result is traceable to a specific immutable model artifact, configuration, and processing run, and model-version changes are representable as dependency-changing events for targeted recalculation
- Multiple runtime implementations (GPU, CPU, remote, future) conforming to one contract, verified through a tiered conformance suite rather than ad hoc integration testing
- Security- and tenant-safe AI execution, since the runtime inherits and cannot downgrade the Design 1.23 classification/authorization/fail-closed model

**Requires:**
- Freezing the Protobuf/transport-neutral contract (model identity, execution identity, state machine, errors, capability discovery) before implementation proceeds (Phase 1)
- Building or adapting a conformant runtime — the GPU Runtime reference implementation (`synanton/gpu-runtime`) — that satisfies Core, Extended, and Production conformance tiers, including lease/reconciliation, idempotency, and artifact integrity verification
- Wiring asynchronous inference-job carriage (queuing, status propagation, retries) through the Design 1.27 eventing/workflow substrate rather than inventing plane-specific async infrastructure
- Extending Resolutor/Equalix integration so model-version changes correctly trigger dependency-aware recalculation (§48–§49)
- An `analytics`- and `test:security`-style conformance/security test tier covering tenant isolation, idempotency, security-context inheritance, and artifact-integrity failure modes for any runtime implementation

**Trade-offs:**
- The contract is intentionally minimal (§62): no bespoke distributed infrastructure is introduced merely because it exists elsewhere in Synanton, which defers some production concerns (advanced scheduling, multi-region execution, model ensembles) to explicitly non-blocking future extensions (§65)
- Determinism is only preferred, not guaranteed, for GPU/LLM workloads (§36); reproducibility work must operate on "reproducible configuration," not "bit-identical execution"
- Streaming, webhooks, and batching are optional/best-effort capabilities that must be advertised through capability discovery rather than assumed, which pushes complexity onto capability negotiation rather than a single fixed execution model

## Implementation Status

Not started — architecture accepted, no implementation exists yet. No `AI Runtime` or `gpu-runtime` implementation phase tracking exists under `docs/implementation/` at the time of this fold. The reference implementation (`synanton/gpu-runtime`) is a separate project; its existing execution-plane implementation plans (PostgreSQL execution state, artifact resolution, concurrency control, leases, idempotency, gRPC/Protobuf, Java 21/Spring Boot/Flyway, Micrometer/Prometheus, OpenTelemetry, Kubernetes/Helm, vLLM) are the implementation decisions this contract formalizes (§55), but conformance against this Design 1.30 contract has not yet been established, and Phase 1 (Contract freeze, §63) has not begun.
