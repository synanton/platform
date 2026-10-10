# Benchmark Runner runbook (DOC-D1.1 + D1.2)

**Covers:** author a manifest, invoke the runner, interpret results, recover
from failure (D1.1) + failure modes and diagnostics (D1.2, §§5–6 below).
**Validated:** followed verbatim end-to-end on a live stack (see validation log
at the end). If a step fails for you, it is a bug in this doc — file it.

---

## 0. Prerequisites

- Python ≥ 3.11, `jsonschema` pip package importable.
- A synquest service URL for live runs (simulated runs need nothing).
- Corpus files locally readable (for hash verification).

## 1. Setup (no install needed)

The runner runs from the repo without installation (PEP 668 forbids bare
`pip install -e` on externally-managed systems — do not document that path):

```bash
cd platform/tools/benchmark-runner
export PYTHONPATH=$PWD
python3 -m benchmark_runner.cli --help
```

All commands below assume `PYTHONPATH` is set and run from that directory,
unless stated otherwise.

## 2. Author a manifest

Copy the minimal example from `docs/benchmark/manifest-reference.md`
(§ Minimal example) or trim `schemas/benchmark/fixtures/six-composition.json`.
Required anatomy: `manifest_id`, `schema_version` (`0.2.0-draft` until Week-2
freeze), `corpus.ref` as `cas:<64-hex>`, ≥1 entry in `compositions` with
`composition_id` + `metadata_provider` + `vector_provider` + `embedding` +
`execution`, `metrics` (recall@10, eligible identity, p95, repro flags,
topology all on for B5), `reproducibility.seed`, `output_sink.file`.

Secrets go in `${VAR}` references resolved from the environment (see
`.env.default`); never inline secrets — manifests are shareable artifacts.

## 3. Validate (no execution)

```bash
python3 -m benchmark_runner.cli validate <manifest.json>
# exit 0: prints manifest id, corpus ref, composition list
# exit 2: prints `manifest schema violation at '<field.path>': <reason>`
```

Fix the named field and re-run. Common rejections: missing `compositions`,
`corpus.ref` not matching `^cas:[0-9a-f]{64}$`, unknown provider enum,
`recall_at_k` as `"10"` (string) instead of `[10]`.

## 4. Verify reproducibility preconditions

```bash
python3 -m benchmark_runner.cli verify <manifest.json> --corpus <path>
# exit 0: prints `reproducibility verified: corpus <hash12>… seed <n>`
# exit 3: hash mismatch (lists expected vs actual) or missing seed/path
```

The corpus path may be a file (sha256 over bytes) or directory (sha256 over
sorted relative-path + bytes pairs). `--corpus` must be the tree that was (or
will be) ingested — `demo-data/documents` for the demo runs.

## 5. Failure modes and diagnostics (DOC-D1.2)

| Symptom | Cause | Fix |
|---|---|---|
| `manifest not found: …` (exit 2) | Wrong path | Check path; relative paths resolve from CWD |
| `not valid JSON` (exit 2) | Malformed file | `python3 -m json.tool` locates the syntax error |
| `schema violation at '<path>'` (exit 2) | Field missing/wrong type/enum | Read the dotted path; see `manifest-reference.md` for the field |
| `corpus hash mismatch` (exit 3) | Corpus changed since manifest authored, or wrong `--corpus` | Re-hash the intended tree; update `corpus.ref` (pre-freeze) or fix the path |
| `seed is missing` (exit 3) | No reproducibility claim possible | Add integer `seed` |
| `endpoint unreachable` (run) | Synquest down / wrong URL | `probe` the endpoint (below); check `:8083/actuator/health` |
| `search failed: HTTP 5xx` (run) | Service-side error | Check service logs; rerun one query with `top_k_dense: 0` to isolate BM25 |
| `timeout after N ms` (exit 4) | `timeout_ms` too tight or service stalled | Raise `timeout_ms` or investigate service latency |
| `event count … exceeded limit` (exit 4) | `max_event_count` below workload | Raise the limit (manifest-configurable) or shrink the query set |
| Empty `hits` on every query | Wrong tenant, empty index, or (dense) missing embeddings | Check tenant; `results show` timing_scope; service `/index/stats` for vector coverage |
| `NoSuchDirectory` on fresh runs | Removed failure mode — empty stores return empty results since BR-A2 | N/A (regression guard in tests) |

## 6. Recovery procedures

- **Hash mismatch mid-campaign:** do not edit results to match. Re-hash the
  true corpus tree, issue a new `manifest_id` (manifests are write-once in
  spirit), re-run. History stays comparable by `dataset_version`, not by file.
- **Partial run (timeout/guard):** artifacts written before the abort stand
  (atomic per-composition writes — no partial files ever exist). Fix the
  control, re-run; each composition artifact is independent.
- **Service outage mid-run:** same as above — completed compositions keep valid
  artifacts; resume with the same manifest (deterministic seed ⇒ same queries).

## 7. Run

```bash
# Dry run first (validates, prints plan, writes nothing):
python3 -m benchmark_runner.cli run <manifest.json> --dry-run

# Simulated (no backend needed):
python3 -m benchmark_runner.cli run <manifest.json> --corpus <path> \
  --queries queries.json --out runs/<id> --executor simulated

# Live synquest (BM25-only without an embedding service: top_k_dense=0):
python3 -m benchmark_runner.cli run <manifest.json> --corpus <path> \
  --queries queries.json --out runs/<id> \
  --executor synquest --endpoint http://localhost:8083
```

Queries file: JSON array of `{query_id, tenant?, text?, gold[], eligible[],
top_k?, top_k_dense?, top_k_lexical?, rrf_k?, execution_mode?}`.
Exit codes: 0 ok · 2 manifest error · 3 reproducibility failure · 4 cost control.

## 8. Interpret results

```bash
python3 -m benchmark_runner.cli results list --out runs/<id>
python3 -m benchmark_runner.cli results show --out runs/<id> --run-id <run_id>
```

Read `metrics_summary` per `docs/benchmark/metric-taxonomy.md`: null means
unmeasured (never zero-as-default); `recall_at_k` needs gold sets or it is
null by construction; latencies exclude `structural_empty` legs; every query
carries `topology` — a metric without one is uninterpretable across
compositions. `reproducibility.verified: false` means the corpus claim is
stated, not checked — see §6 before citing such runs.

## Validation log (fresh-eyes run, 2026-10-09)

Followed §§1–8 verbatim in a clean shell against live synquest (:8083):
validate ✓ → verify vs demo-data ✓ → dry-run (empty out dir confirmed) ✓ →
simulated run ✓ → live BM25 run, 10 queries ✓ → list/show ✓. Two doc fixes
applied during validation (PEP 668 setup path; `--executor` flag documented).
No step required knowledge outside this doc.
