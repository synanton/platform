# A8 v1 — Deterministic entity resolution

**Issue:** #893 Workstream A / A8
**Status:** Library and tests implemented; not wired into production processing
**Predecessor:** A4 baseline (fixed `8c9c3a2b…`) and A4.1 inspection findings

## Strategy

`DeterministicEntityResolver` implements only deterministic normalization, exact canonical-label
matching, and explicit-alias matching. It deliberately does **not** use fuzzy, embedding, phonetic,
or edit-distance assignment.

Normalization (`ResolutionText.normalize`):

- Unicode NFC;
- trim leading/trailing whitespace;
- lower-case with `Locale.ROOT`;
- collapse internal whitespace.

Punctuation is not stripped, because stripping can turn distinct labels into accidental matches.
NFC, case, and whitespace normalization are applied identically to catalog labels, aliases, and
incoming mention surfaces.

### Deterministic collision rules

1. A canonical label match takes precedence over an alias match.
2. Multiple canonical labels for the same normalized surface are `AMBIGUOUS`.
3. Multiple alias targets are `AMBIGUOUS`, independent of catalog insertion order.
4. Duplicate normalized aliases on the same concept are deduplicated, not treated as ambiguity.
5. A canonical label or alias match with a different proposed type is `TYPE_MISMATCH`; it is not
   silently resolved.

## Outcomes

| Status | Meaning |
|---|---|
| `RESOLVED` | One canonical label or alias matched, and the proposed type matches |
| `UNRESOLVED` | No exact label or alias; metadata and near-miss text stay here |
| `AMBIGUOUS` | Multiple canonical labels/aliases match; identity is not forced |
| `TYPE_MISMATCH` | A label/alias matched, but the proposed type disagrees with the canonical type |

`TYPE_MISMATCH` retains the candidate canonical ID for diagnosis but is not a resolved identity.

## Provenance

Each `ResolutionResult` carries:

- source mention ID;
- normalized surface;
- proposed type;
- status and reason;
- matched canonical ID/label when available;
- match method (`EXACT_LABEL`, `ALIAS`, `NONE`);
- strategy version;
- catalog version and deterministic catalog digest.

Mention identity remains separate from canonical identity. A mention can be unresolved or
type-mismatched without being assigned a canonical concept.

## Canonical catalog

The resolver accepts an immutable `CanonicalCatalog` of `CanonicalConcept` records. The catalog
digest is SHA-256 over sorted canonical IDs, normalized labels, types, and sorted aliases. It is
stable across input ordering.

No A2 gold annotations or A4 output are used to construct the resolver catalog.

## Test evidence

`DeterministicEntityResolverTest` covers:

- exact normalized label and mention identity preservation;
- alias resolution;
- explicit type mismatch;
- metadata/license and near-miss hard negatives (`Data Structures`, `Authors`);
- ambiguous alias (`Model` → two concepts) remaining unresolved;
- catalog digest stability across concept order, plus digest sensitivity to label/type/alias changes;
- alias collisions independent of catalog order;
- canonical-label precedence over an alias collision;
- duplicate normalized aliases on one concept;
- NFC equivalence on catalog and mention input; punctuation is not stripped;
- independent resolution precision/recall: 4 resolved/correct, 0 incorrect, 4 unresolved,
  precision 1.0 and recall 1.0 on the small synthetic fixture.

Run:

```bash
./gradlew :java:synflux:test \
  --tests 'org.synanton.synflux.resolution.DeterministicEntityResolverTest'
```

## Boundaries

- No fuzzy or embedding resolution.
- No canonical identity assignment for metadata/generic phrases.
- No graph writes, A2 changes, ontology-release changes, A4 metric changes, or A7 run.
- Not yet wired into the processing pipeline; integration and A4 comparison are separate work.
