# FP8 compatibility evidence review and next gate

**Mode:** review-only. No merge, benchmark, GPU probe, or runtime change.
**Reviewed:** PR #772 (preflight), PR #773 (runtime probe), issues #770/#771.
**Conclusion:** Runtime compatibility is credible for the exact tested stack.
The review findings were resolved in follow-up documentation; evidence is now
consistent across #772/#773. This remains an evidence review, not approval for
benchmarking or production adoption.

## Findings by severity

| Severity | Finding | Required disposition |
|---|---|---|
| Medium | #773 said “no production credentials,” but the probe namespace received a copy of the production `local-registry-cred` image-pull secret. | **Resolved** in #773 `e855d837`: discloses the copy, its image-pull-only use, namespace/secret deletion, and mandatory scoped credentials for future probes. |
| Medium | #773 did not explicitly link the authorization/window evidence. | **Resolved with limitation** in #773 `e855d837`: links #771/@andreminin, records approved/actual start/restore/available times and outage, and explicitly states no separate production-service-owner identity was recorded. |
| Low | #772/#773 cross-reference | **Resolved**: both records now cross-link preflight and execution. |
| Low | Artifact revision repetition | **Resolved**: #773 repeats the pinned revision in the model-load evidence and references the preflight checksum set. |
| Low | Timestamped post-restoration evidence | **Resolved**: #773 records the restore/available times, post-check time, GPU return, vLLM health, Synflux cap/replicas, and eval-smoke provider. |
| Low | KV-cache/concurrency figures (19,536 tokens, 2.38× at 8K) are clearly labeled configuration observations, not benchmark results. | No correction needed beyond preserving that qualification. |
| None | Inference: 16/64-token attempts hit `length`; 256-token attempt reached `stop`. The report correctly does not infer incompatibility from the short caps. | Keep. |

## Evidence consistency

- Both reports use the same target UUID `GPU-2270a494...`, RTX 5060 Ti, CC 12.0.
- Both use `Qwen/Qwen3-8B-FP8` revision
  `220b46e3b2180893580a4454f21f22d3ebb187d3`.
- Runtime: vLLM 0.29.0, PyTorch 2.13.0+cu130, CUDA 13.0.
- Model load selected DeepGEMM FP8 and reported 8.88 GiB weights, 2.68 GiB KV
  cache, 19,536 tokens, 2.38× max concurrency at 8,192.
- Inference reached `finish_reason=stop`; 91 completion tokens.
- The probe namespace was deleted and production vLLM was restored to 1/1 with
  `/health=200`; Synflux 2/2, cap 2048 default, `eval-smoke=regex`, INFO.

## Re-review status

- #772 preflight cross-link/credential-control language updated in `cbdd1635`.
- #773 execution evidence corrected in `e855d837`.
- All review findings are resolved; the approval traceability limitation is now
  explicit rather than inferred.
- No PR has been merged; no benchmark, GPU probe, or runtime change followed
  from this review.

The compatibility claim should remain strictly:

> COMPATIBLE for the exact GPU UUID/architecture, artifact revision, driver,
> CUDA/PyTorch/vLLM versions, and launch settings tested. Not a throughput,
> quality, capacity, or production-readiness result.

## Next-gate proposal (not authorized)

Separate the next evaluation into three independently approved questions:

### A. Controlled performance benchmark

- Use the same pinned 8B FP8 artifact and isolated target GPU/window.
- Frozen DEV inputs only; HOLDOUT remains sealed.
- Measure load time, prompt/completion throughput, latency distribution,
  peak GPU memory, KV capacity, concurrency, and failure rate.
- Define pass/fail against the current production 4B baseline; no quality
  conclusion from timing alone.

### B. Extraction-quality evaluation

- Use reviewed DEV chunks and the frozen extraction contract.
- Separate JSON validity/schema compliance from entity/relation precision,
  recall, evidence sufficiency, and acceptance rate.
- Do not tune thresholds or prompts between reference and candidate without a
  new approval.
- HOLDOUT remains closed until a specific blind-verification claim is approved.

### C. Operational readiness

- Define rollback/restore ownership and a bounded outage window.
- Verify GPU contention and memory headroom at the intended concurrency.
- Require a separate production-change decision; compatibility does not imply
  production adoption.

## Approval requirements before execution

- Production owner approval for a new node3 window and outage duration.
- Execution and restoration owner; independent post-restore verifier.
- Target GPU identity and exclusive allocation confirmed immediately before
  startup.
- Artifact revision/checksum re-verification.
- No HOLDOUT access; no #723 ingestion.
- No production vLLM/Synflux/provider/cap change without separate approval.

## Explicitly unauthorized

- Merging #772 or #773 as part of this review.
- Benchmarking or further GPU probes.
- FP8 production rollout or provider switch.
- Synflux cap, logging, replica, ontology, promotion, or graph changes.
- #723 ingestion or #724 status changes.
- HOLDOUT access.
