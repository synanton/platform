# Synanton embedding service

CPU/GPU embedding service for the vector path (#349). It loads one small model,
measures a probe vector at startup, and serves `/health`, `/info` and `/embed`.

D2 is implemented: the model loads from the mounted local path (no Hub download
by default), the measured dimension is exposed for #348's payload freeze, and a
dimension/device mismatch fails startup.

## Endpoints

| Endpoint | Contract |
|---|---|
| `GET /health` | `200` once the model and probe succeed; `503` before that / after failure |
| `GET /info` | model identity, **measured** dimension, backend/device, probe vector head + hash, truncation state, batch limit |
| `POST /embed` | `{ "texts": [...] }` → `{ vectors, model, dimension }`; capped by `MAX_BATCH_SIZE` |

`/info` example:

```json
{
  "model_name": "BAAI/bge-small-en-v1.5",
  "model_path": "/mnt/local-fast/models/bge-small-en-v1.5",
  "dimension": 384,
  "dimension_source": "measured",
  "backend": "cuda",
  "device_name": "NVIDIA ...",
  "sample_vector_head": [0.0213, -0.0147, 0.0891, 0.0, 0.0],
  "sample_vector_hash": "sha256:...",
  "truncation": { "enabled": false, "target_dim": null },
  "max_batch_size": 100
}
```

`model_path` is the operator's mount path, included for debugging. It is not a
user path; no per-request filesystem paths are exposed.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `EMBED_MODEL` | `/mnt/local-fast/models/bge-small-en-v1.5` | local path or Hub id |
| `EMBED_MODEL_NAME` | `BAAI/bge-small-en-v1.5` | reported model identity |
| `EMBED_DIM` | `384` | expected dimension; measured at startup and hard-failed on mismatch |
| `EMBED_DEVICE` | `cpu` | `cuda` or `cpu`; homelab GPU deployment sets `cuda` |
| `MODEL_LOCAL_FILES_ONLY` | `true` | prevents a Hub fallback when the local path is wrong |
| `MAX_BATCH_SIZE` | `100` | maximum texts per `/embed` |
| `EMBEDDING_PORT` | `8000` | service port |
| `EMBED_TRUNCATE_DIM` | `0` | ignored by the service; non-zero is logged |

The consumer points at `EMBEDDING_SERVICE_URL`.

## Startup invariants

1. Resolve the configured device. `EMBED_DEVICE=cuda` with no CUDA fails hard.
2. Load the model with `local_files_only` when configured; no silent download.
3. Embed the probe text (`EMBED_PROBE_TEXT`).
4. Compare the measured length to `EMBED_DIM`. Mismatch → `DimensionMismatchError`, no serving.
5. Only then `/health` and `/info` become `200`.

Log line:

```text
embedding service ready model=... path=... measured_dim=384 EMBED_DIM=384 backend=cuda device='...' truncation=off
```

## Available models on the nodes

`/mnt/local-fast/models` is present on node1, node2 and node3. node2/node3 have
16 GB NVIDIA GPUs; node1 has the model directory too. All listed models fit the
16 GB cards.

| Model | Type | Native dim | Size | Role |
|---|---|---|---|---|
| `bge-small-en-v1.5` | embedding | 384 | 33M | **D2 choice**; ADR-013's measured model |
| `bge-base-en-v1.5` | embedding | 768 | 110M | alternative; requires an ADR-013 re-benchmark |
| `qwen3-embedding-0.6b` | embedding | ~1024 | 600M | stronger option; requires an ADR-013 re-benchmark |
| `bge-reranker-0.6b` | reranker | n/a | 600M | future reranking track |
| `qwen3-reranker-0.6b` | reranker | n/a | 600M | future reranking track |

bge-small remains the D2 choice because ADR-013 measured it at 384d and the
demo needs a fast, small CPU/GPU model. A GPU makes a larger model feasible, but
that is a retrieval-quality decision, not a deployment decision.

**ADR currency:** ADR-013 predates `qwen3-embedding-0.6b`. To change the model,
re-run the ADR-013 benchmark with bge-small, bge-base and qwen3-embedding on the
same corpus, record recall@k/MRR/latency, then change `EMBED_MODEL` +
`EMBED_DIM` and re-index (#348 B3b). Do not swap on intuition.

**Why the hard-fail matters:** three embedding models with three dimensions are
on disk. A swapped `EMBED_MODEL` without a swapped `EMBED_DIM` is one env var
away; startup catches it before any vector is written.

## Running D2 (bare process)

The fastest path to the #348 B3a measurement, and it bypasses the sandbox
Docker Hub restriction:

```bash
# on node2 or node3 (models are local)
python3 -m venv /tmp/embed-venv
/tmp/embed-venv/bin/pip install -r services/embedding/requirements.txt

EMBED_MODEL=/mnt/local-fast/models/bge-small-en-v1.5 \
EMBED_MODEL_NAME=BAAI/bge-small-en-v1.5 \
EMBED_DIM=384 \
EMBED_DEVICE=cuda \
MODEL_LOCAL_FILES_ONLY=true \
/tmp/embed-venv/bin/uvicorn app:app --host 0.0.0.0 --port 8000
```

From another node:

```bash
curl http://node2:8000/info
```

Record `dimension: 384`, `backend`, `device_name`, `sample_vector_hash` in #348.

For a CPU-only measurement, set `EMBED_DEVICE=cpu`; the dimension is identical.
Vector values can differ slightly by backend; the payload freeze cares about the
dimension, not the values.

Run unit tests without the heavy model dependencies:

```bash
python3 -m venv /tmp/embed-test-venv
/tmp/embed-test-venv/bin/pip install -r services/embedding/requirements-test.txt
cd services/embedding && /tmp/embed-test-venv/bin/pytest
```

## Container

```bash
# Build on node2/node3 (or the internal mirror); the sandbox has no Hub access.
docker build -t local-registry:5000/synanton/embedding:<tag> services/embedding
```

The image is based on `python:3.12-slim` with the standard PyPI `torch` wheel.
GPU access is a deployment concern: use the same NVIDIA runtime class/resource
request pattern as the existing gpu-runtime workloads. Do not add device
auto-detection to the service; pass `EMBED_DEVICE=cuda` and fail loudly if the
deployment did not provide a GPU.

D5 placement: node3 preferred (GPU, models on disk, no CPU contention with the
extraction gateway); node2 acceptable. Mount the node's
`/mnt/local-fast/models` path into the container and set `EMBED_MODEL` to the
mounted path.

## Design constraints

- **No truncation.** Native dimensions only; `EMBED_TRUNCATE_DIM` is ignored
  and logged. Truncation belongs downstream (gpu-plane).
- **No cross-restart cache.** Each request is independent; a persistent cache
  would be a hidden second store.
- **No device auto-selection.** The deployment chooses `EMBED_DEVICE`; the
  service honors it and fails if unavailable.
- **No model downloads by default.** `MODEL_LOCAL_FILES_ONLY=true` makes a bad
  path fail instead of silently fetching from the Hub.
- **Loud startup failure.** Model load, device, or dimension mismatch stops the
  service; `/health` never flips to `200` for a wrong dimension.

## Sequence

D2 (this) → `/info` measurement → #348 B3a payload freeze → D3 batch `/embed`
(already implemented) → D5 k8s deployment → #348 B3b/B4.
