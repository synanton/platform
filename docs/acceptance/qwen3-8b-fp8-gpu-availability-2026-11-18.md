# Qwen3-8B-FP8 GPU availability assessment (#770)

**Collected:** 2026-11-18 09:24–09:25 UTC, read-only over SSH and `kubectl`.
**Outcome:** `ISOLATED_WINDOW_APPROVAL_REQUIRED`
**Probe status:** not started; #771 remains blocked.
**Production state:** unchanged.

## Physical device inventory

| Node | Physical GPUs visible to `nvidia-smi` | UUID / capability | Total | Used | Free | Artifact present |
|---|---|---:|---:|---:|---:|---|
| node1 | 1 | `GPU-220d6936...` GTX 1650, CC 7.5 | 4,096 MiB | 180 MiB | 3,548 MiB | no |
| node2 | 1 | `GPU-a004ce09...` RTX 4060 Ti, CC 8.9 | 16,380 MiB | 15 MiB | 16,016 MiB | yes |
| node3 | 1 | `GPU-2270a494...` RTX 5060 Ti, CC 12.0 | 16,311 MiB | 12,973 MiB | 2,969 MiB | yes |

`kubectl` reports capacity `nvidia.com/gpu: 2` on node1, node2, and node3, but
each host exposes only **one physical GPU** through `nvidia-smi`. The advertised
slot count is therefore not evidence of a second free physical card.

## node3 — target GPU

- Target class: RTX 5060 Ti, compute capability 12.0 (Blackwell), 16GB.
- Physical UUID: `GPU-2270a494-7456-1a7a-a974-eb76d94b9b1c`.
- Production vLLM process: PID `8558`, `VLLM::EngineCore`, 12,952 MiB used on
  that UUID.
- Free memory at collection: 2,969 MiB.
- Pinned FP8 artifact exists at `/mnt/local-fast/models/qwen3-8b-fp8`.
- The only target-class GPU is occupied by production vLLM. A probe at the
  previous `gpu_memory_utilization=0.90` cannot reserve 14.01 GiB; this is a
  pre-load allocation blocker, not an FP8 compatibility result.

## node2 — free GPU but not the target class

- Physical UUID: `GPU-a004ce09-4df3-3098-a6dc-1d11175ecf30`.
- RTX 4060 Ti, compute capability 8.9 (Ada), 16,380 MiB; 16,016 MiB free.
- The pinned FP8 artifact is present.
- This is a genuinely free 16GB GPU, but it is not the fixed Blackwell target
  class for #771. Using it would change the tested device/architecture and is a
  scope deviation, not a target compatibility result.

## node1 — insufficient

- GTX 1650, 4,096 MiB, compute capability 7.5. It cannot hold the 8B FP8
  artifact and is not the target class.

## Why slot count is not availability

The node objects advertise two GPU slots, but the host device inventory shows
one physical GPU per node. Kubernetes slot scheduling, physical device
identity, and actual free memory are three separate facts. A schedulable second
slot does not establish a second free card.

## Prerequisites for an isolated target-class probe

The minimum operational path is an explicitly approved isolated window on
node3:

1. Owner approval to stop/scale production vLLM on node3, freeing
   `GPU-2270a494...`.
2. A defined window and acknowledgment that the extraction endpoint will be
   unavailable for the duration.
3. Verification that Synflux/provider/replica settings are not changed.
4. Probe with the pinned artifact and a disposable workload on the freed GPU.
5. Stop/delete the probe and restore production vLLM to its prior image, args,
   replica count, provider and logging configuration.
6. Post-window verification of vLLM `8192/4`, Synflux cap `2048`,
   `eval-smoke=regex`, and two INFO replicas.

No such window is scheduled by this assessment. A node2/Ada probe would require
a separate scope decision because it is not the fixed target architecture.

## Conclusion

- A free physical GPU exists on node2, but it is not the target Blackwell class.
- The target-class GPU on node3 is occupied by production and has only
  ~2.97 GiB free.
- Therefore the correct outcome is **`ISOLATED_WINDOW_APPROVAL_REQUIRED`**.
- No pod was created for this assessment; no model was loaded; no production
  workload or configuration was changed.
- #771 must remain blocked until a target-class isolated window is approved or
  the probe scope is explicitly changed.
