# FP8 compatibility evidence review and next gate

**Mode:** review-only. No merge, benchmark, GPU probe, or runtime change.
**Reviewed:** PR #772 (preflight), PR #773 (runtime probe), issues #770/#771.
**Conclusion:** Runtime compatibility is credible for the exact tested stack;
the evidence should be clarified before it is used as the basis for any
evaluation or production decision.

## Findings by severity

| Severity | Finding | Required disposition |
|---|---|---|
| Medium | #773 says “no production credentials,” but the probe namespace received a copy of the production `local-registry-cred` image-pull secret. The pod needed it to pull vLLM. | Disclose the exception, or use a scoped pull credential in any future probe. The namespace was deleted, limiting persistence, but the report must not imply zero production credentials. |
| Medium | #773 does not explicitly link the authorization/window evidence: who approved it, the start/end time, or the outage duration. | Add the #771 approval reference and the recorded window/outage timestamps before treating the report as a complete audit record. |
| Low | #772 and #773 are separate records with a chronological transition from `ISOLATED_WINDOW_APPROVAL_REQUIRED` to execution. | Keep both; add a cross-reference so the preflight is not read as contradictory. |
| Low | #773 references 12/12 checksum verification from the preflight but does not repeat the checksum manifest or artifact revision in its own evidence block. | Add the pinned revision and a manifest/checksum reference; no need to duplicate 9.45GB. |
| Low | Production restoration evidence in #773 lacks exact timestamped post-restore `vLLM` args, Synflux cap, and health output. | Add sanitized command outputs and a post-restore timestamp. |
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
