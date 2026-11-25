# P0-driven extraction prompt hydration

**Scope:** hydrate the Gateway extraction prompt from the pinned P0 SPEC/template.
**Baseline:** #919 artifacts and metrics remain unchanged; this document describes the next
implementation contract.

## Contract loader

`P0ContractLoader` reads the immutable P0 artifacts:

- `docs/implementation/it-books/p0/it-books.spec.json`
- `docs/implementation/it-books/p0/it-books.template.json`
- `docs/implementation/it-books/p0/it-books.report.json`

It emits `CompiledExtractionContract` containing:

- release ID/digest;
- compiler version, export profile, schema version;
- SPEC/template/compilation digests;
- a canonical `contractDigest` over the SPEC + template;
- all extraction entity IDs/labels and all relation IDs, labels, subject/object expansions, and status.

The report is the authority for spec/template/compilation digests because the published SPEC and
template carry release provenance but not their own digest fields.

## Prompt hydration

Gateway extraction now receives contract-derived values for:

- allowed entity types;
- allowed predicates;
- subject/predicate/object relation lines;
- release, compiler, schema, and contract digest identity.

The prompt includes an explicit compiled-contract block. The prompt digest therefore changes when
the compiled contract changes. No CSO context is injected by this change.

## Validation and unsupported terms

The adapter uses the hydrated predicate set and entity set:

- unknown entity types are quarantined as `UNKNOWN_ENTITY_TYPE`;
- unknown predicates are quarantined as `UNKNOWN_PREDICATE`;
- relation endpoints outside the compiled subject/object sets are quarantined as `DOMAIN_RANGE`.

Unsupported output is visible in the extraction result; it is never normalized into an allowed
predicate/type.

## Tests

- `P0ContractHydrationTest` loads the real P0 artifacts, asserts 11 extraction types / 8 relations,
  verifies prompt release/identity and relation expansion, and rejects unknown predicate and
  domain/range violations.
- `GpuSemanticEvaluation` now loads the compiled contract and records its digest in the run
  manifest; it has not been rerun, so #919 baseline artifacts remain untouched.

## Boundaries

No A2, IT Books v1, CSO, graph state, evaluation metrics, P2, Docling, or F1/gold/gate/profile/
ADR-044 change is included.
