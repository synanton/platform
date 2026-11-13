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
