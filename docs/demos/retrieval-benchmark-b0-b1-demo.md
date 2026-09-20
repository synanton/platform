# Retrieval Evaluation Benchmark - B0/B1 Baseline - Demo Scenario

> **Document type:** Demo scenario / manual QA reproduction guide
> **Title:** Retrieval Evaluation Benchmark - Fixed vs. Semantic Chunking Baseline
> **Status:** Draft
> **Last reviewed:** 2026-09-20
> **Related:** [retrieval-evaluation-benchmark-plan.md](../research/retrieval-evaluation-benchmark-plan.md) (the research plan this demo executes), [`tools/retrieval-eval/README.md`](../../tools/retrieval-eval/README.md) (harness usage reference), [`docs/demos/classification-aware-semantic-search-demo.md`](./classification-aware-semantic-search-demo.md) (sibling demo, same document template)

## 1. Scenario and Goal

**Goal:** Prove that `extraction-gateway` (the `content_extractor` sibling repo) produces genuinely different, structurally-aware chunks when healthy versus when `synflux` falls back to local Tika extraction - and record a reproducible BM25 baseline (Recall@10, NDCG@10, p95 latency) over both.

This demo reproduces Phase B0 (harness + gold-chunk annotation) and Phase B1 (T01/T04 baseline runs) of the research plan above. It exists so a QA specialist can independently re-run and verify the same result without needing to re-derive the investigation from scratch.

**Two tenants, same 16-document corpus, different extraction path:**

| Tenant | `extraction-gateway` | Chunking outcome |
|---|---|---|
| `rb-fixed` | stopped | `synflux` falls back to local Tika - every document becomes one flat `FALLBACK` chunk |
| `rb-semantic` | healthy | Text/markdown files *still* fall back flat (see §7 - a real, documented `content_extractor` limitation, not a bug); the 3 PDF fixtures get real `SECTION`/`TABLE`/`LIST` chunks with real `section_path` values |

**Fixtures added for this demo** (already committed under `demo-data/documents/`, not fetched at test time):

| File | Why it was added |
|---|---|
| `mental-health-report-2010.pdf` | Real, spec-valid PDF with 11 headings across 5 levels and 12 tables - the primary structural-divergence proof |
| `outsourcing-agreement.pdf` | Real PDF with 47 numbered headings (deep hierarchy) |
| `sks8300-web-interface-manual.pdf` | Real PDF with numbered sections, 21 images, 23 lists |

The corpus's *original* PDF, `quarterly-report.pdf`, is a known-bad fixture (missing `xref`/`startxref` - a `platform` demo-data defect, not a `content_extractor` bug) and is expected to fail extraction in both tenants; this is not a regression.

## 2. Prerequisites

- Docker 24+ with Compose V2 (`docker compose version`).
- The `content_extractor` repo checked out as a sibling of `platform` (`../content_extractor`), on a commit that includes the `commons-lang3` dependency fix (`build.gradle.kts`'s `extra["commons-lang3.version"] = "3.18.0"` override) - without it, every text/markdown document fails extraction with a `NoSuchMethodError`, not just falls back gracefully. Confirm with:
  ```bash
  grep commons-lang3.version ../content_extractor/build.gradle.kts
  ```
- Python 3.11+ with `python3 -m venv` available, for `tools/retrieval-eval`.
- `cqlsh` reachable via `docker exec` into the Cassandra container (used in §5 to inspect chunk structure directly - no separate install needed).

## 3. Setup

**Start the stack:**

```bash
cd platform
./scripts/run-extract-index-poc.sh
```

This starts Cassandra, MinIO, `synvault`, `synflux`, `synquest`, and (per `compose.yaml`) `extraction-gateway` as a dependency of `synvault`. Confirm all seven are healthy:

```bash
docker compose -f deployment/docker/compose.yaml ps
```

**Rebuild `extraction-gateway` if `content_extractor` changed since the image was last built:**

```bash
docker compose -f deployment/docker/compose.yaml up -d --build extraction-gateway
```

**Set up the benchmark harness:**

```bash
cd tools/retrieval-eval
python3 -m venv .venv && source .venv/bin/activate
pip install -e '.[dev]'
retrieval-eval check-config   # confirms local dataset paths resolve - informational only for this demo
```

## 4. Walkthrough

| Step | Action | Expected | Marker |
|---|---|---|---|
| 1 | Confirm `extraction-gateway` is healthy: `docker compose -f ../../deployment/docker/compose.yaml ps extraction-gateway` | `STATUS` column shows `healthy` | `[WORKS]` |
| 2 | Stop it: `docker stop docker-extraction-gateway-1` | Container `Exited` | `[WORKS]` |
| 3 | Ingest `rb-fixed`: `retrieval-eval ingest --tenant rb-fixed` | `Ingested 16 documents (errors=0)` | `[WORKS]` |
| 4 | Restart it: `docker start docker-extraction-gateway-1`, wait for `healthy` (poll `docker inspect docker-extraction-gateway-1 --format '{{.State.Health.Status}}'`) | Reaches `healthy` within ~15s | `[WORKS]` |
| 5 | **Confirm DNS is actually stable before ingesting** - run 5x: `docker exec docker-synflux-1 getent hosts extraction-gateway` | Same IP every time, no `Try again`/timeout | `[BLOCKED if flaky - see §7]` |
| 6 | Ingest `rb-semantic`: `retrieval-eval ingest --tenant rb-semantic` | `Ingested 16 documents (errors=0)` | `[WORKS]` |
| 7 | Check `synflux` logs for the ingestion window: `docker logs docker-synflux-1 --since 90s \| grep -iE "extraction\|fallback"` | Exactly **one** fallback line, for `quarterly-report.pdf` only (`db0bb7a9...`, the known-bad fixture) - **zero** fallback lines for the 3 new PDFs or any text/markdown file | `[BLOCKED: see §7 if more than one file fell back]` |
| 8 | Compare chunk structure for `mental-health-report-2010.pdf` between tenants (§5 gives the exact commands) | `rb-fixed`: all `chunk_type=FALLBACK`, empty `section_path`. `rb-semantic`: real `SECTION`/`TABLE`/`LIST` types, `section_path` like `"APPENDIX A: DATA SOURCE DESCRIPTIONS"` | `[WORKS: this is the core proof]` |
| 9 | Run the T01 baseline (BM25-only, `rb-fixed`) - full command in §6 | `mean recall@10=0.900 mean ndcg@10=0.756` | `[WORKS]` |
| 10 | Run the T04 baseline (`rb-semantic`) - full command in §6 | `mean recall@10=0.900 mean ndcg@10=0.736` | `[WORKS]` |

## 5. Structural Divergence - Direct Inspection

Find the `content_ref_id` for `mental-health-report-2010.pdf` under each tenant, then inspect its chunks:

```bash
for tenant in rb-fixed rb-semantic; do
  ref=$(docker exec docker-cassandra-1 cqlsh -e \
    "SELECT content_ref_id FROM ingestion_cache.manifest WHERE tenant_id='$tenant' AND source_uri='file:///demo-data/documents/mental-health-report-2010.pdf' ALLOW FILTERING;" \
    2>&1 | sed -n '4p' | tr -d ' ')
  echo "=== $tenant ($ref) ==="
  docker exec docker-cassandra-1 cqlsh -e \
    "SELECT chunk_ordinal, section_path, chunk_type FROM ingestion_cache.chunks_payload WHERE tenant_id='$tenant' AND content_ref_id=$ref;"
done
```

**Expected for `rb-fixed`:** every row `chunk_type=FALLBACK`, `section_path` empty.
**Expected for `rb-semantic`:** rows with `chunk_type` in `SECTION`/`TABLE`/`LIST`, `section_path` populated with real headings such as `CONTENTS` and `APPENDIX A: DATA SOURCE DESCRIPTIONS`.

If both tenants show `FALLBACK` for this file, `extraction-gateway` was not actually healthy/reachable during the `rb-semantic` ingest - re-run from step 4, and don't skip step 5's DNS check.

## 5a. Resetting a Tenant (if a step needs to be re-run)

Ingestion is idempotent by content hash - re-running `retrieval-eval ingest --tenant X` against a tenant that already has this corpus ingested reports `Ingested 0 documents` and changes nothing, even if the prior ingest used a broken `extraction-gateway`. To force a genuine re-ingest, wipe the tenant's rows first:

```bash
TENANT=rb-semantic   # or rb-fixed

REFS=$(docker exec docker-cassandra-1 cqlsh -e \
  "SELECT content_ref_id FROM ingestion_cache.manifest WHERE tenant_id='$TENANT' ALLOW FILTERING;" \
  2>&1 | tail -n +4 | head -n -2 | tr -d ' ')

CQL=""
for ref in $REFS; do
  CQL+="DELETE FROM ingestion_cache.manifest WHERE tenant_id='$TENANT' AND content_ref_id=$ref;\n"
  CQL+="DELETE FROM ingestion_cache.chunks_payload WHERE tenant_id='$TENANT' AND content_ref_id=$ref;\n"
done
echo -e "$CQL" > /tmp/wipe_$TENANT.cql
docker cp /tmp/wipe_$TENANT.cql docker-cassandra-1:/tmp/wipe_$TENANT.cql
docker exec docker-cassandra-1 cqlsh -f /tmp/wipe_$TENANT.cql

# confirm empty:
docker exec docker-cassandra-1 cqlsh -e \
  "SELECT count(*) FROM ingestion_cache.manifest WHERE tenant_id='$TENANT' ALLOW FILTERING;"
```

Then re-run the ingest step for that tenant from §4.

## 6. Baseline Run Commands (Acceptance Assertions)

```bash
cd tools/retrieval-eval

retrieval-eval evaluate \
  --tenant rb-fixed \
  --queries ../../demo-data/eval/retrieval-benchmark/queries.rb-fixed.jsonl \
  --run-id T01 \
  --dataset-version demo-data-documents-v2-16docs \
  --knowledge-version rb-fixed-$(date +%Y-%m-%d) \
  --search-config bm25-only \
  --embedding-model none \
  --retrieval-strategy bm25 \
  --reranker none \
  --top-k-dense 0

retrieval-eval evaluate \
  --tenant rb-semantic \
  --queries ../../demo-data/eval/retrieval-benchmark/queries.rb-semantic.jsonl \
  --run-id T04 \
  --dataset-version demo-data-documents-v2-16docs \
  --knowledge-version rb-semantic-$(date +%Y-%m-%d) \
  --search-config hybrid-rrf-no-embed \
  --embedding-model none \
  --retrieval-strategy hybrid \
  --reranker none \
  --top-k-dense 0
```

**Both must print** `mean recall@10=0.900` (9 of 10 gold queries are answerable; `rb009` is a deliberately unanswerable negative test and always scores 0). NDCG@10 will be close to but not necessarily identical between runs (`0.756` / `0.736` observed) - this reflects tie-breaking over different per-tenant chunk UUIDs, **not** a meaningful quality signal, since none of the 10 gold queries target the 3 structurally-rich PDFs yet (see the research plan's Open Question 0).

Run records land in `demo-data/eval/retrieval-benchmark/results/T01.yaml` and `T04.yaml` - diff against a prior run to catch regressions.

## 7. What This Demo Proves / Does Not Prove Yet

| Claim | Proved by | Notes |
|---|---|---|
| `extraction-gateway` extracts real headings/tables/lists from spec-valid PDFs | §5, `rb-semantic` column | Requires the `commons-lang3` fix (§2) - without it, this fails with a silent-to-the-operator `NoSuchMethodError` |
| Chunking genuinely diverges (Fixed vs. Semantic) when structure is present | §5 comparison | Only true for the 3 PDF fixtures - see limitation below |
| BM25 retrieval baseline is reproducible | §6, both runs | `top_k_dense=0` is required - dense retrieval is unreachable in this stack (see limitation below) |
| Benchmark-run records persist per Design 1.31 §88's schema | `results/T01.yaml`/`T04.yaml` contents | |

**Known, documented limitations - not defects in this demo:**

- **`content_extractor`'s text/markdown adapter does not parse markdown headings.** `structured-supply-chain.md` (the corpus's one hand-written file with real `#`/`##`/`###` markdown headings) still produces a single flat `FALLBACK` chunk under `rb-semantic`, identical to `rb-fixed`. This is why the 3 PDF fixtures were added - they're the only documents in this corpus where structural divergence can currently be demonstrated. See the research plan's Open Question 0a.
- **Dense/hybrid retrieval is unreachable in this stack.** `synquest`'s embedding client always calls out to a vLLM service that only exists behind `docker compose --profile phase2` (2×8GB GPUs), which isn't running here. Every `/search` response reports `query_usage.embed_skipped=true`. `--top-k-dense 0` is not optional in §6's commands - it's documenting reality, not an arbitrary choice. T02 (dense-only) and T03 (hybrid) from the research plan's test matrix are **blocked**, not run, pending the Phase 2 profile.
- **`quarterly-report.pdf` fails in both tenants.** This is `platform` demo-data's own pre-existing defect (invalid PDF structure), unrelated to anything in this demo - do not treat it as a new bug.

## 8. Troubleshooting

| Symptom | Likely cause | Fix |
|---|---|---|
| Every text/markdown file in the ingestion log shows `UNAVAILABLE: Unable to resolve host extraction-gateway` or `UNAVAILABLE: io exception` right after step 4/6 | Transient DNS/connection race immediately after `extraction-gateway` restarts - the container reports `healthy` before its address is reliably resolvable/reachable from `synflux` | Wait ~5-10s after `healthy`, re-run step 5's DNS check 5x in a row before ingesting; if it's still flaky, wipe the tenant per §5a and retry the whole ingest |
| `rb-semantic` and `rb-fixed` show identical `FALLBACK`-only chunking for **every** document, including the 3 PDFs | Either the DNS race above hit mid-ingest (check step 7's log grep), or `extraction-gateway` was never actually healthy during this ingest | Re-check `docker compose ps extraction-gateway`; wipe `rb-semantic` per §5a and re-ingest |
| `ExtractSync` returns `STATUS_FAILED` with diagnostic `'...SystemProperties.getUserName(String)'` | `content_extractor` checkout predates the `commons-lang3` fix | `grep commons-lang3.version ../content_extractor/build.gradle.kts` - if absent, pull the fix and rebuild the image (§3) |
| `retrieval-eval ingest --tenant X` reports `Ingested 0 documents` on a re-run | Ingestion is idempotent by content hash - the tenant already has this exact corpus ingested (possibly with stale/bad data from a prior failed attempt) | Wipe the tenant per §5a, then re-ingest |
| `evaluate` reports `recall@10=0.000` for every query | Wrong `--queries` file for the tenant (gold chunk IDs are tenant-specific - `content_ref_id` is a fresh UUID per ingestion run) | Use `queries.rb-fixed.jsonl` with `--tenant rb-fixed` and `queries.rb-semantic.jsonl` with `--tenant rb-semantic` - never cross them |
| `evaluate`/`inspect` connection errors | `synquest`/`synflux` not reachable on the expected host ports | Confirm `docker compose ps` shows both healthy; the harness resolves ports via `docker compose port`, falling back to the container's default port if compose isn't reachable from where the harness runs |
