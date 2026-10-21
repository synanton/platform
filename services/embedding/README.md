# Synanton embedding service

CPU-only embedding service for the vector path (#349). D1 is the skeleton:
the container builds and serves, but the model is not loaded yet, so `/health`,
`/info` and `/embed` return `503 model not loaded`.

D2 adds `sentence-transformers`, loads one small model, measures the probe
vector's dimension, and serves `/info` for #348's payload freeze.

## Endpoints

| Endpoint | Contract |
|---|---|
| `GET /health` | `200` when the model is loaded; `503` during startup / after failure |
| `GET /info` | `{ model_name, dimension, dimension_source, sample_vector_head, sample_vector_hash, truncation }`; dimension is **measured**, not configured |
| `POST /embed` | `{ "texts": [...] }` → `{ vectors, model, dimension }`; batch size capped by `MAX_BATCH_SIZE` |

D1 defines the shapes and returns `503` until the model is loaded.

## Configuration

| Variable | Default | Purpose |
|---|---|---|
| `EMBED_MODEL` | `BAAI/bge-small-en-v1.5` | model to load |
| `EMBED_DIM` | `384` | expected dimension; D2/D4 fail hard on mismatch |
| `MAX_BATCH_SIZE` | `100` | maximum texts per `/embed` request |
| `EMBEDDING_PORT` | `8000` | container port (compose/k8s may override the service port) |

The consumer points at `EMBEDDING_SERVICE_URL`.

## Run

```bash
cd services/embedding
python3 -m venv .venv
source .venv/bin/activate
pip install -r requirements-dev.txt
pytest
uvicorn app:app --host 0.0.0.0 --port 8000
```

```bash
curl -i http://localhost:8000/health   # 503 until D2
```

## Container

```bash
docker build -t synanton-embedding:dev services/embedding
docker run --rm -p 8000:8000 synanton-embedding:dev
```

## Design constraints

- **No truncation.** The service emits native model dimensions only.
  `EMBED_TRUNCATE_DIM` is a gpu-plane concern; truncation belongs downstream.
- **No cross-restart cache.** Each request is independent; a hidden persistent
  cache would be a second store the vector payload freeze does not know about.
- **No automatic GPU use.** CPU is the deployment target; the profile decides
  if GPU is ever used.
- **No filesystem paths in `/info`.** `model_name` is enough.
- **Loud startup failure.** A model load or dimension mismatch fails hard
  rather than serving a different dimension.

## Sequence

D1 skeleton → D2 model + measured `/info` (unblocks #348 B3a) → D3 batch
`/embed` → D4 startup validation + integration test → D5 k8s deployment on
node2 and `EMBEDDING_SERVICE_URL` wiring.
