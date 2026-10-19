# Vertical slice — trust bar

**Status:** Active · **Applies to:** #253 and its dependents
**Last reviewed:** 2026-10-19

The vertical slice is **done** when its acceptance criteria pass. It is
**trustworthy** when a developer who was not involved can verify it works,
understand what it does not do, and diagnose it when it breaks.

"Done" is #253's closure state. "Trustworthy" is this document's subject.

## The five questions

A slice that answers all five is trustworthy. A slice that answers four is
done but not yet trustworthy — the unanswered question is the risk.

| # | Question | Answering it means |
|---|---|---|
| 1 | **Can someone else reproduce it?** | Evidence integrity — the pass is not machine-specific, secret-dependent, or time-bound |
| 2 | **Do the tests prove what they claim?** | Test integrity — no test asserts against a shape production never produces |
| 3 | **Does it survive beyond the happy path?** | Behavioural coverage — at least one failure scenario per component boundary |
| 4 | **Can you diagnose it when it fails?** | Observability — a documented diagnosis order, not manual forensics |
| 5 | **Are the known gaps bounded and documented?** | Honesty — every gap has a trigger, an owner, and a resolution criterion |

## Current state

| Question | State | Gap |
|---|---|---|
| 1 Reproducibility | **Partial** | Repeatability gate passed twice, both on developer machines. No independent run |
| 2 Test integrity | **Partial** | Four synthetic-shape instances found and fixed; acceptance suite not audited for the same class |
| 3 Behavioural coverage | **Partial** | Restart, concurrency, integrity failure tested. Load and multi-component failure not |
| 4 Observability | **Partial** | Structured logs present; diagnostic runbook not written; twelve findings exist as prose in #253 |
| 5 Bounded gaps | **Partial** | #294 and single-reviewer control lack resolution criteria (see `gap-register.md`) |

None of the five is failed. Each is evidenced at the level #253 required and
unproven at the level this document requires.

## What moves each question to "Answered"

| # | Action | Effort | Evidence produced |
|---|---|---|---|
| 1 | Clean-checkout run on a host that has never run the slice | Half day | Run log from a fresh environment |
| 2 | Audit `tests/integration/vertical-slice.sh` — per assertion, ask "could this pass while production is broken?" | 2–3 hours | Audit record; fixes for any found |
| 3 | Load at 10× acceptance volume; two-component failure scenario | 1–2 days | Failure-mode records |
| 4 | Write `diagnostic-runbook.md` from the twelve cluster findings | Half day | The runbook itself |
| 5 | Add resolution criteria to every entry in `gap-register.md` lacking one | Half day | Complete register |

## Why this is separate from #253

#253's acceptance criteria are five functional statements. They answer *"does
the path work?"* They do not answer *"would we know if it stopped working?"*

The twelve cluster-run findings each presented as a different class of failure
than their cause. That was survivable because the developer was in the loop
watching. It is not survivable for a slice that someone else depends on.

#253 closed correctly. This document exists so the gap between "passed" and
"dependable" is a written boundary, not an implicit one.

## The discipline that produced this

Four times in this slice's development, a test passed against a shape production
never produces:

1. `MockTenantFilter` in synflux — tests passed because production scanned a
   legacy component
2. `!scopes.isEmpty()` — tests passed because the short-circuit skipped the guard
3. Fixtures committed to git — run 2 passed because run 1 left files run 2 read
4. MCP scope tests — tests passed because they set an identity the filter
   never produces

Each was found by asking question 2. The acceptance suite is subject to the
same question until it has been audited.
