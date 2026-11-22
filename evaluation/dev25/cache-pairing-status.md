# Cache isolation and paired reporting status

**Status:** offline implementation; no inference or spend.

- `eval_cache.py` keys entries by tenant, arm, source hash, ontology snapshot
  hash and retrieval version. Arm A rejects ontology keys; Arm B requires a
  snapshot. Cross-tenant/arm/stale-snapshot lookups miss; invalidations and
  rejected mismatches are counted.
- `evaluation_reporting.py` builds per-document/chunk/arm records with identity,
  hashes, metrics, endpoint/evidence rejections, schema/hallucination fields,
  latency, GPU telemetry and explicit eligibility.
- Three endpoint rejection reasons are always preserved, including zero counts.
- `paired_report` keeps incomplete/mismatched pairs visible and emits
  deterministic per-pair deltas; no ranking.
- 12 new tests pass; full stack suite passes.
