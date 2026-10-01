# Track A — Benchmark Runner (BR · 12 tasks)

Parent: [INDEX.md](./INDEX.md) · Proposal: [proposal-benchmark-runner.md](../../architecture/proposals/Benchmark-runner/proposal-benchmark-runner.md)

Canonical 12 (folded from 17 listed). Merges: A1.2+A1.3 → BR-A1.2; A2.2+A2.3 → BR-A2.2;
A3.1+A3.2+A3.3 → BR-A3.1 (optional analytic sinks); A3.4 + runbook pointer → BR-A3.2
(runbook itself counted once under DOC-D1.1).

Historical IDs from the pre-reconciliation draft are noted inline where tasks were merged;
canonical IDs in this file are current. See INDEX.md for the complete reconciliation table.

## Phase A0 — Requirements and schema design (Week 1–2)

### BR-A0.1 — Gather requirements from Track B

- Description: Structured requirements session with Track B owner. Capture: composition
  description format, corpus reference format, metric shape, reproducibility requirements,
  execution mode (parallel/sequential), output needs.
- Acceptance: Written requirements doc covering all fields in §2.1 of the plan.
- Evidence: `benchmark-runner-requirements.md` on Track A branch.
- Estimate: 4 hr.
- Depends on: Track A + Track B owners named.

### BR-A0.2 — Draft Benchmark Manifest schema

- Description: JSON Schema (or equivalent) for the manifest. Must express: corpus reference,
  N compositions, per-composition config, metrics requested, reproducibility, cost controls,
  output sink.
- Acceptance: Schema validates the 6-composition spec as a test input.
- Evidence: `benchmark-manifest.schema.json` + validation test fixture.
- Estimate: 1 day.
- Depends on: BR-A0.1.

### BR-A0.3 — Draft Result Manifest schema

- Description: JSON Schema for run output. Reuse Q3 shape from R3: `run_id`, `corpus`,
  `queries[]` with `top_k`, `eligible_set`, `timing_ms`, `timing_scope`. Add: metrics summary,
  reproducibility flags, environment, topology annotations.
- Acceptance: Existing R3 artifacts validate against the schema.
- Evidence: `result-manifest.schema.json` + R3 artifacts pass validation.
- Estimate: 1 day.
- Depends on: BR-A0.1.

### BR-A0.4 — Metric taxonomy document

- Description: Canonical definitions for every metric the runner collects. Recall@10, overlap,
  eligible-set identity, latency percentiles, index build time, index size, freshness.
- Acceptance: Both tracks use identical definitions. No ambiguity on what "overlap" means.
- Evidence: `metric-taxonomy.md`.
- Estimate: 4 hr.
- Depends on: BR-A0.1.

Gate A0: Manifest schema, Result schema, metric taxonomy frozen. Cross-track sign-off.

## Phase A1 — Core infrastructure (Week 3–4)

### BR-A1.1 — Manifest parser + validator

- Description: Read manifest, validate against schema, fail fast with field path on any error.
- Acceptance: Missing/invalid fields produce named errors before execution.
- Evidence: Validator tests (valid, malformed, missing field, wrong type).
- Estimate: 1 day.
- Depends on: BR-A0.2.

### BR-A1.2 — ResultSink abstraction + FileResultSink (merges old A1.2 + A1.3)

- Description: Interface `ResultSink { write(ResultManifest); emit(RunCompletedEvent); }`,
  pluggable implementations. File sink: atomic write (temp + rename), path from manifest/config.
- Acceptance: Interface defined; artifact exists ⟺ run completed successfully.
- Evidence: Interface + javadoc; sink tests incl. failure case (partial write leaves no artifact).
- Estimate: 1 day.
- Depends on: BR-A0.3.

### BR-A1.3 — Reproducibility verification (old A1.4)

- Description: Corpus hash verification against manifest. Seed check. Fail loud on mismatch.
- Acceptance: Hash mismatch → named error, run aborts before execution.
- Evidence: Tests: matching hash (pass), mismatched hash (fail), missing hash (fail).
- Estimate: 4 hr.
- Depends on: BR-A1.1.

### BR-A1.4 — Cost controls (old A1.5)

- Description: `--dry-run` (validate only), timeout, max-event-count guard. All manifest-configurable.
- Acceptance: Each control fires loudly when triggered.
- Evidence: Tests: dry-run produces no artifacts, timeout aborts, event-count guard fires.
- Estimate: 1 day.
- Depends on: BR-A1.1.

Gate A1: Runner executes a trivial manifest end-to-end, produces valid Result Manifest,
all controls fire correctly.

## Phase A2 — Composition execution (Week 5–6)

### BR-A2.1 — Multi-composition execution

- Description: Accept manifest with N compositions; execute each against the same corpus;
  produce N Result Manifests. Emits `RunCompletedEvent` per run (provisional shape pending
  Eventing 1.27 freeze: `run_id`, `manifest_id`, `composition_count`, `duration_ms`).
- Acceptance: 3-composition test manifest produces 3 valid artifacts + events.
- Evidence: Test run + artifacts + event capture test.
- Estimate: 2 days.
- Depends on: BR-A1.2, BR-A1.3.

### BR-A2.2 — Metric collection + topology propagation (merges old A2.2 + A2.3)

- Description: Compute recall, overlap, latency percentiles, index size from raw query results;
  populate Result Manifest. Per-query topology strings (`btree_sort`, `ivfflat`, `milvus_gpu`,
  etc.) flow from execution into the manifest — no metric without its topology.
- Acceptance: All metric-taxonomy fields populated or explicitly null; topology present per query.
- Evidence: Metric computation tests against known fixtures; topology-presence assertion per query.
- Estimate: 3 days.
- Depends on: BR-A0.4, BR-A2.1.

Gate A2: Runner executes the 6-composition spec against a small corpus, produces valid
artifacts for all 6. Track B Phase B5 pre-check.

## Phase A3 — Integration and refinement (Week 7–8)

### BR-A3.1 — Pluggable analytic sinks: Kafka + ClickHouse (merges old A3.1 + A3.2 + A3.3; optional)

- Description: Alternative `ResultSink`s emitting Result Manifest + `RunCompletedEvent` to Kafka
  (EventLab integration) and ClickHouse (analytics). Same contract as FileResultSink.
- Acceptance: Each sink passes the shared sink contract tests.
- Evidence: Sink contract tests.
- Estimate: 2 days.
- Depends on: BR-A1.2, BR-A2.1.
- Note: Optional / deferrable if EventLab integration isn't immediate or Track B5 needs only files.

### BR-A3.2 — Result Manifest API + runbook pointer (old A3.4; runbook owned by DOC-D1.1)

- Description: Read endpoints `GET /benchmarks/results`, `GET /benchmarks/results/{id}`,
  `GET /benchmarks/manifests/{id}` returning validated manifests. Operational runbook itself
  ships as DOC-D1.1; this task tracks API + runbook review by an unfamiliar team member.
- Acceptance: Endpoints return validated manifests; new user runs a manifest end-to-end on runbook alone.
- Evidence: API tests; runbook review record.
- Estimate: 2 days.
- Depends on: BR-A0.3, BR-A2.2.
- Note: API not blocking Track B5 — UI integration is future.

Gate A3: Runner is usable by anyone with a manifest. Track B5 is a validated client.
