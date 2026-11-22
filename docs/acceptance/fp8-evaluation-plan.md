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

## Decision semantics (pre-registered)

Every gate has exactly one terminal result: `PASS`, `FAIL`, `BLOCKED`, or
`INCONCLUSIVE`.

| Result | Meaning |
|---|---|
| `PASS` | All required criteria were observed, evidence is complete, and the gate's scope was respected. |
| `FAIL` | A criterion was violated, including OOM, timeout, crash, silent data loss, invalid output, or an unapproved configuration change. |
| `BLOCKED` | Prerequisites (GPU/window/approval/artifact/owner) were unavailable; no experiment result exists. |
| `INCONCLUSIVE` | The experiment ran but infrastructure invalidity prevents a decision; failures must still be reported. |

A classified failure is **not** a pass. OOM, timeout, crash, and infrastructure
failure are terminal failures for the run. Retries are allowed only under a
pre-declared bounded policy; every attempt and failure remains in the report.
No failed attempt may be silently discarded.

Gate ordering is sequential for execution (`performance` → `quality` →
`operational`), but authorization is independent. A PASS in one gate never
authorizes the next gate.

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

**Pre-registered decision rule**

- `PASS` only if every scheduled call either completes or is an explicitly
  declared retry, and the final run has **zero** OOM, crash, timeout, or
  unclassified failure.
- Proposed performance thresholds, measured against the frozen 4B baseline:
  p95 latency no worse than **2.0×** baseline; throughput at least **0.5×**
  baseline; peak GPU allocated ≤ **95%** of the documented device capacity.
  Any threshold change must be pre-registered and reviewed before execution.
- Any OOM/timeout/crash or unapproved configuration change => `FAIL`, not
  `INCONCLUSIVE`/`PASS`. Missing window/owner/device => `BLOCKED`.
- A reviewable report includes every attempt, raw counts, variance, and GPU
  occupancy/restore evidence.
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

**Pre-registered decision rule**

- `PASS` only if the final run has zero unclassified parse failures or
  incomplete outputs, and on DEV-only reviewed inputs the candidate is
  non-inferior to the frozen 4B baseline by a pre-registered margin of at most
  **5 percentage points** for relation precision and recall.
- Parse/schema validity must be no lower than the baseline; every
  parse/incomplete failure has an explicit disposition.
- Any regression beyond the margin, any threshold change during the run, or any
  unapproved ontology/promotion change => `FAIL`.
- Missing DEV scope/approval => `BLOCKED`; invalid infrastructure => `INCONCLUSIVE`.
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

**Pre-registered decision rule**

- `PASS` requires demonstrated isolation and scoped credentials, no production
  service-account/data access, readiness within **60 seconds** of process start,
  rollback completion within **15 minutes**, GPU/resource cleanup confirmed, and
  the exact production baseline restored and independently verified.
- Any production credential/secret exposure, missed rollback deadline, or
  unverified restoration => `FAIL`.
- Missing window/owner/service approval => `BLOCKED`; infrastructure invalidity
  => `INCONCLUSIVE`.
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
| Credentials | Dedicated scoped registry credential; production `local-registry-cred` and other production pull secrets are prohibited; any exception requires explicit approval and disclosure |
| Data | DEV only; HOLDOUT sealed unless separately approved |
| Evidence | Raw responses/usage via #766; sanitized logs |
| Rollback | Restore exact image/args/replicas and verify baseline |
| Merge | No automatic merge; review each report separately |

## Approval record template

No gate may start without a completed record:

| Field | Required content |
|---|---|
| Gate | Performance / extraction quality / operational readiness |
| Execution owner | Named individual |
| Restoration owner | Named individual (may be the same only if separately accepted) |
| Independent verifier | Named individual different from execution owner |
| Service owner approval | Named approver and evidence reference |
| GPU/window | Device UUID, approved start/end, hard stop, outage duration |
| Scope | Dataset, chunk/prompt hash, model revision, settings |
| Retry policy | Pre-declared bounded attempts and terminal disposition |
| Rollback deadline | Concrete timestamp and restoration checklist |
| Approval date | ISO-8601 date and link to approval record |

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
