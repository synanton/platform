# Replica RLS topology

**Status:** Proposed 2026-10-25 for #339.
**Scope:** read replicas for the relational content cache; primary remains the
write/audit path.

## Decision

- Replica connections use the **same application role** as the primary, with
  `FORCE ROW LEVEL SECURITY` on the tenant-scoped tables. No owner, superuser,
  or `BYPASSRLS` role for reads.
- Every replica read transaction sets `app.tenant_id` with the same
  transaction-local form used on the primary (`set_config(..., true)` or
  `SET LOCAL`). A replica pool that omits the setter fails closed with zero
  rows; it must not fall back to a session-level setting.
- The tenant-scoped repository/facade is unchanged: `TenantScopedContentStore`
  points at the replica `DataSource` for reads. There is no replica-specific
  query path with different tenant filtering.
- Cross-tenant reconcile writes audit rows. A read-only replica cannot accept
  those writes, so reconcile runs with its read connection(s) on the replica
  only if the audit sink is separately writable to the primary; this design does
  not claim that split. The first implementation keeps reconcile on the primary.
  The replica path is validated for single-tenant reads and RLS activation.

## Consistency

Replica lag is a consistency property, not an isolation property. The final
#339 acceptance reads from the **primary**. Replica parity is validated by its
own parameterized test; it does not inherit the primary acceptance's result and
is not a read-your-writes guarantee.

If a future acceptance reads from a replica, it must include an explicit
"wait for replication" step and record the lag bound. That is not part of this
slice.

## Replica parity test

`ReplicaReadParityTest` is env-gated and visibly skipped when no replica is
configured. With `REPLICA_PARITY_PRIMARY_URL` and
`REPLICA_PARITY_REPLICA_URL` set, it:

1. writes a known artifact/tenant on the primary;
2. waits for the replica to expose it;
3. asserts the known tenant count is **non-zero on the replica** (the setter and
   role work; a missing setter would return zero);
4. asserts an unknown tenant sees **zero rows** for the same digest (RLS is
   active; a bypass role would see the row);
5. asserts primary and replica single-tenant counts match.

Cross-tenant reconcile with audit is not run against the read-only replica; that
would require the separate audit-sink split above.

## Risks pinned

| Risk | Control |
|---|---|
| Replica pool missing setter | Known-data non-zero test; fail-closed zero is not accepted as parity |
| Owner/bypass role on replica | Same app role + FORCE RLS; unknown-tenant zero test |
| Replica lag mistaken for isolation | Acceptance reads primary; replica test records lag wait |
| Cross-tenant reconcile on read-only replica | Not claimed; audit write path stays primary |
