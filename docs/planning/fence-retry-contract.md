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

- Per-class max attempts are bounded by existing consumer configuration; wall-clock deadlines are DEFERRED (see conformance).
- Current backoff is fixed exponential; jitter is DEFERRED (see conformance).
- `promotion.exhausted` increments once per promotion operation when its retryable attempts reach the configured max; it is not incremented on `FENCE_BEHIND` while reconciliation is pending.
- Exhaustion is a recoverable state, distinguishable from terminal stale rejection.
- Every attempt carries the same operation identity; a newer generation makes the retry `STALE_GENERATION`, never a silent success.

## Metrics

Per source: terminal outcomes by class, retryable outcomes, exhausted retries, last authorized generation, last reconciliation sync/drift.

Implemented `promotion.outcome` labels: `PROMOTED`, `STALE_GENERATION`, `UNAUTHORIZED_GENERATION`, `CONTRACT_VIOLATION`, `FENCE_BEHIND`, `RETRYABLE`, `EXHAUSTED_RETRY`, `FAILED`. `ABORT_RECOVERABLE` remains UNENFORCED pending #666. `promotion.exhausted` is the operation-level exhausted counter.


Status vocabulary: `ENFORCED` = conformance test exists and passes with a test reference; `UNENFORCED` = in Phase-2b scope, test owed and must reach zero before #658; `DEFERRED` = outside Phase 2b, requiring rationale, owner, revisit trigger, and an exclusion acknowledgment; `DROPPED` = deliberately out of scope, with a decision date.

## Conformance

| Clause | Test | Status |
|---|---|---|
| Terminal outcomes are never retried | terminal-outcome-attempt-count test | UNENFORCED (#666) |
| Abort after fence check and staged writes leaves no graph evidence | `Neo4jGraphCanonicalPortIntegrationTest::abortedPromotionAfterFenceAndStagingLeavesNoEvidenceAndRetryWritesOnce` | ENFORCED |
| Newer generation before retry rejects the retry as stale | `Neo4jGraphCanonicalPortIntegrationTest::newerGenerationFenceAdvanceDuringRecoveryRejectsGenerationTwoRetry` | ENFORCED |
| Concurrent fence advance cannot outrun an in-flight generation-2 promotion | `Neo4jGraphCanonicalPortIntegrationTest::concurrentFenceAdvanceCannotOutrunInFlightGenerationTwoPromotion` | ENFORCED |
| Retryable max attempts are bounded | `PromotionConsumerTest::exhaustedRetryEmitsDistinctOutcome` | ENFORCED |
| Wall-clock retry deadline | none | DEFERRED: rationale=current max-attempt bound is sufficient for Phase 2b; owner=#667; revisit trigger=before #658; exclusion=#658 does not claim wall-clock deadlines |
| Fixed backoff is the Phase-2b policy; jitter | none | DEFERRED: rationale=fixed backoff avoids changing timing in this PR; owner=#667; revisit trigger=before #658; exclusion=#658 does not claim jitter |
| Exhausted retry is a distinct metric/outcome | `PromotionConsumerTest::exhaustedRetryEmitsDistinctOutcome` | ENFORCED |
| `promotion.exhausted` increments once per operation | `PromotionConsumerTest::exhaustedRetryEmitsDistinctOutcome` | ENFORCED |
| `FENCE_BEHIND` does not increment exhausted | `PromotionConsumerTest::fenceBehindEmitsRetryableOutcome` | ENFORCED |
| Idempotency key stable across attempts | `Neo4jGraphCanonicalPortIntegrationTest::abortedPromotionAfterFenceAndStagingLeavesNoEvidenceAndRetryWritesOnce` | ENFORCED |
| `ABORT_RECOVERABLE` classification/metric | none | UNENFORCED (#666) |

## Change control

Changes require review from owners of #666, #667, and #648; re-run the conformance matrix. Passing tests against an older contract do not authorize the new contract.


## Change-control note 2026-11-13
#667 narrows the implemented label set and defers wall-clock deadlines/jitter explicitly; #666 still owns terminal-outcome, idempotency-key, and abort-recovery conformance.

#666 adds the named post-staging/pre-commit abort checkpoint and real-store cleanup/idempotency observables; terminal-outcome attempt counting and ABORT_RECOVERABLE metrics remain UNENFORCED.

#666 concurrency follow-up: the promotion transaction now write-locks the fence before re-checking it, and the test asserts a concurrent generation-3 advance waits for the in-flight generation-2 transaction. The abort injection point is after the fence check and full staging, before commit; cleanup is asserted through real-store counts, not return values.
