# Fence retry contract

**Status:** Proposed — 2026-11-13
**Consumers:** #666 (abort/recovery), #667 (bounded retry/metrics), #648 runtime enforcement.

This is one contract for every promotion retry path. Do not implement separate loops in #666 and #667.

## Idempotency identity

A promotion is identified by its deterministic graph identities:

- assertion: `(tenant_id, canonical_relation_id)`;
- evidence: `(tenant_id, evidence_id)`.

Retries deduplicate on those identities. A failed Neo4j transaction commits nothing, so there is no partial reservation or claim row to clean up in the graph. Any future Cassandra claim/intent row must be named here before it is introduced.

## Classification

| Class | Retry | Notes |
|---|---|---|
| `STALE_GENERATION` | No | Fence is newer; terminal. |
| `UNAUTHORIZED_GENERATION` | No | Cassandra no longer accepts the operation generation; terminal. |
| `FENCE_BEHIND` | Yes, after reconciliation | Do not spin; wait for/trigger reconciliation. |
| `ABORT_RECOVERABLE` | Yes, immediate | Transaction aborted after fence check with no commit. |
| infrastructure/unknown | Yes, bounded | Existing `RetryableProcessingException` classification. |

## Bounds and backoff

- Per-class max attempts/deadline to be set by #667; defaults must be explicit configuration, not infinite.
- Exponential backoff with jitter.
- Exhausted retry is its own terminal outcome and metric; it must not look like a retryable `FENCE_BEHIND`.
- Every attempt carries the same operation identity; a newer generation makes the retry `STALE_GENERATION`, never a silent success.

## Metrics

Per source: terminal outcomes by class, retryable outcomes, exhausted retries, last authorized generation, last reconciliation sync/drift. Labels distinguish `STALE`, `UNAUTHORIZED`, `FENCE_BEHIND`, `ABORT_RECOVERABLE`, reconciliation failure, and exhausted retry.


Status vocabulary: `ENFORCED` = conformance test exists and passes; `UNENFORCED` = in Phase-2b scope, test owed and must reach zero before #658; `DEFERRED` = outside Phase 2b, requires a tracked Phase 3+ ticket.

## Conformance

| Clause | Test | Status |
|---|---|---|
| Terminal outcomes are never retried | terminal-outcome-attempt-count test | UNENFORCED (#675) |
| Retryable outcomes bounded by deadline/max attempts | forced-failure exhaustion test | UNENFORCED (#675) |
| Exhausted retry is a distinct metric/outcome | metric-label test | UNENFORCED (#675) |
| Idempotency key stable across attempts | same-key-one-write test | UNENFORCED (#675) |

## Change control

Changes require review from owners of #666, #667, and #648; re-run the conformance matrix. Passing tests against an older contract do not authorize the new contract.
