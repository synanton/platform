# Context Schema and Conflict Representation

**Status:** Draft for ADR review — not an accepted contract  
**Supports:** ONT-ADR-002 / repository ADR-031  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3 §6  
**Standards alignment:** See [Standards Compatibility and Interchange](./standards-compatibility.md).

## Purpose

Provide a machine-readable design input for ADR-031. Context is an independently versioned object; resolution records reference an immutable version or content hash. Context serialization must not be confused with the ontology's logical semantics.

## Candidate schema

```json
{
  "context_id": "ctx:...",
  "version": "immutable-version-or-content-hash",
  "dimensions": {
    "domains": [],
    "organization": null,
    "tenant": null,
    "source": null,
    "task": null,
    "environment": null,
    "time": null,
    "actor_role": null
  },
  "constraints": [],
  "assumptions": [],
  "assertions": [],
  "provenance": [],
  "status": "RESOLVED"
}
```

This is illustrative, not a frozen API schema. Sensitive values and tenant authorization data must not be copied into semantic context unless explicitly permitted by the security contract.

JSON-LD 1.1 is a candidate interchange representation when context needs to link to RDF resources. It is not mandatory for internal API payloads. If JSON-LD is used, the JSON-LD context document and all remote context dependencies must be pinned or locally controlled; fetching a mutable remote context at runtime is not acceptable.

## Context assertions and conflict representation

Context is composed from assertions, not a mutable bag of values. Conflicting context claims are retained as separate assertions with:

- assertion identity and version;
- dimension and value (or referenced semantic resource);
- source/authority and asserted-by agent;
- validity interval and observation/recording time where known;
- confidence or epistemic status where applicable;
- provenance and evidence reference;
- conflict-set reference and resolution status.

A conflict must not be silently resolved by overwriting one assertion. Distinguish at least:
- **inconsistency:** claims cannot simultaneously hold under the selected model;
- **disagreement:** sources make different claims without enough information to prove contradiction;
- **underspecification:** required context is absent;
- **staleness:** a claim is outside its validity or freshness policy;
- **policy conflict:** individually valid claims conflict under a selected precedence/authorization policy.

## Evaluation and precedence

ADR-031 must decide:

- how constraints are evaluated (hard constraints versus soft preferences);
- whether precedence is global, per-domain or per-task;
- how incomparable authorities are handled;
- whether resolution can return multiple candidate contexts or abstain;
- how time, validity intervals and observation time participate in evaluation;
- how conflict status and resolution decisions are versioned and audited.

No universal precedence order is selected by this draft. Confidence is not authority, and a numeric score must not silently override source governance or a hard constraint.

## Provenance representation

PROV-O is a candidate vocabulary for interoperable provenance of context assertions and resolution activities:

- assertions and immutable context snapshots can be represented as provenance entities;
- context composition and resolution can be represented as activities;
- source systems, people and software agents can be represented as agents.

PROV-O supplements, rather than replaces, Synanton's domain-specific assertion identifiers, security controls, validity model and provenance contract. Provenance records must not expose protected source content or tenant secrets.

## Resolution references

Every concept-resolution record pins:

- context ID and immutable version/hash;
- context composition inputs and their versions;
- conflict-set IDs and selected resolution, if any;
- policy version and implementation version;
- ontology release/import closure used;
- evidence and provenance references;
- outcome, including ambiguity or abstention.

## Acceptance

A context schema, conflict model, precedence rules, validation semantics, provenance mapping and version-reference contract are accepted only through ADR-031 and associated tests.
