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
pip install -e '.[dev]'
```

## Usage

```bash
# 1. Start the stack (unchanged from the existing PoC demo)
cd ../..
./scripts/run-extract-index-poc.sh

# 2. Confirm the local benchmark datasets referenced by config.yml actually
#    resolve on this machine (see docs/research/.../plan.md §4a)
cd tools/retrieval-eval
retrieval-eval check-config

# 3. Ingest the demo corpus into a tenant and reindex synquest
retrieval-eval ingest --tenant rb-demo

# 4. Run the starter gold query set and score the results
retrieval-eval evaluate \
  --tenant rb-demo \
  --queries ../../demo-data/eval/retrieval-benchmark/queries.jsonl \
  --run-id T03 \
  --dataset-version demo-data-documents-v1 \
  --knowledge-version <manifest schema_version or a corpus content hash> \
  --search-config hybrid-rrf \
  --embedding-model bge-base-en-v1.5 \
  --retrieval-strategy hybrid \
  --reranker none
```

Each `evaluate` run writes a benchmark-run record (Design 1.31 §88 schema) to
`demo-data/eval/retrieval-benchmark/results/<run-id>.yaml`.

## What's implemented vs. what's a stub

| Module | Status |
|---|---|
| `config.py` | Real - loads `config.yml`, resolves `${VAR:-default}`, validates dataset paths exist |
| `metrics.py` | Real - Recall@k, Precision@k, MRR, NDCG@k, aggregation with p95 latency; unit-tested |
| `run_record.py` | Real - writes the Design 1.31 §88 YAML record |
| `gold.py` | Real - loads the same JSONL schema as `flat-vs-semantic-chunks-research-plan.md` |
| `ingest.py` | Real - wraps `synflux`'s actual `/ingest/run` + `/ingest/jobs/{id}` + synquest `/reindex`, matching `run-extract-index-poc.sh` exactly |
| `query.py` | Real - wraps `synquest`'s actual `/search`, using the real `Hit` DTO field names |
| `compose.py` | Real - resolves host ports via `docker compose port`, falling back to the container port like the existing bash scripts do |
| Chunking-strategy switching, reranking, graph rank-fusion | **Not implemented** - these are Phase B2 (see the plan's §2 Modules Touched); nothing in this harness invents them |

## Known gaps in this scaffold (tracked, not silent)

- `queries.jsonl`'s `gold_chunk_ids` are all empty. `content_ref_id` is a UUID
  assigned at ingest time, so real gold chunk IDs can only be filled in after
  running `ingest` once against a real stack and inspecting
  `synvault`'s manifest - this is Phase B0 item 2 in the plan, not done here.
  Until then, `evaluate`'s Recall/NDCG/MRR numbers will legitimately read as
  0 (no gold labels to match), while `gold_section_paths` and latency are
  already meaningful.
- Chunking-strategy selection (`--search-config`) is currently a label you
  pass in, not something this harness switches for you - per
  `flat-vs-semantic-chunks-research-plan.md`, that still means separate
  tenant IDs re-ingested under different `synflux` config today.
- `synquest`'s internal `SearchTrace` (`embedMs/denseMs/lexicalMs/fusionMs`)
  isn't exposed on the `/search` response yet, so `query.py` only measures
  coarse end-to-end latency. Exposing it is one of this plan's own §2
  deliverables.
