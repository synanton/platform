# Controlled FP8 evaluation proposal

**Status:** proposal only — not authorized for execution.
**Runtime evidence:** #772 (preflight), #773 (compatibility), #774 (review);
all merged.
**Production baseline:** vLLM `8192/4`, Synflux cap `2048`, `eval-smoke=regex`,
two INFO replicas.
**Candidate:** `Qwen/Qwen3-8B-FP8`, revision
`220b46e3b2180893580a4454f21f22d3ebb187d3`, FP8 `e4m3`, block size `128`.
**HOLDOUT:** sealed; not authorized for this proposal.

The compatibility probe proved only that the pinned artifact can load and
complete one plain inference on the exact Node3 stack. It did not measure
performance, extraction quality, or operational fitness.

## Gate 1 — Controlled performance

**Question:** What are the throughput, latency, memory, and concurrency costs of
the FP8 candidate relative to the current production 4B baseline?

**Scope**

- Isolated, disposable vLLM serving the pinned artifact; no production traffic.
- Frozen DEV-only extraction prompts/chunks, identical across models.
- Record load time, time-to-first-token, end-to-end latency, prompt/completion
  tokens/s, peak/allocation memory, KV capacity, concurrency, failures, and
  `finish_reason` distribution.
- Repeat a fixed subset to expose variance; no prompt or sampling changes
  between configurations.

**Acceptance for this gate**

- Every scheduled call completes or is classified by an explicit failure.
- No OOM, crash, or timeout; GPU occupancy and restore are recorded.
- A reviewable report compares the candidate and baseline with raw counts and
  uncertainty.
- No production capacity or rollout claim follows from the numbers.

**Approval required:** benchmark authorization, production service-owner
approval for the GPU window, and named execution/restoration owners.

## Gate 2 — Extraction quality

**Question:** Does the candidate improve or degrade structured extraction and
relation quality versus the current baseline?

**Scope**

- Same frozen extraction contract and reviewed DEV inputs; HOLDOUT remains
  closed unless a separate blind-evaluation authorization explicitly opens it.
- Separate syntax from semantics: parse/schema success, `finish_reason=length`,
  entity validity, type accuracy, predicate correctness, evidence sufficiency,
  relation precision/recall, and accepted-relation rate.
- Preserve raw failures through #766 capture; no parser repair or boundary
  rewriting to make a run pass.
- Compare against the frozen 4B baseline; do not change thresholds after
  results are seen.

**Acceptance for this gate**

- A reviewable comparison with raw TP/FP/FN counts and uncertainty.
- Explicit disposition for every parse/incomplete failure.
- No ontology, promotion, or threshold changes during the evaluation.
- Quality claims are limited to the tested corpus and model revision.

**Approval required:** evaluation authorization; explicit HOLDOUT approval only
if a blind claim requires it; production service-owner approval and named
execution/restoration owners.

## Gate 3 — Operational readiness

**Question:** Can the candidate be operated safely, observably, and reversibly
under an approved window?

**Scope**

- Dedicated namespace/isolation boundary, scoped registry credentials, no
  production service account or data access.
- Monitoring, alerting, health/readiness, GPU contention, memory headroom,
  failure recovery, rollback, and ownership.
- A bounded GPU window with a named execution owner, restoration owner, and
  independent post-restore verifier.
- No production provider/default switch in this gate.

**Acceptance for this gate**

- Isolation and credential scope demonstrated.
- Monitoring and rollback evidence captured.
- Production baseline verified unchanged before/after.
- A separate production-change decision is required; this gate cannot promote
  FP8 by itself.

**Approval required:** operational-test authorization, production service-owner
approval, named execution/restoration owners, and a rollback/verification plan.

## Resources and controls

| Control | Requirement |
|---|---|
| GPU | Confirmed free target-class device UUID; exclusive allocation |
| Window | Explicit start/end and hard stop; production owner approval |
| Artifact | Revision-pinned; checksums re-verified before execution |
| Credentials | Scoped registry credential; any exception disclosed |
| Data | DEV only; HOLDOUT sealed unless separately approved |
| Evidence | Raw responses/usage via #766; sanitized logs |
| Rollback | Restore exact image/args/replicas and verify baseline |
| Merge | No automatic merge; review each report separately |

## Explicitly excluded

- Production FP8 rollout or provider-default change.
- #723 ingestion or #724 status change.
- HOLDOUT access without separate approval.
- Ontology, promotion, filtering, prompt, chunking, or runtime changes.
- Running all three gates as one experiment or treating one gate's result as
  evidence for another.

## Decision requested

Review and approve the *plan* only. No GPU window, benchmark, quality run, or
operational test is authorized by this document. The first executable task must
name the gate, resources, execution/restoration owners, and approval record.
