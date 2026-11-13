# Activation record and projection recovery contract

**Status:** Draft — required artifact for #669/#654–#656.

Cassandra is authoritative for the activation record per `(source, revision, generation)`.

## State machine

`STAGED → VALIDATED → ACTIVATING → ACTIVE → RETIRING → RETIRED`, plus `FAILED`.

Transitions are idempotent and guarded by expected generation/state; no transition may lower a generation or activate an older revision over a newer fence.

## Projections

Graph and search are projections of the activation record. Each carries an applied-state marker (`applied_generation`, `applied_state`).

Read paths resolve currentness from the activation record, never from projection contents alone.

## Recovery

Recovery replays projections to match the activation record. It does not reconcile two stores that each claim authority.

For #656 failure semantics, if graph activation commits and search replacement fails, reads must not present stale search content as current; the read boundary serves the graph projection or hides the source's search results until search replay reaches the activation record. The chosen rule must be explicit and observable.


Status vocabulary: `ENFORCED` = conformance test exists and passes; `UNENFORCED` = in Phase-2b scope, test owed and must reach zero before #658; `DEFERRED` = outside Phase 2b, requires a tracked Phase 3+ ticket.

## Conformance

| Clause | Test | Status |
|---|---|---|
| Allowed transitions are idempotent under replay | transition replay test per state | UNENFORCED (#675) |
| Disallowed transitions are rejected | negative transition test | UNENFORCED (#675) |
| Interrupted transition recovers to defined state | injection/recovery test | UNENFORCED (#675) |
| Reads resolve from activation record, not projections | projection-marker test | UNENFORCED (#675) |
| Failed search replacement cannot expose stale as current | #656 failure-model test | UNENFORCED (#656/#649b) |

## Change control

Changes require #654–#656 owners; re-run the conformance matrix.
