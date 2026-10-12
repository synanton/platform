# Design 1.27 freeze packet (mirrors the 1.32 packet pattern)

Freezing = marking the Eventing and Workflow contract immutable; afterwards
any change needs a versioned design revision, never a silent edit. Per
Architecture 1.0 §14/L424, no 1.26–1.34 plane begins implementation until
1.27 (and 1.32) are frozen — the gate is joint; schedule both together.

## 1. Freeze candidates (verify current before signing)

| Surface | Sections | Freeze form |
|---|---|---|
| Event model + identity + type + version | §§8–11 | Doc marked frozen; no TBDs present (verified 2026-10-12) |
| Schema evolution + envelope + tenant scope + security context | §§12–15 | Same |
| Commands + identity + result + event-vs-command | §§16–19 | Same |
| Topic/stream + partitioning + ordering + delivery | §§20–23 | Same |
| Consumer idempotency + patterns + outbox/inbox | §§24–30 | Same |
| Retry classification + DLQ + poison + backpressure + retention | §§31–37 | Same |
| Workflow + state machine + cancellation + recovery | §§40–51 | Same |
| Priority + quotas + fairness + correlation/causation/trace/lineage | §§59–64 | Same |

## 2. Pre-freeze checks (all must pass day-of)

- [ ] No TBD/TODO/placeholder language in the frozen sections
  (grep-verified, not eyeballed).
- [ ] Cross-track references resolve: 1.23 (security), 1.28 (ingestion),
  1.30 (AI runtime), 1.32 (platform API), 1.34 (temporal) sections cited
  by number exist in their docs.
- [ ] Joint scheduling with the 1.32 freeze packet (the gate is joint —
  freezing one without the other unblocks nothing).
- [ ] Outbox/retention numbers (if any) marked as policy (tunable) vs
  contract (frozen) — retention periods and retry budgets must not freeze
  accidentally.

## 3. Freeze commit contents

1. Status line in `synanton-design-1.27.md`: Approved → Frozen, with date.
2. Tracker entry: date, owner, freeze commit hash, checklist results.
3. This packet archived as executed (fill in below).

## 4. Execution record (fill at scheduling)

- Date:
- Owner:
- Freeze commit:
- Exceptions (if any §2 item waived, with reason + follow-up):
