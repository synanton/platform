# Context Schema and Conflict Representation

**Status:** Draft for ADR review — not an accepted contract  
**Supports:** ONT-ADR-002 / repository ADR-031  
**Baseline:** Domain Ontologies and Cross-Domain Semantic Mapping v0.3 §6

## Purpose

Provide a machine-readable design input for ADR-031. Context is an independently versioned object; resolution records reference an immutable version or content hash.

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

## Conflict representation

Conflicting context claims are retained as separate assertions with:
- assertion identity and version;
- dimension and value;
- source/authority;
- validity interval;
- confidence or epistemic status where applicable;
- provenance;
- conflict-set reference and resolution status.

A conflict must not be silently resolved by overwriting one assertion.

## Evaluation and precedence

ADR-031 must decide:
- how constraints are evaluated (hard constraints versus soft preferences);
- whether precedence is global, per-domain or per-task;
- how incomparable authorities are handled;
- whether resolution can return multiple candidate contexts or abstain;
- how time and validity intervals participate in evaluation.

No universal precedence order is selected by this draft.

## Resolution references

Every concept-resolution record pins:
- context ID and immutable version/hash;
- context composition inputs;
- conflict-set IDs and selected resolution, if any;
- policy version;
- provenance.

## Acceptance

A context schema, conflict model, precedence rules, validation semantics and version-reference contract are accepted only through ADR-031 and associated tests.
