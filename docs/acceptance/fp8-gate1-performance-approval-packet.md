# Gate 1 — performance execution approval packet

**Status:** DRAFT — not authorized for execution.
**Gate:** Controlled FP8 performance only.
**Blocked on:** #775 approval/merge and the approvals below.
**No GPU allocation, benchmark, or production change is authorized by this
packet.**

## Scope

Compare the current 4B production model with the pinned 8B FP8 candidate on the
same isolated node3 GPU, using the same DEV-only extraction inputs, prompt,
sampling and output settings. This gate measures performance only; it makes no
extraction-quality, operational-readiness, or production-readiness claim.

| Item | Baseline | Candidate |
|---|---|---|
| Model | `qwen3-extraction` (Qwen3-4B) | `Qwen/Qwen3-8B-FP8` |
| Revision | currently deployed | `220b46e3b2180893580a4454f21f22d3ebb187d3` |
| Runtime | vLLM 0.29.0 / torch 2.13.0+cu130 / CUDA 13.0 | same |
| GPU | node3 RTX 5060 Ti, UUID to be re-confirmed | same physical UUID, exclusive |
| Context | `--max-model-len 8192` | same |
| Sequences | `--max-num-seqs 1` | same |
| Output cap | `max_tokens 4096` | same |
| Temperature | 0 | same |
| Prompt | `extraction-v1` (hash below) | same |

The production deployment is not the benchmark harness. Both configurations run
as isolated, disposable vLLM instances in an approved window.

## Frozen workload

- Workload: 20 reviewed DEV candidates deduplicated to **18 unique DEV chunks**
  from the 104-page Google report.
- Manifest: `docs/acceptance/artifacts/fp8-gate1/dev-workload.tsv`.
- Workload SHA-256:
  `9af1938a56c2be0a27d70c31b0d3adfaafbb55357c761845b01b733d0c0725ef`.
- Prompt SHA-256:
  `8a1a610cddc5e1ed21a54330ce95d5c1e07bddf2a06cfe9a271819c44b443f1e`.
- Plan revision: #775 commit `f257d980`.
- **HOLDOUT is not part of this workload and must remain sealed.**
- Each chunk is run three times per model (54 requests per model, 108 total).
  The warm-up is excluded from measurement and recorded separately.

## Pre-registered metrics and decision rule

| Metric | PASS threshold |
|---|---|
| Fatal failures | 0 OOM, crash, timeout, or unclassified failure |
| p95 end-to-end latency | ≤ 2.0× baseline |
| Throughput (completion tokens/s) | ≥ 0.5× baseline |
| Peak GPU allocated | ≤ 95% of documented device capacity |
| Completion | All scheduled requests complete; failures never count as passes |
| Evidence | Raw attempts, variance, GPU occupancy, restore, and logs |

Terminal result:

- `PASS` only if all thresholds hold for the final run.
- `FAIL` for OOM/timeout/crash/invalid output or threshold breach.
- `BLOCKED` if GPU/window/approval/artifact prerequisite is missing.
- `INCONCLUSIVE` if infrastructure invalidity prevents measurement; failures are
  still reported.
- Retries: at most one pre-declared retry per request for an infrastructure
  error; all attempts remain in the report. No silent retry/discard.

## Isolation and credentials

- Dedicated namespace; no Service/Ingress; no production service account or data.
- One confirmed target GPU UUID; no node2/Ada fallback.
- Read-only model hostPath; no writes to production volumes.
- **Do not copy `local-registry-cred` or any production pull secret.** Provide a
  dedicated scoped registry credential for the probe namespace.
- Timeout and automatic cleanup must be set before startup.

## Approval record (must be completed before execution)

| Field | Value |
|---|---|
| Gate | Performance |
| Execution owner | TBD — named individual |
| Restoration owner | TBD — named individual |
| Independent verifier | TBD — named individual, not execution owner |
| Service-owner approval | TBD — named approver + evidence link |
| Approved GPU/window | TBD — device UUID, start/end, hard stop |
| Outage duration | TBD — production vLLM downtime |
| Workload hash | `9af1938a...` (above) |
| Model revisions | 4B deployed; 8B FP8 `220b46e...` |
| Retry policy | One bounded infrastructure retry |
| Rollback deadline | TBD — restore image/args/replicas and verify baseline |
| Approval date | TBD |

## Pre-execution checks

1. #775 is merged and its decision rules are unchanged.
2. All approval-record fields are complete and linked.
3. Target GPU UUID is free; production vLLM state is recorded.
4. Artifact revision/checksums and workload/prompt hashes match this packet.
5. Scoped registry credential is available; no production pull secret is copied.
6. Cleanup, hard stop, rollback, and independent verification owners are active.

## Explicit exclusions

- Extraction-quality or operational-readiness conclusions.
- FP8 production rollout, provider/default switch, or Synflux cap change.
- HOLDOUT access, #723 execution, or #724 status change.
- Prompt/chunking/model/ontology/promotion changes.
- Running Gate 2 or Gate 3 from a Gate 1 PASS.

## Decision requested

Approve this packet only after #775 is merged and all record fields are named.
Execution remains unauthorized until a separate performance authorization is
granted.
