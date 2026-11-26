# TRIZ-Inspired Engineering Problem Solving

## Purpose

This document defines a practical, lightweight way for Synanton engineering agents and contributors to use ideas from TRIZ (the Theory of Inventive Problem Solving, developed by Genrich Altshuller) when exploring difficult engineering problems.

The goal is not to make every task a formal TRIZ exercise. It is to help an agent avoid premature commitment to the first plausible implementation, recognize structural contradictions, and explore a wider solution space before choosing an approach.

TRIZ is a source of heuristics, not an oracle. Its principles generate questions and candidate directions; architecture, experiments, and evidence determine whether a candidate is useful.

## When to use it

Consider this method when a problem has one or more of these characteristics:

- Improving one property appears to degrade another (for example, recall versus precision, flexibility versus operational simplicity, or freshness versus query cost).
- A design accumulates special cases, flags, filters, retries, or configuration to compensate for a recurring limitation.
- A local fix repeatedly moves the problem elsewhere in the system.
- Requirements seem mutually incompatible under the current architecture.
- The initial implementation is expensive, fragile, or difficult to maintain, and a different decomposition may be possible.
- Several architectural approaches appear plausible and the consequences are significant.

For routine, well-understood changes, use normal engineering judgment and proceed directly.

## Working principles

1. **Start from the real outcome.** Restate the problem in terms of the result that must improve, not merely the requested implementation.
2. **Respect evidence and invariants.** Separate facts, assumptions, constraints, and preferences. Preserve security, correctness, compatibility, and operational invariants.
3. **Look for the contradiction.** Ask what gets worse when something improves, or which requirement prevents another from being satisfied.
4. **Search the system for resources.** Existing representations, components, metadata, lifecycle stages, events, indexes, policies, and operational signals may be recombined before adding new infrastructure.
5. **Generate distinct alternatives.** Use inventive principles as prompts for different structural approaches, not as labels attached to variations of the same design.
6. **Choose and test.** Compare options against explicit criteria and validate the uncertain parts with experiments.
7. **Preserve learning.** Record the important rejected options, evidence, and decision where future maintainers will find them.

## A practical workflow

### 1. Frame the problem

Write a short problem statement containing:

- Desired outcome and how it will be measured.
- Current behavior and the observed failure or limitation.
- Functional and non-functional requirements.
- Hard constraints and project invariants.
- Assumptions that still need validation.

Avoid encoding a proposed solution into the problem statement. For example, "we need another filter" is a proposed remedy; "candidate extraction creates too many false positives while early canonicalization loses recall" is a problem description.

### 2. Identify the contradiction

Ask:

- What property should improve?
- What property becomes worse as a result?
- Is the conflict technical (the same system or element must behave differently in incompatible ways), or is it a resource/architecture conflict?
- Is the conflict fundamental, or does it arise only because of the current decomposition?

Express it plainly, for example: "We want broader candidate generation to improve recall, but broadening candidates increases false positives and downstream resolution cost."

If there is no meaningful contradiction, do not invent one. The problem may be a straightforward defect, missing capability, or unclear requirement.

### 3. Identify available resources

Before adding a new service, dependency, model, data store, or abstraction, inspect what is already available:

- Existing pipeline stages and intermediate representations.
- Domain models, metadata, provenance, and lifecycle states.
- Interfaces, adapters, event streams, caches, indexes, and graph structures.
- Existing validation, policy, retry, scheduling, and observability mechanisms.
- Information available earlier or later in the lifecycle.
- Unused capacity, time, context, or information that can be exploited safely.

Resource reuse is not an absolute rule: reuse only when it preserves clear ownership and does not create hidden coupling.

### 4. Generate alternatives with inventive principles

Use the principles below as prompts. Select only those that illuminate the problem. The examples are engineering interpretations, not a claim that every principle maps one-to-one to software architecture.

| Principle | Prompt for engineering agents | Possible Synanton application |
| --- | --- | --- |
| Segmentation (1) | Can a coupled operation be split into independently testable stages? | Separate mention detection, candidate generation, entity resolution, and canonical promotion. |
| Taking out / extraction (2) | Can the problematic function or responsibility be isolated? | Move expensive model inference behind an explicit adapter or execution boundary. |
| Local quality (3) | Should different contexts use different policies rather than one global rule? | Apply domain- or document-specific extraction and validation policies. |
| Merging (5) | Can related operations or evidence sources be coordinated to reduce duplication? | Combine compatible retrieval signals through a defined fusion stage. |
| Universality (6) | Can one component serve multiple purposes without violating its contract? | Reuse a typed intermediate representation across evaluation and downstream processing where semantics align. |
| Preliminary action (10) | Can preparation happen earlier, before the constrained step? | Precompute candidate features or provenance before entity resolution. |
| Beforehand cushioning (11) | Can a reversible buffer or safety layer absorb uncertainty? | Keep extracted candidates provisional until validation and resolution complete. |
| Dynamization (15) | Can a fixed structure or policy adapt to context or lifecycle state? | Select extraction strategy by document type, ontology, or processing stage. |
| Transition to another dimension (17) | Can the problem be represented in a different structural dimension? | Move from a flat list of entities to a provenance-aware dependency or knowledge graph when relationships are essential. |
| Feedback (23) | Can results influence upstream behavior through an explicit loop? | Use adjudicated extraction errors and benchmark results to guide later configuration changes. |
| Intermediary (24) | Can an intermediate object or stage bridge incompatible concerns? | Introduce pre-entities between raw mentions and canonical entities. |
| Self-service (25) | Can a component use its own outputs or signals to improve operation? | Use pipeline diagnostics to identify low-confidence stages and trigger targeted review. |
| Copying (26) | Can a cheaper model or representation be used instead of the expensive original? | Use compact candidate features for broad filtering before expensive model or graph operations. |
| Parameter changes (35) | Can a property be changed rather than redesigning the whole system? | Tune chunking, thresholds, batching, or retrieval parameters when evidence shows the architecture is sound. |

The classical 40-principle set is larger than this practical selection. Consult the full set or a contradiction matrix when useful, but do not assume the historical matrix directly captures modern software, data, or distributed-systems trade-offs. Treat its suggestions as hypotheses.

For a **physical contradiction**--where the same element appears to need incompatible properties--consider separation by time, space, condition, or scale. In software this may translate into lifecycle-specific states, context-specific policies, tenant/domain boundaries, or different representations at different stages. Use these analogies carefully; they are prompts, not formal equivalences.

### 5. Build a small option set

For a consequential problem, aim for two or three materially different approaches, where practical:

- **Baseline:** the smallest change to the current design.
- **Structural alternative:** a different decomposition, lifecycle, or boundary.
- **Resource-recombination alternative:** a solution that uses existing capabilities or information differently.

Do not force three options when one is clearly correct or when the cost of exploration exceeds the decision's significance. State why an option is not viable rather than padding the list.

### 6. Evaluate and experiment

Compare candidates using criteria appropriate to the problem. Typical Synanton criteria include:

- Correctness and semantic integrity.
- Tenant isolation, authorization, and data handling.
- Provenance, auditability, and reproducibility.
- Complexity and number of new concepts or dependencies.
- Maintainability and fit with existing module boundaries.
- Latency, throughput, storage, and resource consumption.
- Failure handling, retries, idempotency, and recovery.
- Compatibility, migration cost, and operational burden.
- Measured impact on relevant quality metrics.

Use a short decision table or focused experiment. Prefer the cheapest experiment that can falsify the important assumption. A prototype is evidence about the tested conditions, not proof of production readiness.

### 7. Decide and capture the result

For a significant architectural or cross-module decision, record:

- Problem and contradiction (if any).
- Relevant resources and principles considered.
- Alternatives evaluated, including the baseline.
- Evaluation criteria and evidence.
- Selected approach and trade-offs.
- Rejected options and why they were rejected.
- Remaining risks and follow-up validation.

Use an ADR when the decision changes or constrains architecture; use an issue, design note, or PR description for smaller decisions. Follow the repository's existing ADR conventions in `CLAUDE.md`.

## Synanton-oriented examples

### Example A: Extraction recall versus precision

**Problem:** Broad candidate extraction improves recall but increases false positives and makes canonical resolution more expensive.

A purely local response might keep adjusting thresholds or add more filters. A TRIZ-inspired exploration asks whether extraction is committing too early.

Possible approaches:

1. Tune the current extractor and measure the precision/recall curve.
2. Segment the lifecycle into mention detection, pre-entity representation, candidate resolution, and explicit canonical promotion.
3. Use a provisional intermediary representation that retains source span, context, type hypotheses, and evidence until downstream validation.

The second and third approaches may preserve uncertainty instead of forcing an early canonical decision. They should be evaluated against the frozen gold dataset, including judged granularity, unjudged cases, and reproducibility. Do not infer success from architectural elegance alone.

### Example B: Retrieval quality versus latency

**Problem:** Adding retrieval signals may improve relevance but increase query cost and operational complexity.

Possible approaches include measuring whether a signal contributes unique relevant results, applying fusion only where justified, caching stable work, or moving expensive enrichment out of the synchronous query path. A benchmark should test quality and latency together; optimizing one in isolation can hide a regression in the other.

### Example C: Ontology expressiveness versus domain isolation

**Problem:** Sharing concepts across domains can improve interoperability but may leak assumptions or inferences between contexts.

Explore separate domain-owned ontologies connected through explicit, governed mappings; context-scoped reasoning; and a minimal shared identity foundation. Validate that cross-domain mappings do not silently promote one domain's interpretation to universal truth. Use ontology and SHACL tests to make boundaries executable. This is an exploration prompt only; it does not change the current decision to defer ontology v2.

### Example D: Flexibility versus operational simplicity

**Problem:** A configurable distributed execution design may support more deployment modes but increase failure states and operational burden.

Before adding a broker or orchestration dependency, examine existing in-process dispatch, durable task state, resumability, fencing, and explicit delivery semantics. A simpler execution mode with clear guarantees may be preferable to a more distributed design whose side-effect semantics are underspecified.

## Agent output template

For a substantial problem where this method is useful, an agent may use this compact format in its analysis or engineering proposal:

```text
Problem:
Desired outcome / success measure:
Constraints and invariants:
Contradiction (or "none identified"):
Available resources:
Inventive prompts considered:
Alternative approaches:
Evaluation and evidence:
Recommendation:
Risks / unresolved assumptions:
```

This template is optional. Do not require it for routine code changes or repeat information already captured clearly in an issue or design document.

## Guardrails

- Do not claim that a solution is TRIZ-derived merely because it can be associated with a principle after the fact.
- Do not treat the contradiction matrix as authoritative for modern software engineering.
- Do not optimize a metric while violating security, correctness, data governance, or explicit product requirements.
- Do not introduce a new service, abstraction, or dependency solely to satisfy an inventive principle.
- Do not confuse generating alternatives with making progress; converge through evidence.
- Do not let an agent silently change scope or architectural policy. Significant decisions remain subject to the repository's normal review and approval process.

## Further reading

TRIZ originated in the work of Genrich Altshuller. This document intentionally provides an engineering-oriented adaptation rather than a complete course in classical TRIZ. Teams applying it formally should consult authoritative TRIZ references and distinguish the original method from the software-specific heuristics above.
