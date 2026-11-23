---
title: "Syntology Architecture"
status: "current"
last_reviewed: "2026-11-23"
---

# Syntology — Ontology Management and Governance

**Purpose:** Architecture and design for the Syntology ontology management, governance, and semantic-evolution domain.  
**Audience:** Architects, Syntology module owners, platform engineers, and UI/API owners  
**Last Updated:** 2026-11-23

## Architecture documents

| Document | Status | Description |
|---|---|---|
| [ontology-governance.md](./ontology-governance.md) | Proposed | Model-assisted discovery, human review/editing, deterministic validation, immutable publication, and impact-aware evolution |
| [ontology-management.md](./ontology-management.md) | Historical demo design | v1.17 standalone demo scope; not the production governance specification |
| [../../../schemas/ontology/README.md](../../../schemas/ontology/README.md) | Schema contract | HCL schema-as-code (JSON IR → SHACL + OWL TBox), includes, Admin API load |
| [../proposals/ontology-domain-mapping-v0.3.md](../proposals/ontology-domain-mapping-v0.3.md) | Approved baseline; specification open | Domain ownership, concept identity, contextual mapping, candidate/governed separation, and mapping governance |

## Normative boundaries

- The governance proposal is a design target, not an implementation claim.
- Syntology manages ontology definitions, proposal workflow, validation, release publication, and ontology-facing APIs; canonical identity authority must be resolved explicitly rather than inferred from model output.
- Models may generate proposals but never mutate the active ontology directly.
- Human approval is mandatory in the initial operating mode; automatic publication is disabled.
- Published ontology releases are immutable. Changes create a new release.
- Ontology publication and downstream graph/search/vector recalculation are separate lifecycle operations.
- Domain-mapping v0.3 remains an immutable approved baseline. This governance proposal complements it and does not silently amend it.

## How to contribute

Changes to ontology governance should update this proposal and the current platform architecture (../synanton-design-1.25.md). UI interaction and accessibility requirements belong in the [Synanton UI Knowledge Discovery proposal](https://github.com/synanton/ui/blob/main/docs/architecture/knowledge-discovery-ui-proposal.md). Implementation status must be recorded separately from proposed architecture.
