# YDB-041-SLOW — Code-Quality Checklist (Transaction Scope)

Companion to `041-bulk-upsert.md` (performance half). This document is the
code-quality half: fix the shape, then measure the shape, then change the
shape if the numbers say so.

## Framing: transaction scope, not merely collections

A proper batch has one transaction scope:

```text
begin transaction
    write item 1
    write item 2
    ...
    write item N
commit
```

Not N single-element transactions behind a `List` parameter. The abstraction:

```java
void writeBatch(List<Revision> revisions) {
    try (Transaction tx = beginTransaction()) {
        for (Revision r : revisions) writeRevision(tx, r);
        tx.commit();
    }
}
// writeRevision(tx, ...) must never create its own transaction.
```

Architectural rule: transaction scope belongs to the operation that defines
atomicity, not to the lowest-level repository method. Three shapes:

- one transaction + N statements — legitimate batch, possibly inefficient;
- one transaction + one bulk statement — more efficient batch;
- N transactions + N statements — not a batch semantically.

First code-review question for any batch: where exactly is the transaction
opened and committed relative to the N-element batch? If inside the
per-element method, that is an architecture problem before a performance one.

## Reconciliation with current 041 state

041's implementation already satisfies most of this — the checklist locks the
contract so it cannot regress:

| Item | Current 041 state | Action |
|---|---|---|
| Batch = one transaction | ✅ (commit_count == 1 asserted) | Lock with test |
| Per-item method doesn't own tx | ⚠️ Not verified in review | Explicit API split |
| Atomic rollback | ✅ Equivalence + failure tests | Keep |
| YDB query count measured | ❌ Not tracked | Add to build.json |
| Bulk-vs-per-item benchmarked | ❌ Not done | New — architectural pivot |
| Batch-size sweep 1/10/100/1000 | 🟡 Partial (single-row ref) | Extend to full matrix |
| Regression test for tx scope | ⚠️ Implicit | Explicit test |
| Documented atomicity contract | 🟡 Implicit in code | Per-path javadoc |

## Correctness / architecture (hard gates)

1. **Batch is the transaction boundary, in the type signature.**
   `writeBatch` opens one transaction for the whole batch; `writeRow(tx, row)`
   receives it and never opens its own. Moves atomicity from implementation
   detail to API — a future contributor cannot silently regress it.
2. **Document per-method transaction ownership.** Every write method declares:
   starts a transaction / participates in an existing one / executes outside
   any transaction. Implicit ownership is how the batch question got ambiguous.
3. **Batch failure rolls back the whole batch (named regression test).**
   Write A → write B → fail on C ⇒ neither A nor B persisted.
4. **Batch count vs transaction count test.**
   `assertEquals(1, transactionCount()); assertEquals(batchSize, rowsWritten());`
   Catches a future `writeBatch` degenerating into a loop over single-item writes.

## Performance / optimization (soft, benchmarked)

5. **Transaction + query count per batch in build.json:**
   `{"batch_size": 100, "transactions": 1, "queries": N, "commit_ms": ...}`.
   Makes "is it actually a batch?" visible without reading code.
6. **Benchmark matrix 1 / 10 / 100 / 1000** × current YQL path (and BulkUpsert
   candidate when evaluated).
7. **Side-by-side shapes:** one tx + N statements (current) vs one tx + one
   bulk statement (candidate). The resulting number is the deliverable.
8. **Index construction moved to post-load.** Separate from the write-API
   question; compounds with it. Readiness gate covers both steps.

## API split (reconciliation outcome)

- `writeBatchLoad` — bulkUpsert path, no ordering guard; for initial loads.
- `writeBatchUpdate` — YQL path with guard; for updates.
- Both honor one-batch-one-boundary. If bulkUpsert cannot participate in a
  transaction, the load path's atomicity contract changes — stated explicitly
  in the API, never implicit in the call choice.

## Required benchmark report (Phase 4 close)

```text
Code Quality Benchmark: YDB Batch

Transaction correctness        PASS
Atomic rollback                PASS
Transaction count              1 / batch
Query count                    N / batch
Single-item regression         PASS (unchanged)
Batch-size throughput: 1 / 10 / 100 / 1000 ...
Architecture:
  tx boundary explicit        PASS / FAIL
  per-item owns tx            NONE
  batch is loop-over-single   NO
```

Hard gates (no compensation by points elsewhere): batch of N creating N
transactions fails; partial commit under required atomicity fails; per-item
method silently owning the transaction fails; batch-as-loop-over-single fails;
implicit transaction boundaries fail.

## Acceptance criteria

- [ ] Batch operation owns exactly one transaction (load path + update path)
- [ ] Per-item write method cannot create its own transaction (type-enforced)
- [ ] Batch failure rolls back the whole batch (test)
- [ ] Transaction count == 1 per batch, asserted in test
- [ ] Query count per batch measured and recorded in build.json
- [ ] Batch-size throughput measured for 1/10/100/1000
- [ ] Current YQL path vs BulkUpsert path benchmarked side by side
- [ ] Index construction moved to post-load; readiness gate covers both steps
- [ ] No regression: single-item write path unchanged
- [ ] Atomicity contract documented per path (load = ?, update = serializable)

The load-bearing item is the side-by-side benchmark: it determines whether
the architectural split is worth its cost.
