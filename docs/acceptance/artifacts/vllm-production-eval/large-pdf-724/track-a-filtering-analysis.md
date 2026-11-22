# Track A candidate-filtering analysis (#760)

**Scope:** The 20 reviewed DEV rows for the 104-page Google 2023 Environmental
Report. HOLDOUT was not read, counted, summarized, or used.
**Annotation provenance:** These are user-approved AI-assisted annotations, not
independently adjudicated human gold. The original candidate and evidence
records remain canonical.
**Status:** analysis only; no runtime, ontology, promotion, extraction, or
production change.

## Evidence and limitations

DEV-only counts:

| Measure | Count |
|---|---:|
| Reviewed DEV rows | 20 |
| Valid subjects | 9 |
| Valid objects | 11 |
| Correct predicates | 5 |
| Valid relations | 1 |

The one valid candidate is `cand-4d56f9d76d16`
(`TerrAdapt → Google Earth Engine`, `uses`).

### Structural pattern matches

| Pattern | DEV rows |
|---|---:|
| Exact non-entity endpoint | 12 |
| Control/heading endpoint | 1 |
| Any narrow structural match | 13 |

The non-entity matches are exact endpoint surfaces observed in DEV:
`Some`, `Our`, `This`, `Where`, `In`, `As`, `The`, `Together`, `Due`, and
`That`. The control match is `Climate Accord\n\nGoogle` (heading boundary
contamination). No DEV evidence supports a broader stopword, prefix, modifier,
or cross-reference rule.

All 13 structurally matched candidates are invalid as-is; none is the valid
relation. However, **all 13 have proposed boundary corrections**, so hard-
dropping them would discard recoverable intended facts.

The 7 survivors contain:

- 6 candidates already rejected by the existing ontology as unsupported
  `is_a`/`has` relations;
- the one clean positive, `TerrAdapt → Google Earth Engine`.

The sample is small and AI-assisted. It is not sufficient to establish
production precision/recall, generalization, or safe hard-filter thresholds.

## Recommendation

**Do not implement hard filtering at this stage.**

The only narrowly justified implementation candidate is a feature-flagged
**pre-validation abstention** for the exact, deterministic patterns supported by
DEV:

1. An endpoint surface exactly equal to one of the observed non-entity tokens:
   `some`, `our`, `this`, `where`, `in`, `as`, `the`, `together`, `due`, `that`.
2. An endpoint containing a control/heading boundary (newline or heading
   separator), as in `Climate Accord\n\nGoogle`.

The proposed behavior must:

- abstain before ontology validation/promotion, rather than delete or rewrite
  the candidate;
- preserve the original candidate, endpoint surfaces, source/evidence
  references, and the abstention reason for audit;
- remain disabled by default until separately implemented, tested, reviewed,
  and explicitly enabled;
- avoid treating proposed boundary corrections as accepted facts or applying
  them automatically.

This proposed abstention would remove invalid candidates before validation; it
would **not** increase the accepted-relation count, because those candidates are
already rejected by the ontology. It would reduce validation/noise and preserve
the evidence for review.

### Why broader rules are not justified

- **Boundary repair:** 13/13 structurally matched rows have proposed corrections;
  automatically rewriting them would turn draft suggestions into facts.
- **Modifier/truncated endpoint removal:** examples such as `DEFRA U` and
  `AI-based` are recoverable/uncertain and have no deterministic DEV rule.
- **Prefix/pronoun removal:** `Our consumer hardware` and `This category` are
  legitimate corrected noun phrases; exact-token abstention must not become a
  prefix rule.
- **Predicate filtering:** the 6 surviving invalid rows are already rejected by
  ontology; duplicating that check adds no acceptance power.
- **Endpoint-type filtering:** the clean positive is `Concept → Concept`; a
  generic type filter would remove it.

## Proposed test matrix for a later implementation PR

| Case | Expected behavior | Evidence status |
|---|---|---|
| Exact observed non-entity endpoint (`This`, `Our`, `As`, `Due`, `Together`) | Abstain with `NON_ENTITY_ENDPOINT`; preserve candidate/evidence | Supported by 12 DEV rows |
| Control/heading endpoint (`Climate Accord\n\nGoogle`) | Abstain with `CONTROL_OR_HEADING_ENDPOINT` | Supported by 1 DEV row |
| Ordinary valid endpoints (`TerrAdapt`, `Google Earth Engine`) | Pass unchanged to existing validation | Supported by clean positive |
| Identifier endpoint (`6.6.1`) | Pass; identifiers are not non-entity tokens | Supported by `cand-00dd...` |
| Ambiguous endpoint (`GRI Disclosure`, `Product`) | Pass to existing validation/review; never silently drop | Defensive; labels include AMBIGUOUS |
| Truncated/modifier endpoint (`DEFRA U`, `AI-based`) | Do not abstain under this slice; leave for review | Defensive; no deterministic rule |
| Unsupported predicate (`is_a`, `has`) | No new filter; existing ontology rejection remains | Supported by 6 survivors |
| Domain/range mismatch | No new filter; existing ontology contract remains | Defensive regression |
| Insufficient evidence | No structural rewrite; preserve for review | Defensive regression |
| Abstention audit | Candidate ID, surfaces, chunk/offsets, reason retained | Required |
| Feature flag disabled | Exact current behavior; no graph/list changes | Required |
| No automatic promotion/boundary rewrite | No accepted fact or rewritten endpoint | Required |

## Smallest follow-up implementation scope

If this proposal is accepted after review, the smallest testable slice is:

- an opt-in `EndpointAbstentionFilter` invoked before ontology validation;
- exact observed-token matching and control/heading detection only;
- a structured abstention reason plus candidate/evidence preservation;
- feature flag default `false`;
- no graph write, no promotion, no ontology or boundary mutation;
- tests covering the matrix above and reproduction of the 13 DEV abstentions and
  7 remaining candidates.

Acceptance would require the flag-off path to be byte-for-byte behaviorally
unchanged and the flag-on path to preserve all original candidate/evidence
records. No implementation issue is created here; that is a separate reviewed
step.

## Explicit boundaries

This analysis does not authorize:

- ontology vocabulary or domain/range changes;
- SHACL or validation relaxation;
- promotion-rule changes;
- automatic boundary correction or entity resolution;
- prompt, chunking, model, runtime, or provider-default changes;
- extraction configuration or production behavior changes;
- any use of HOLDOUT for rule discovery, threshold tuning, or validation.

HOLDOUT remains sealed and unread.
