---
title: "Syntology Ontology Governance and Dynamic Evolution"
status: "proposed"
last_reviewed: "2026-11-23"
---

# Syntology Ontology Governance and Dynamic Evolution

**Status:** Proposed architecture; not an implementation claim  
**Owner:** Syntology / Platform Architecture  
**Scope:** Model-assisted ontology discovery, human review and editing, validation, publication, and controlled evolution  
**Related:** [Syntology architecture index](./INDEX.md), [ontology management v1.17 demo design](./ontology-management.md), [Domain Ontologies and Cross-Domain Semantic Mapping v0.3](../proposals/ontology-domain-mapping-v0.3.md), [Synanton Design 1.24/1.25](../synanton-design-1.25.md)

## 1. Decision summary

Synanton should use a **governed, versioned ontology with model-assisted semantic evolution**.

- A stable semantic core and owned domain ontologies provide reusable, consistent semantics.
- Models may discover concepts, properties, relationships, hierarchies, and constraints that appear to be missing.
- Model output is a proposal, never an active ontology mutation.
- Authorized humans can review, edit, approve, reject, defer, or request revision of a proposal.
- Deterministic validation is required before publication.
- Publication creates a new immutable ontology release and changes the active-version pointer through an authorized, audited operation.
- Existing active ontologies remain usable when dynamic discovery is disabled.

This design does not require one universal ontology, one model provider, or fully autonomous ontology generation.

## 2. Architectural principles

1. **Candidate is not canonical knowledge.** A discovered concept or relation remains outside the active ontology until governed publication.
2. **Stable identity.** Ontology, class, property, and relation identifiers are stable independently of labels, model runs, and release versions.
3. **Immutable releases.** Published ontology versions are immutable. Corrections and extensions produce a new release.
4. **Human authority.** Model confidence is advisory. It never substitutes for authorization, review, or validation.
5. **Evidence-backed proposals.** Every model-generated proposal carries source evidence, extraction/proposal run provenance, model and prompt identity where available, and the base ontology version.
6. **Deterministic gates.** Structural, SHACL, identity, compatibility, and policy validation are explicit and reproducible.
7. **Tenant and domain ownership.** Tenant-local discovery does not silently alter a shared domain ontology.
8. **Controlled recalculation.** Ontology publication is a semantic dependency change; impact analysis and recalculation are explicit, observable, and recoverable.
9. **Fail closed.** A stale proposal, failed validation, missing authorization, or unresolved publication conflict cannot change the active ontology.

## 3. Ontology layers and ownership

| Layer | Purpose | Governance |
|---|---|---|
| Foundational semantic core | Minimal cross-domain abstractions where justified | Centrally governed, deliberately small |
| Domain ontology | Domain-owned concepts, properties, relations, and constraints | Domain owner and designated reviewers |
| Tenant/workspace extension | Local concepts and vocabulary that should not become globally canonical | Tenant/workspace policy and reviewers |
| Discovered proposal space | Model- or human-created candidate changes | Isolated from published ontology; review required |
| Instance/evidence graph | Concrete entities and observed assertions tied to ontology releases | Produced by extraction and graph lifecycle; not an ontology authoring surface |

A tenant-local extension may reference a shared domain ontology but cannot modify it. Promotion from tenant-local scope to a shared domain registry is a separate governed operation with an explicit owner, compatibility review, and publication audit.

The existing domain-mapping baseline remains complementary: mapping candidates and mapping strategies stay separate from ontology concept identity and governed mapping assertions. This document does not change the approved v0.3 mapping baseline.

## 4. Dynamic ontology controls

Dynamic discovery is a configurable capability, not the authority boundary.

### 4.1 Configuration scope

Configuration should support organization defaults with tenant/workspace overrides where authorized. The effective policy must be resolved server-side and included in audit/provenance for each discovery run.

Initial controls:

| Control | Initial behavior |
|---|---|
| Dynamic discovery | Enable/disable model-generated ontology proposals |
| Proposal generation | Allowed only when dynamic discovery is enabled and policy permits the model/strategy |
| Human review | Required for every proposal before publication |
| Human editing | Available to users with ontology-edit permission |
| Automatic approval/publication | Disabled; not part of the initial operating mode |
| Manual proposal | Authorized users may create proposals even when model discovery is disabled |
| Existing ontology use | Always remains available subject to normal authorization and ontology lifecycle |

Disabling dynamic discovery stops new model-generated ontology proposals. It does **not** disable ontology reads, entity resolution against the active ontology, existing extraction against the pinned ontology version, review of already submitted proposals, or authorized human-authored proposals.

A disable operation must not silently delete or discard pending proposals. It prevents new model discovery runs; existing proposals remain reviewable, rejectable, or withdrawable under normal policy.

### 4.2 Safe defaults

- Dynamic discovery: configurable; default OFF until an administrator explicitly enables it for a scope.
- Human review: REQUIRED and not bypassable by a tenant-level feature toggle in the initial release.
- Human editing: enabled only for users with the relevant permission.
- Automatic publication: OFF.
- Model confidence: informational evidence only, never an auto-approval threshold.

The platform may later support narrowly scoped automated acceptance for low-risk changes, but that requires a separate policy decision, calibrated evaluation, explicit opt-in, audit, and rollback/recovery design. It is not implied by this proposal.

## 5. Proposal and publication domain model

Keep proposal workflow objects separate from published ontology definitions.

### 5.1 Core objects

| Object | Responsibility |
|---|---|
| OntologyDefinition | Stable ontology identity, namespace, owner, scope, and governance policy |
| OntologyRelease | Immutable published content, version, digest, validation record, and release metadata |
| OntologyProposal | Candidate set of additions, modifications, deprecations, or replacements |
| OntologyProposalRevision | Immutable snapshot of proposal content after each model or human edit |
| OntologyReview | Reviewer identity, decision, rationale, comments, and reviewed revision digest |
| OntologyValidationRun | Deterministic validation results against a specific proposal revision and base release |
| OntologyPublication | Audited publication operation linking the prior and new active release |

The model must not write directly to OntologyDefinition, OntologyRelease, or the active-version pointer.

### 5.2 Proposal contents

A proposal should carry:

- Proposal ID, tenant/workspace scope, ontology ID, and owner.
- Base ontology release ID/version and content digest.
- Candidate operation: add, amend, deprecate, replace, or merge.
- Stable candidate IDs or proposed identifiers, labels, definitions, parents, properties, relations, and constraints.
- Source evidence references and spans, where available.
- Discovery run ID, model/provider/version, prompt or strategy version, and relevant configuration.
- Model confidence and rationale, explicitly marked advisory.
- Duplicate/synonym candidates and known conflicts.
- Validation results and unresolved warnings.
- Proposal revision number and content digest.

Do not promote a model-generated label directly into a stable class/property identifier. Identity allocation is owned by the authoritative ontology registry/resolution contract; the final owner must be confirmed through the applicable identity ADR.

### 5.3 Lifecycle

Proposed lifecycle:

DRAFT → SUBMITTED → IN_REVIEW → APPROVED → PUBLISHED

Alternative transitions:

- IN_REVIEW → CHANGES_REQUESTED → SUBMITTED
- IN_REVIEW → REJECTED
- DRAFT/SUBMITTED → WITHDRAWN
- A proposal based on an obsolete release becomes STALE and must be rebased/revalidated; it cannot publish against the wrong base.

Each edit creates a new immutable proposal revision. Any edit after approval invalidates that approval and requires review of the new revision.

Approval and publication are separate auditable operations. Publication revalidates the approved revision and verifies that the active ontology release still matches the proposal's expected base. A mismatch fails closed and requires rebase/review.

## 6. Human review and editing

Reviewers need enough information to evaluate semantic meaning and operational impact, not just a model-generated diff.

A review should present:

1. Proposed class/property/relation changes and their definitions.
2. Existing ontology context: parent/child hierarchy, related properties, aliases, and possible duplicates.
3. Evidence passages and source links supporting the proposal.
4. Model/run provenance and confidence, clearly distinguished from validation.
5. SHACL and structural validation results.
6. Semantic conflicts, compatibility warnings, and unresolved ambiguities.
7. Impact analysis: affected instances, extraction mappings, graph assertions, queries, and dependent derived artifacts where known.
8. A before/after version diff and target ontology scope.

Authorized reviewer actions:

- **Approve:** approve the exact proposal revision after required validations pass.
- **Edit:** modify the proposal; save as a new revision and rerun validation.
- **Reject:** reject with a required rationale.
- **Request changes:** return actionable feedback for human or model revision.
- **Defer:** keep the proposal pending without changing the active ontology.
- **Merge/alias:** resolve a duplicate proposal against an existing canonical concept, subject to identity and equivalence policy.
- **Withdraw:** withdraw a proposal when authorized.

A reviewer cannot bypass validation by editing a proposal. Model regeneration after human feedback creates another revision; it cannot overwrite the reviewed revision.

## 7. Validation and publication

Validation is a deterministic gate and is separate from model reasoning.

Required validation categories:

- Syntax and schema compilation.
- Stable identifier and namespace constraints.
- SHACL validation of the candidate ontology and relevant affected data where supported.
- Parent/child and property domain/range consistency.
- Duplicate, synonym, equivalent, disjoint, and contradiction checks according to explicit semantic policy.
- Import/dependency resolution and cycle checks.
- Backward-compatibility and deprecation/replacement checks.
- Scope, authorization, and policy checks.
- Proposal base-release freshness and content-digest verification.

SHACL conformance is not proof of semantic equivalence or domain correctness. Model confidence is not a substitute for these checks.

Publication must:
1. Verify reviewer authorization and approval of the exact proposal revision.
2. Verify all required validation results are successful and current.
3. Compare-and-set the expected active release/base version to prevent lost updates.
4. Create a new immutable release and publication record.
5. Update the active release pointer only after the publication preconditions are satisfied.
6. Emit a versioned ontology-publication event with before/after release IDs and provenance.
7. Preserve prior releases and the review/audit history.

If publication or downstream processing partially fails, the release and active pointer must have an unambiguous recoverable state. Do not claim a distributed transaction across graph, search, vector, or analytics projections.

## 8. Relationship to extraction and graph construction

Extraction continues to resolve against a pinned, published ontology release.

When dynamic discovery is enabled and extraction encounters an unresolved concept or relation:
- retain the lexical expression, context, source span, pre-entity/candidate representation, and extraction provenance;
- optionally submit a discovery request to create an ontology proposal;
- do not create a canonical class or predicate as a side effect of extraction;
- do not block ordinary extraction solely because a proposal is pending, unless the extraction policy explicitly requires canonical typing;
- represent unresolved or provisional typing distinctly from canonical ontology typing.

Every canonical graph assertion must retain the ontology release/version used for its interpretation. Proposal status must never be represented as a published class or relationship.

## 9. Change impact and recalculation

Ontology publication is a semantic input change.

- Resolutor identifies affected annotations, entity classifications, graph assertions, indexes, and other derived artifacts using explicit dependency metadata.
- Recalculation is planned and executed under existing resource, resumability, and generation-promotion contracts.
- Historical results remain interpretable against their original ontology release.
- New derived generations pin the target ontology release.
- Promotion of recalculated knowledge remains explicit; publication of an ontology release does not silently activate partially recalculated graph/search projections.
- Impact analysis distinguishes additive changes from rename, deprecation, replacement, hierarchy, domain/range, and constraint changes.

The exact recalculation policy and any required human approval for high-impact ontology changes remain separate policy decisions.

## 10. Security, tenancy, and audit

- All proposal, review, edit, validation, and publication operations are server-authorized.
- Tenant/workspace scope is derived from authenticated context, never trusted from a client-supplied tenant field.
- Proposal evidence is visible only where the reviewer is authorized to access the underlying source.
- Model providers and strategies must follow data-residency, retention, and external-processing policy.
- Audit records capture actor, action, timestamp, scope, proposal/revision digest, base release, decision/rationale, validation run, and publication outcome.
- Reviewer permissions distinguish read, propose, edit, approve, publish, administer policy, and promote to shared scope.
- Separation of duties may be required for high-impact or shared-domain publication; the policy owner defines when.
- Disabling dynamic discovery or changing policy is itself an audited configuration change.
- Pending proposal deletion/retention follows tenant data-governance policy and must not erase required audit evidence.

## 11. API boundary (proposed contracts, not existing endpoints)

The public Platform API should expose contract-level operations equivalent to:

- Read effective ontology-governance policy and capabilities.
- Enable/disable dynamic discovery for an authorized scope.
- Create/list/get ontology proposals and immutable revisions.
- Edit a proposal by creating a new revision.
- Submit, request changes, approve, reject, defer, or withdraw a proposal.
- Run/read validation for a specific proposal revision.
- Publish an approved proposal against an expected base release.
- Read publication history, active release, and impact/recalculation status.

The browser must not call Syntology storage, Cassandra, Jena, graph databases, or internal services directly. These are capability requirements, not claims that the routes or protobuf messages already exist.

## 12. Initial rollout and acceptance gates

### Phase A — Governance contract

- Confirm ontology identity authority and stable-ID allocation.
- Define proposal/revision/review/publication schemas and state transitions.
- Define dynamic-discovery policy scope and authorization matrix.
- Define validation and publication concurrency semantics.

### Phase B — Human-governed proposals

- Persist model- and human-created proposals separately from published ontology releases.
- Implement review, edit, revision, reject, defer, and audit.
- Keep dynamic discovery OFF by default.
- Keep human approval mandatory and auto-publication disabled.

### Phase C — Validated publication

- Implement deterministic validation and compare-and-set publication.
- Emit publication events and preserve immutable release history.
- Add rollback/recovery semantics for publication failures.

### Phase D — Impact-aware evolution

- Integrate ontology release dependencies with Resolutor.
- Implement selective recalculation and explicit downstream generation promotion.
- Add tenant-local to shared-domain promotion as a separately governed operation.

Acceptance requires proof that:
- Model output cannot mutate an active ontology.
- Dynamic discovery OFF stops new model proposals but leaves existing ontology reads and manual governance available.
- Every edit creates a new revision and invalidates prior approval.
- Approval is bound to an exact revision digest and base release.
- Stale proposals cannot publish.
- Invalid or unauthorized proposals cannot change the active release.
- Publication creates an immutable release and durable audit trail.
- Tenant-local proposals cannot modify shared ontology scope.
- Existing graph assertions retain their original ontology-release provenance.
- Ontology publication does not imply automatic activation of incomplete downstream recalculation.

## 13. Decisions still required

1. Canonical ontology identity and identifier allocation authority.
2. Organization/tenant/workspace policy inheritance and override rules.
3. Reviewer, approver, publisher, and shared-domain owner role matrix.
4. Which changes require separation of duties or additional review.
5. Validation profiles and affected-data validation scope.
6. Proposal/evidence retention and deletion policy.
7. Publication recovery and downstream recalculation approval policy.
8. Public API/protobuf contracts and event schema.

These decisions should be recorded as ADRs before implementation claims are made.

## 14. Architectural conclusion

Synanton should not choose between static ontologies and unconstrained model-generated ontologies. It should maintain governed, immutable ontology releases while allowing models and humans to propose semantic evolution through a reviewable, validated, auditable workflow.

> **Models discover. Humans govern. Validators enforce. Syntology publishes. Resolutor recalculates.**
