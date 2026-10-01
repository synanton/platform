# Track DOC — Documentation (DOC · 12 tasks, cross-track)

Parent: [INDEX.md](./INDEX.md)

Documentation ships alongside the code, not after. Each item has an owner (Docs owner)
and a deadline tied to a track milestone. Canonical 12 (D4.1 + D4.2 merged into DOC-D4.1).

## Phase D0 — Frozen-schema documentation (Week 2)

### DOC-D0.1 — Benchmark Manifest schema reference · Ships with BR-A0.2

- Description: Human-readable reference for the manifest schema. Fields, examples, validation rules.
- Acceptance: A user can author a manifest from this doc alone.
- Evidence: `docs/benchmark/manifest-reference.md`.
- Estimate: 1 day.
- Depends on: BR-A0.2.

### DOC-D0.2 — Result Manifest schema reference · Ships with BR-A0.3

- Description: Same for output schema.
- Acceptance: A user can interpret a Result Manifest.
- Evidence: `docs/benchmark/result-reference.md`.
- Estimate: 1 day.
- Depends on: BR-A0.3.

### DOC-D0.3 — Metric taxonomy reference · Ships with BR-A0.4

- Description: Formal definitions of every metric the runner collects.
- Acceptance: Definitions match BR-A0.4.
- Evidence: `docs/benchmark/metric-taxonomy.md`.
- Estimate: 4 hr.
- Depends on: BR-A0.4.

## Phase D1 — Runner operational documentation (Week 5)

### DOC-D1.1 — Runner runbook (covers BR-A3 runbook scope) · Ships with BR-A2.2

- Description: How to author a manifest, invoke the runner, interpret results, recover from failure.
- Acceptance: New user runs a manifest end-to-end using only this doc.
- Evidence: `docs/benchmark/runner-runbook.md` + validation by unfamiliar team member.
- Estimate: 2 days.
- Depends on: BR-A2.2.

### DOC-D1.2 — Failure modes and diagnostics · Ships with BR-A1.4

- Description: Common failures (schema mismatch, hash mismatch, timeout), diagnosis, recovery.
- Acceptance: Every failure from BR-A1.4 has an entry.
- Evidence: Section in runbook.
- Estimate: 1 day.
- Depends on: BR-A1.4.

## Phase D2 — Vector engine documentation (Week 8)

### DOC-D2.1 — Composition configuration guide · Ships with VEC-B2.3

- Description: How to configure `metadata.*` + `vector.*`; working example for each of the 6 compositions.
- Acceptance: Every composition has a working example config.
- Evidence: `docs/architecture/vector-composition-guide.md`.
- Estimate: 2 days.
- Depends on: VEC-B2.3.

### DOC-D2.2 — VectorRetriever port reference · Ships with VEC-B1.4

- Description: Interface, contract, capability semantics.
- Acceptance: An implementer can build a new adapter from this doc.
- Evidence: `docs/architecture/vector-retriever-reference.md`.
- Estimate: 1 day.
- Depends on: VEC-B1.4.

### DOC-D2.3 — Dev-guide update: parameterization + composition · Ships with VEC-B2.3

- Description: Add parameterization discipline (from YDB-041) and composition rules to the dev-guide.
- Acceptance: Both rules citable by PR reviewers.
- Evidence: Updated `docs/dev-guide.md` (or current equivalent).
- Estimate: 4 hr.
- Depends on: VEC-B2.3.

## Phase D3 — Selection framework documentation (Week 11–12)

### DOC-D3.1 — Vector engine selection framework · Ships with VEC-B6.2

- Description: Published decision framework: matrix, profiles, recommendations, sources, flags.
- Acceptance: Reviewed by architecture + legal. Every cell has a source or "unmeasured" flag.
- Evidence: `docs/architecture/vector-engine-selection.md`.
- Estimate: 3 days.
- Depends on: VEC-B6.2.

### DOC-D3.2 — Decision record: engine selection per context · Ships with VEC-B6.2

- Description: For each context, the recommended engine and why.
- Acceptance: Each decision names owner, date, rationale, revisit trigger.
- Evidence: `docs/architecture/decisions/vector-engine-selection.md`.
- Estimate: 1 day.
- Depends on: VEC-B6.2.

### DOC-D3.3 — Pattern library additions · Ships with end of Track B

- Description: New patterns surfaced during the work: composition boundaries, cross-engine
  consistency, score-space portability, taxonomy of "guard exists but doesn't run" (PN-8), etc.
- Acceptance: New patterns recorded with origin instances.
- Evidence: Updated `docs/architecture/pattern-library.md` (or equivalent).
- Estimate: 1 day.
- Depends on: VEC-B6.2.

## Phase D4 — Cross-track documentation hygiene (ongoing)

### DOC-D4.1 — Cross-reference maintenance + review cadence (merges old D4.1 + D4.2)

- Description: Every doc references the current schema version; every schema references the doc
  (CI check if possible). Docs owner reviews all cross-track docs at each phase gate so nothing
  drifts behind code.
- Acceptance: No dangling references; every gate checklist includes DOC items.
- Evidence: CI check or manual review cadence; gate checklists with DOC items.
- Estimate: ongoing, ~2 hr/week + ~4 hr/gate.
- Depends on: continuous from BR-A0.2.
