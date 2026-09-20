# Retrieval Evaluation Benchmark harness

Phase B0 scaffold for the SNTP-9 / Issue #14 benchmark. Full rationale, test
matrix, and methodology: [`docs/research/retrieval-evaluation-benchmark-plan.md`](../../docs/research/retrieval-evaluation-benchmark-plan.md).

This harness does not start or replace the platform's own compose stack - it
drives the same `synflux`/`synquest` REST APIs
`scripts/run-extract-index-poc.sh` already uses, against whatever stack you
already started.

## Setup

```bash
cd tools/retrieval-eval
uv venv --seed --python 3.12
source .venv/bin/activate
pip install -e '.[dev]'
```
Folder `retrieval_eval.egg-info` will be created. 

## Usage

### 1. Start the stack (unchanged from the existing PoC demo)

```bash
cd ../..
./scripts/run-extract-index-poc.sh
```

This starts Cassandra, MinIO, `synvault`, `synflux`, and `synquest`. It does
**not** start the content extractor - `synflux` falls back to a local Tika
extractor by default (`EXTRACTION_CLIENT_ENABLED=true`, fallback policy
`FALLBACK_LOCAL_TIKA`), so the stack above is enough to run the benchmark.

#### Optional: start the content extractor for structured extraction

The extraction engine lives in the separate `content_extractor` repo (this
repo only ships its client library, `java/extraction-client`) and is built
and run independently - it's no longer part of this repo's compose file.
Start the stack above first so the `synanton-demo` network and the `postgres`/
`minio` containers it depends on already exist, then from the
`content_extractor` checkout:

```bash
cd ../../../content_extractor
docker build -t docker-extraction-gateway -f deployment/docker/extraction-gateway.Dockerfile .
docker run -d --name extraction-gateway --network synanton-demo \
  -e EXTRACTION_DB_URL=jdbc:postgresql://postgres:5432/synanton \
  -e EXTRACTION_DB_USER=synanton \
  -e EXTRACTION_DB_PASSWORD=changeme \
  -e EXTRACTION_GATEWAY_GRPC_PORT=9091 \
  -e EXTRACTION_GATEWAY_HTTP_PORT=8092 \
  -e EXTRACTION_OBJECTSTORE_ENDPOINT=http://minio:9000 \
  -e EXTRACTION_OBJECTSTORE_ACCESS_KEY=minioadmin \
  -e EXTRACTION_OBJECTSTORE_SECRET_KEY=minioadmin \
  -p 9091:9091 -p 8092:8092 \
  docker-extraction-gateway
```

`synflux` reaches it at `extraction-gateway:9091` by container name on that
shared network - no restart of the platform stack needed once it's healthy.
Adjust the `EXTRACTION_DB_*`/`EXTRACTION_OBJECTSTORE_*` values above if your
`platform` checkout overrides the default `POSTGRES_*`/`MINIO_ROOT_*` env vars.

### 2. Confirm the local benchmark datasets referenced by config.yml actually resolve on this machine (see docs/research/.../plan.md §4a)

```bash
cd tools/retrieval-eval
retrieval-eval check-config
```

### 3. Ingest the demo corpus into a tenant and reindex synquest

```bash
retrieval-eval ingest --tenant rb-demo
```

### 4. Run the starter gold query set and score the results

```bash
retrieval-eval evaluate \
  --tenant rb-demo \
  --queries ../../demo-data/eval/retrieval-benchmark/queries.jsonl \
  --run-id T03 \
  --dataset-version demo-data-documents-v1 \
  --knowledge-version demo-data-documents-v1 \
  --search-config hybrid-rrf \
  --embedding-model bge-base-en-v1.5 \
  --retrieval-strategy hybrid \
  --reranker none
```

`--knowledge-version` is a free-form label you choose (Design 1.31 §88) - it
just needs to identify which ingested corpus snapshot this run was scored
against, e.g. `synvault`'s manifest `schema_version` or a hash of the corpus
content, so two runs are comparable only when this value matches. The demo
corpus above is static, so reusing `--dataset-version`'s value is fine here.

Each `evaluate` run writes a benchmark-run record (Design 1.31 §88 schema) to
`demo-data/eval/retrieval-benchmark/results/<run-id>.yaml`.

### 5. Shut down the stack when you're done

```bash
cd ../..
./scripts/shutdown-extract-index-poc.sh
```

This stops and removes `synvault`, `synflux`, `synquest`, `cassandra`,
`minio`, `postgres`, and the `synanton-demo` network (plus the standalone
`extraction-gateway` container, if it's running). Named volumes (Cassandra/
MinIO/Postgres data) are left in place by default so a later
`run-extract-index-poc.sh` picks up where you left off - pass `--volumes` to
also wipe them for a full reset:

```bash
./scripts/shutdown-extract-index-poc.sh --volumes
```

To suspend the stack instead of removing it (keep containers, free up CPU/
memory, resume faster next time), use `docker compose stop` directly rather
than this script:

```bash
docker compose -f deployment/docker/compose.yaml stop
docker stop extraction-gateway 2>/dev/null || true
# later:
docker compose -f deployment/docker/compose.yaml start
docker start extraction-gateway 2>/dev/null || true
```

## What's implemented vs. what's a stub

| Module                                                    | Status                                                                                                                                 |
|-----------------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------|
| `config.py`                                               | Real - loads `config.yml`, resolves `${VAR:-default}`, validates dataset paths exist                                                   |
| `metrics.py`                                              | Real - Recall@k, Precision@k, MRR, NDCG@k, aggregation with p95 latency; unit-tested                                                   |
| `run_record.py`                                           | Real - writes the Design 1.31 §88 YAML record                                                                                          |
| `gold.py`                                                 | Real - loads the same JSONL schema as `flat-vs-semantic-chunks-research-plan.md`                                                       |
| `ingest.py`                                               | Real - wraps `synflux`'s actual `/ingest/run` + `/ingest/jobs/{id}` + synquest `/reindex`, matching `run-extract-index-poc.sh` exactly |
| `query.py`                                                | Real - wraps `synquest`'s actual `/search`, using the real `Hit` DTO field names                                                       |
| `compose.py`                                              | Real - resolves host ports via `docker compose port`, falling back to the container port like the existing bash scripts do             |
| Chunking-strategy switching, reranking, graph rank-fusion | **Not implemented** - these are Phase B2 (see the plan's §2 Modules Touched); nothing in this harness invents them                     |

## Known gaps in this scaffold (tracked, not silent)

- **Resolved (2026-09-20):** `gold_chunk_ids` are now annotated with real
  chunk IDs against live `rb-fixed`/`rb-semantic` tenants - see
  `demo-data/eval/retrieval-benchmark/queries.rb-fixed.jsonl` and
  `queries.rb-semantic.jsonl` (the base `queries.jsonl` is the un-annotated
  template; annotate against a fresh tenant with `retrieval-eval inspect`).
  IDs differ per tenant since `content_ref_id` is a fresh UUID per ingestion
  run, even for the same source file.
- Chunking-strategy selection (`--search-config`) is currently a label you
  pass in, not something this harness switches for you - per
  `flat-vs-semantic-chunks-research-plan.md`, that still means separate
  tenant IDs re-ingested under different conditions (`extraction-gateway`
  up vs. down - see the research plan §4 for why that's the actual lever
  today, not a `synflux` config flag).
- `synquest`'s internal `SearchTrace` (`embedMs/denseMs/lexicalMs/fusionMs`)
  isn't exposed on the `/search` response yet, so `query.py` only measures
  coarse end-to-end latency. Exposing it is one of this plan's own §2
  deliverables.
- **Dense retrieval is unreachable in the Phase 1 stack** - `EMBED_BASE_URL`
  points at a vLLM service that only exists behind `--profile phase2`
  (2×8GB GPUs). Every query reports `query_usage.embed_skipped=true`;
  `--top-k-dense`/`--top-k-lexical` exist (pass `0` to suppress dense, `1`
  - not `0` - to suppress lexical, since `synquest`'s Lucene lexical path
  rejects `n=0`) but there's nothing for `--top-k-dense` to suppress until
  Phase 2 is running.
