# Local GPU inference profile — architectural intent

**Status:** intent only; no local runtime changes or model calls.
**Separation:** local GPU and GPU-7 external are independent profiles; no automatic fallback.

## Responsibility split

| Responsibility | Component |
|---|---|
| Semantic proposals, typing, predicates, ambiguity | Local GPU model |
| Surface anchoring, offsets, token budgets, schema/endpoint checks | Deterministic code |
| IDs, hashes, deduplication, provenance | Deterministic code |

## Contract

- Models emit `ModelProposal` only: surface text, proposed type, context,
  predicate, endpoint surfaces and evidence text. No offsets, IDs, hashes or
  coordinates are requested or trusted.
- `validate_proposal` converts only accepted proposals into
  `ValidatedExtraction`; model proposals cannot enter graph persistence or
  canonical resolution directly.
- Ambiguous/missing surfaces and unsupported endpoints are rejected with
  structured reasons and retained for audit.
- Local GPU must be selected explicitly; external GPU-7 is optional, never an
  automatic fallback.

No inference, spend, HOLDOUT access or production change is authorized.
