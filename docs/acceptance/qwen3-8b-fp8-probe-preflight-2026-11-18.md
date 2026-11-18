# Qwen3-8B-FP8 isolated Blackwell probe — preflight

**Status:** `ISOLATED_WINDOW_APPROVAL_REQUIRED` — no execution approved.
**Related:** #770, #771, #772; execution record: #773.
**Collected:** 2026-11-18 09:28 UTC, read-only.
**No pod was created, no model was loaded, and no production state was changed
for this preflight.** This report precedes the actual probe recorded in #773;
the credential and window execution details are in that execution record.

## Frozen experiment contract

| Item | Value |
|---|---|
| Host | node3 |
| Target GPU | Physical RTX 5060 Ti, UUID `GPU-2270a494-7456-1a7a-a974-eb76d94b9b1c`, CC 12.0 |
| Artifact | `Qwen/Qwen3-8B-FP8`, revision `220b46e3b2180893580a4454f21f22d3ebb187d3` |
| Local path | `/mnt/local-fast/models/qwen3-8b-fp8` |
| FP8 config | `e4m3`, block size `128` |
| Runtime | vLLM 0.29.0, PyTorch 2.13.0+cu130, CUDA 13.0, driver 615.71.09 |
| Scope | Model load + one minimal deterministic inference; no benchmark/tuning |
| Production baseline | vLLM 8192/4; Synflux cap 2048; eval-smoke regex; 2 INFO replicas |

## Preflight evidence

### Target GPU and occupancy

```text
GPU 0: NVIDIA GeForce RTX 5060 Ti (UUID: GPU-2270a494-7456-1a7a-a974-eb76d94b9b1c)
0, GPU-2270a494..., RTX 5060 Ti, 12.0, 615.71.09, 16311 MiB, 12973 MiB used, 2969 MiB free
PROCESSES: 8558 VLLM::EngineCore 12952 MiB GPU-2270a494...
```

- Node3 physically exposes **one** GPU through `nvidia-smi`, despite the node
  object advertising two `nvidia.com/gpu` slots.
- The target GPU is occupied by production vLLM (PID 8558).
- Only 2,969 MiB is free; the planned FP8 probe needs the GPU to itself.
- The second advertised slot is not evidence of a second physical card.

### Other nodes

| Node | Physical GPU | State | Target-class? |
|---|---|---|---|
| node1 | GTX 1650, 4GB | free | no |
| node2 | RTX 4060 Ti, 16GB, CC 8.9 | free | no (Ada, not Blackwell) |
| node3 | RTX 5060 Ti, 16GB, CC 12.0 | production vLLM occupied | yes |

The pinned artifact also exists on node2, but using it would change the target
architecture. Node2 is explicitly **not** a fallback for this probe.

### Artifact integrity

```text
hf cache verify Qwen/Qwen3-8B-FP8 --revision 220b46e3... --local-dir /mnt/local-fast/models/qwen3-8b-fp8
=> Verified 12 file(s); all checksums match
=> 27 local auxiliary/cache files not on remote (warning only; 12-file model set verified)
```

Artifact config: `model_type=qwen3`, `Qwen3ForCausalLM`, FP8 `e4m3`, block
`[128,128]`, max position 40,960.

### Node resources

- 20 CPUs; 62GB RAM (20GB free, 49GB available); 935GB root filesystem with
  604GB free. Resource capacity is sufficient once the target GPU is isolated.

### Production baseline

- vLLM deployment: 1/1 available; args `/models`, served name
  `qwen3-extraction`, port 8000, `--gpu-memory-utilization 0.85`,
  `--max-model-len 8192`, `--max-num-seqs 4`.
- Synflux: 2/2 available; `EXTRACTION_CONSUMER_TENANT=*`; no
  `LLM_EXTRACTION_MAX_TOKENS` override; INFO logging.
- `tenant_provider_config(eval-smoke)=regex`.

## Isolation proposal (requires approval)

The only target-class path is an approved node3 window that temporarily frees
the production vLLM GPU. The proposal:

1. Obtain explicit owner approval for a bounded node3 window and the resulting
   extraction-endpoint outage.
2. Record pre-window production state and image/args.
3. Scale the production vLLM deployment to zero; verify PID 8558 exits and the
   target UUID has sufficient free memory.
4. Create a disposable probe in an isolated namespace with:
   - exactly one GPU allocation, pinned by node/UUID verification;
   - read-only hostPath for the pinned artifact;
   - CPU/RAM limits, no service, no ingress, no production service-account/data
     credentials; any registry pull credential must be scoped or disclosed;
   - a bounded active deadline and explicit cleanup.
5. Launch vLLM 0.29.0 with the recorded FP8 artifact and a single-request
   configuration (no benchmark).
6. Run one minimal deterministic inference and record startup/load/kernel/
   inference logs, versions, memory, and exact commands.
7. Delete the probe and temporary namespace/files.
8. Restore the production vLLM image, args, replicas, and verify the Synflux
   baseline and health.
9. Publish COMPATIBLE / INCOMPATIBLE / INCONCLUSIVE only for the exact tested
   device, artifact revision, stack, and settings.

No second GPU is allocated, no node2/Ada fallback is used, and no production
change is made before approval. The actual #773 probe used a copied production
registry pull secret solely for image pulling; that deviation is documented in
the execution report.

## Approval questions

- Who approves the node3 production-vLLM outage window and for how long?
- What is the accepted rollback/restore procedure and verification owner?
- Is a separate namespace permitted, or must the probe live in `gpu-plane` with
  equivalent isolation?
- Is a minimal JSON structured-output check included, or only plain inference?

## Current outcome

**`ISOLATED_WINDOW_APPROVAL_REQUIRED`.** Preflight is complete, but execution is
blocked because the only target-class GPU is occupied by production. No probe
was run; #771 remains blocked.
