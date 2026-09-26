# Phase 0 exit checklist (engineering complete; formalities + gate outstanding)

## Exit-review agenda (all must close before Phase 0 is declared)

- [ ] 006 sign-off line (Search Eng): thresholds table as frozen (lex 0.72 / vec 9.5 /
  hybrid 9.3ms, lexical Recall ≥ 0.98).
- [ ] **Vector Recall@10 ≥ 0.95 ratification (Search Eng + Architecture)** — agenda
  item for discussion, not a sign-off pile initial. Covers the Option B absolute gate.
- [ ] 040 named-human confirmation (Platform Eng): countersign the workstream
  ownership recorded in `040-call-site-rewire.md`.
- [ ] 010 resolution recorded below (frozen or throwaway).

## 010 date-drift guard

If 2026-10-03 passes with no Architecture decision, the date must not drift into
implicit throwaway. The driver (Platform Eng, YDB-PoC workstream) **formally
activates the throwaway path**: mark `011-provisional-followup.md` as load-bearing,
notify the 021/024A/024B owners in writing, and record the activation date here.
Unexpired silence is not a decision; expired silence is, once activated.

## 010 flip playbook

**Positive (frozen):** re-run 020+039 suites to confirm green, then open **021 first**
(critical path for Phase-1 benchmarks and Phase-3 freshness); 024A/024B start in
parallel (024A likely closes first — smaller lift). Re-validate provisional types
and lift where unchanged.

**Negative / expired (throwaway):** bounded re-scope per the provisional file
(≤ 2 days); 021/024A/024B run evaluation-only. Provisional file stays open.

## Standing watch-items (do not close in transition)

- `011-provisional-followup.md` stays open until 010 flips either way.
- 040 confirmation counts only on human signature, not on "owned" status.
