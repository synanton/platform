# Activation record and projection recovery contract

**Status:** Draft — required artifact for #669/#654–#656.

Cassandra is authoritative for the activation record per `(source, revision, generation)`.

## State machine

Canonical states follow ADR-029:

```text
REVISION_ACCEPTED → EXTRACTING → EXTRACTION_COMPLETE
  → GRAPH_COMMITTING → GRAPH_COMMITTED
  → SEARCH_COMMITTING → SEARCH_COMMITTED
  → ACTIVATING → CURRENT → RETIRING → RETIRED
```

Failure states: `EXTRACTION_FAILED`, `EXTRACTION_PARTIAL`, `GRAPH_COMMIT_FAILED`, `SEARCH_COMMIT_FAILED`, `STALE_REJECTED`, `ACTIVATION_PARTIAL`, `FAILED`. Coarse contract terms map as: STAGED=`REVISION_ACCEPTED`, VALIDATED=`EXTRACTION_COMPLETE`/`GRAPH_COMMITTED`, ACTIVE=`CURRENT`.

Transitions are idempotent and guarded by expected generation/state; no transition may lower a generation or activate an older revision over a newer fence.

## Projections

Graph and search are projections of the activation record. Each carries an applied-state marker (`applied_generation`, `applied_state`).

Read paths resolve currentness from the activation record, never from projection contents alone. `SourceLifecycleRecord` is the per-request snapshot shape: resolve it once per request and pass the same record to graph and search; do not call `getCurrentGeneration` separately per subsystem.

## Recovery

Recovery replays projections to match the activation record. It does not reconcile two stores that each claim authority.

For #656 failure semantics, if graph activation commits and search replacement fails, reads must not present stale search content as current; the read boundary serves the graph projection or hides the source's search results until search replay reaches the activation record. The chosen rule must be explicit and observable.


Status vocabulary: `ENFORCED` = conformance test exists and passes with a test reference; `UNENFORCED` = in Phase-2b scope, test owed and must reach zero before #658; `DEFERRED` = outside Phase 2b, requiring rationale, owner, revisit trigger, and exclusion acknowledgment; `DROPPED` = deliberately out of scope, with a decision date.

## Conformance

| Clause | Test | Status |
|---|---|---|
| Staging is idempotent and does not change current | `CassandraSourceActivationStoreTest::stageIsIdempotentAndDoesNotChangeCurrent` | ENFORCED |
| Allowed transitions activate after graph and search markers | `CassandraSourceActivationStoreTest::committedProjectionsActivateAndReplayIdempotently` | ENFORCED |
| Disallowed transitions are rejected | `CassandraSourceActivationStoreTest::disallowedAndStaleTransitionsDoNotMutate` | ENFORCED |
| Older generation cannot replace newer current | `CassandraSourceActivationStoreTest::olderGenerationCannotReplaceNewerCurrent` | ENFORCED |
| Interrupted transition recovers to defined state | `CassandraSourceActivationStoreTest::crashAfterCurrentFlipRecoversFromSearchCommitted` | ENFORCED |
| `ACTIVATION_PARTIAL` is repairable without resetting generation | `CassandraSourceActivationStoreTest::activationPartialCanBeRepairedWithoutResettingGeneration` | ENFORCED |
| Reads resolve from activation record, not projections | `CassandraSourceActivationStoreTest::stageIsIdempotentAndDoesNotChangeCurrent` | ENFORCED (record only; reader wiring #655/#656) |
| Activation requires a matching fence | `ActivateRevisionIntegrationTest::activationRequiresSearchCommitThenIsIdempotent` | ENFORCED |
| Activation of an older generation is rejected after fence advance | `ActivateRevisionIntegrationTest::fenceAdvancedPastGenerationRejectsActivationWithoutChangingCurrent` | ENFORCED |
| `ActivateRevision` is idempotent | `ActivateRevisionIntegrationTest::activationRequiresSearchCommitThenIsIdempotent` | ENFORCED |
| Crash after current flip recovers from `SEARCH_COMMITTED` | `ActivateRevisionIntegrationTest::searchCommittedCrashRecoversByRepairingCurrentPointer` | ENFORCED |
| Activation failure is `ACTIVATION_PARTIAL` and repairable | `ActivateRevisionIntegrationTest::activationFailureIsPartialAndRepairable` | ENFORCED |
| Failed search replacement cannot expose stale as current | #656 failure-model test | UNENFORCED (#656/#649b) |

Deferred register: none. Every current activation clause is Phase-2b scope; the only UNENFORCED row is owned by #656/#649b.

## Change control

Changes require #654–#656 owners; re-run the conformance matrix.

## Change-control note 2026-11-13
#669 adopts the ADR-029 state names as canonical and records the coarse contract mapping. #654 adds the fence-guarded `ActivateRevision` core; staged ingestion wiring and #655/#656 reader enforcement remain open.
