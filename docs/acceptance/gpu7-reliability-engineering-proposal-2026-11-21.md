# GPU-7 reliability engineering proposal

**Status:** proposal + offline tests only.
**No model calls, spend, HOLDOUT access, or production changes.**
**Baseline evidence:** `evaluation/dev25/phase2-runs/failure-analysis-2026-11-21.md`.

## 1. Objective

Address the two dominant failures in the DeepSeek 25-document run without
changing acceptance criteria or making a model-ranking claim:

- 82/202 JSON responses were truncated at `max_tokens=8192`.
- Raw relation proposals were dominated by unsupported/endpoint mismatches
  (2,467 of 3,032 raw triples), not just predicate errors.

## 2. Workstream A — chunk-aware sizing and output budgets

### Change

Add a deterministic sizing preflight:

- target ~4,000–6,000 characters per chunk for dense technical documents,
  instead of the current 12,000;
- reserve prompt and completion headroom against the provider context;
- cap output at a measured budget and split further when estimated output
  exceeds it;
- record per-chunk `chars`, estimated tokens, `max_tokens`, and stop reason.

### Expected effect

Truncation should drop sharply because the 82 failures all ended with
`finish_reason=length`, but total call count and cost will rise.

### Offline tests

`estimate_chunks` boundary tests: target size, overlap, final chunk and
zero/short input. Implementation: `evaluation/dev25/offline_validation.py`.

## 3. Workstream B — two-stage extraction

### Change

1. Stage 1: entities/mentions only, using the allowed-type enum and exact spans.
2. Stage 2: relationships only over accepted Stage 1 mentions, with allowed
   predicate enums, explicit direction and evidence quotes.
3. Reject relations whose endpoints are not accepted mentions.
4. If the provider cannot enforce the schema, record the capability gap and stop;
   do not silently change task semantics.

### Expected effect

Should reduce unsupported endpoints and predicate drift. Recall may fall if the
second stage is overly strict; measure precision and recall separately.

### Offline tests

`reject_unsupported_relations` and `validate_relations` cover missing endpoints,
bad predicates, self-relations and invalid evidence spans. Tests include all 25
gold v1.0 documents.

## 4. Workstream C — evidence-grounded endpoint validation

### Change

Add a pure pre-scoring gate:

- normalize surfaces and predicates;
- require endpoints to exist in the entity set;
- validate evidence spans against the source text;
- count rejected relations by reason;
- never silently rewrite a relation to make it pass.

### Expected effect

Removes unsupported triples from precision calculations and makes failure modes
auditable. It will not create missing gold relations.

### Offline tests

`validate_entities`, `validate_relations`, `reject_unsupported_relations`;
regression cases: wrong span, bad type, missing endpoint, wrong predicate,
duplicate mention, direction reversal.

## 5. Regression tests against DEV gold v1.0

Current offline result:

```bash
cd evaluation/dev25
python3 -m unittest -v test_offline_validation.py
```

**7 tests pass**, including validation of all 25 gold documents. This checks
schema/offset/evidence/endpoint integrity only; it does not measure model quality.

## 6. Expected quality effects (hypotheses, not results)

| Change | JSON validity | Relation precision | Relation recall | Cost/latency |
|---|---|---|---|---|
| Smaller chunks | Up | Neutral | Neutral/slightly down | Up calls |
| Two-stage extraction | Up | Up | Down or up | Up |
| Endpoint filter | Neutral | Up | Down | Neutral |
| Schema-constrained output | Up | Up | Neutral | Provider-dependent |

## 7. Risks

- Over-filtering may improve precision at the cost of recall.
- Cross-chunk relations are lost unless a cross-chunk merge stage is designed.
- Larger call counts increase spend and tail latency.
- Provider schema support may differ; a capability gap must fail closed.
- Gold v1.0 has partial PDF coverage; improvements may not generalize to books.
- No ranking or acceptance follows from these engineering changes.

## 8. Proposed acceptance for a future, separately authorized experiment

Unchanged thresholds from the Phase 2 registration:

- JSON/schema validity ≥ 98%
- Entity F1 ≥ 85%
- Relation F1 ≥ 80%
- Evidence precision ≥ 95%
- Relationship direction accuracy ≥ 95%

Additional engineering gates:

- zero unsupported-endpoint relations in accepted output;
- every accepted relation has a source evidence span;
- all 25 gold documents used identically for both candidates;
- full per-call provenance (request, doc, chunk, model, raw response);
- no candidate ranking until both candidates complete.

## 9. Execution boundary

No model calls are authorized by this proposal. Any experiment must name the
provider/model, chunk policy, output schema, spend cap, window and stop
conditions in a separate registration. HOLDOUT and production remain untouched.
