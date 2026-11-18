# Qwen3-8B-FP8 Blackwell runtime probe — 2026-11-18

**Classification:** `COMPATIBLE` for the exact tested device, artifact revision,
vLLM stack, and settings below.
**Not a benchmark:** one model load and one plain deterministic inference only.
**Production restored and verified.**

## Probe contract

| Item | Value |
|---|---|
| Host | node3 |
| Physical GPU | RTX 5060 Ti, UUID `GPU-2270a494-7456-1a7a-a974-eb76d94b9b1c`, CC 12.0 |
| Driver / CUDA | 615.71.09 / CUDA 13.0 |
| Runtime | vLLM 0.29.0, PyTorch 2.13.0+cu130 |
| Artifact | `Qwen/Qwen3-8B-FP8`, revision `220b46e3b2180893580a4454f21f22d3ebb187d3` |
| Local artifact | `/mnt/local-fast/models/qwen3-8b-fp8` (12 files; checksums verified before probe) |
| FP8 config | `e4m3`, block size `128` |
| Server settings | port 8001, `--max-model-len 8192`, `--max-num-seqs 1`, `--gpu-memory-utilization 0.90` |
| Isolation | dedicated `synanton-probe` namespace; one GPU; no service; no production credentials |

A bounded production-vLLM maintenance window was approved before execution.
Production vLLM was scaled to zero to release the only physical target GPU;
PID 8558 exited and the GPU reported 15,929 MiB free before the probe.

## Model load

Relevant sanitized log evidence:

```text
Selected DeepGemmFp8BlockScaledMMKernel for Fp8LinearMethod
Model loading took 8.88 GiB memory and 23.741258 seconds
Available KV cache memory: 2.68 GiB
GPU KV cache size: 19,536 tokens
Maximum concurrency for 8,192 tokens per request: 2.38x
Application startup complete.
```

vLLM recognized the FP8 format and selected its FP8 block-scaled DeepGEMM
kernel. The service reached `/health=200`.

## Minimal inference

Plain chat completion, temperature 0, no structured-output constraints.

| Attempt | `max_tokens` | Result |
|---|---:|---|
| 1 | 16 | `finish_reason=length`; reasoning prefix only |
| 2 | 64 | `finish_reason=length`; reasoning prefix only |
| 3 | 256 | `finish_reason=stop`, 91 completion tokens, content ended with `ready` |

The first two caps were too small for the model's reasoning prefix; they were
not treated as incompatibility. Attempt 3 completed naturally. Raw response
metadata is committed as
`docs/acceptance/artifacts/qwen3-8b-fp8-probe/inference.json`.

## Cleanup and production restoration

- Experimental namespace `synanton-probe` deleted.
- Target GPU returned to 13 MiB used / 15,929 MiB free before production restart.
- Production vLLM restored to 1/1 with the original image and settings:
  - image `local-registry:5000/vllm-openai:v0.29.0`
  - `--max-model-len 8192`, `--max-num-seqs 4`
  - `/health=200`; served model `qwen3-extraction`
- Synflux: 2/2; `EXTRACTION_CONSUMER_TENANT=*`; no
  `LLM_EXTRACTION_MAX_TOKENS` override; INFO logging.
- `tenant_provider_config(eval-smoke)=regex`.
- No benchmark, DEV corpus ingestion, provider change, or #723 execution.

## Limitations

- Compatibility is established only for this GPU UUID/architecture, artifact
  revision, driver, CUDA/PyTorch/vLLM versions, and launch settings.
- The probe used plain inference; structured-output and extraction-quality
  compatibility remain separate questions.
- The model emitted a reasoning prefix; output-cap behavior must be evaluated
  in the benchmark, not inferred from this probe.
