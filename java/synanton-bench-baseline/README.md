# synanton-bench-baseline (028b)

Multi-tenant baseline harness + Q3 emitter over the v1 corpus. Spec: corpus
contract `028a-format-spec-corpus.md`; tasks `028b-tasks.md`.

## BaselineBench retirement decision (028b.1)

`BaselineBench` (`java/synquest` test sources) is **left in place,
deprecated-with-notice** for v1 work — not deleted, not archived:

- It remains the provenance of the (now void) 006 thresholds and stays
  runnable for scale-trend reference.
- It is superseded by this module for everything 028-gated. No new baseline
  work lands there; a comment at its head points here.

Rationale: deleting a working harness destroys the only runnable baseline
while its replacement is unproven. Deprecation preserves the fallback until
028b.8 produces `baseline-v1.json`, at which point deletion may be proposed
as separate cleanup — never bundled with feature work.

## Note for 028b.5

This module is the baseline leg of the 4-leg run: its Q3 output must pass
`EmitterContractTest` before the run commits, same as 028c/d/e. A baseline
schema mismatch surfacing at R3 costs a full re-run chain — the contract
test exists to catch it here.
