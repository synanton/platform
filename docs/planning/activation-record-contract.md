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

Read paths resolve currentness from the activation record, never from projection contents alone.

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
| Failed search replacement cannot expose stale as current | #656 failure-model test | UNENFORCED (#656/#649b) |

## Change control

Changes require #654–#656 owners; re-run the conformance matrix.

## Change-control note 2026-11-13
#669 adopts the ADR-029 state names as canonical and records the coarse contract mapping. #654 still owns fence-guarded `ActivateRevision` wiring into ingestion; #655/#656 own reader enforcement.
