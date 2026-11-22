# OGCE JSONL vertical slice — spike result

**Run:** 2026-11-22T15:34:07Z  
**Endpoint:** `http://127.0.0.1:8000` / `qwen3-8b-fp8`  
**Document:** `/spike/ogce-jsonl/fixtures/sample-doc.md` — 15305 chars, 2936 tokens, 18 headings, 28 list items  
**Scope:** contract robustness only; not F1/quality, not p95, not profile qualification, not ADR-044 transition.

## T1 — smoke

| Check | Result |
|---|---|
| Model call | PASS |
| Content | `OK` |
| Finish reason | `stop` |
| Latency | 0.107 s |
| Output SHA-256 | `565339bc4d33d72817b583024112eb7f5cdf3e5eef0252d6ec1b9c9a94e12bb3` |

## T2 — runtime capability matrix

| Probe | HTTP OK | Lines | JSON valid | Enforcement | Latency | Notes |
|---|---|---|---|---|---|---|
| baseline | True | 3 | False | prompt-only | 3.195 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| response_format_json_object | True | 1 | True | single-object | 0.894 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| response_format_json_schema | True | 1 | True | schema-object | 0.888 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| response_format_json_schema_strict | True | 1 | True | schema-object | 0.892 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| guided_json | True | 3 | False | accepted-not-enforced | 3.187 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| guided_regex | True | 3 | False | accepted-not-enforced | 3.195 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| guided_grammar | True | 3 | False | accepted-not-enforced | 3.185 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| structured_outputs_json | True | 1 | True | schema-object | 0.887 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| structured_outputs_regex | True | 1 | False | regex-match | 3.192 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| structured_outputs_grammar | True | 1 | True | grammar-object | 0.259 | {"type":"entity"} |
| structured_outputs_jsonl_grammar | True | 2 | False | jsonl-grammar | 1.011 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| stop | True | 3 | False | stop-accepted | 3.193 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| logit_bias | True | 3 | False | accepted-not-observable | 3.187 | {"type":"entity","surface":"Acme Corporation","class":"Organ |
| seed_repeat | True |  |  | deterministic |  | identical=True |

## T4/T5 — chunker and schema

- Retrieval chunks: **6**; extraction segments: **12**; chunk/segment ratio: **2.0×**.
- Segment token range: **181–286**; overlaps: [0, 36, 38, 40, 41, 42, 43, 44, 48].
- Undersized tails: [{"segment": 11, "tokens": 181, "reason": "MERGE_WOULD_EXCEED_384"}].
- List observation: [{"list_items": 10, "span": [7032, 7654], "chunks": [2], "segments": [5], "atomic_in_one_chunk": true, "atomic_in_one_segment": true}, {"list_items": 12, "span": [10390, 11166], "chunks": [3, 4], "segments": [7, 8], "atomic_in_one_chunk": false, "atomic_in_one_segment": false}, {"list_items": 6, "span": [14446, 14855], "chunks": [5], "segments": [10, 11], "atomic_in_one_chunk": true, "atomic_in_one_segment": false}].
- Repeat-format check: {'segment': 1, 'format_equal': True, 'first_lines': 15, 'repeat_lines': 15}.

### Segment size histogram

| Band | Count |
|---|---|
| <64 | 0 |
| 64-127 | 0 |
| 128-191 | 1 |
| 192-255 | 0 |
| 256-319 | 11 |
| 320-383 | 0 |
| 384-511 | 0 |
| >=512 | 0 |
## T6/T7 — calls, parsing, validation

- Lines returned: **164**; accepted: **128**; quarantined: **36**.
- Quarantine reasons: `{"AMBIGUOUS_ANCHOR": 14, "CLASS_MISMATCH": 5, "JSON_PARSE": 1, "SURFACE_NOT_IN_EVIDENCE": 11, "UNANCHORED": 5}`.
- Segments with fences stripped: **0**.

## T8 — proof experiments

| Experiment | Claim | Result | Detail |
|---|---|---|---|
| E1 truncation | C2 | PASS | {"percent": 50, "cut_line": 8, "expected_pre_cut": 7, "jsonl_accepted": 7, "jsonl_retention": 1.0, "jsonl_quarantined": 1, "monolithic_accepted": 0, "monolithic_error": "JSON_PARSE: Extra data: line 2 column 1 (char 154)"} |
| E2 malformed line | C3 | PASS | {"pass": true, "corrupted_line": 3, "quarantined_lines": [3], "baseline_quarantined_lines": [], "new_quarantines": [3], "expected_quarantine": [3], "accepted_before": 15, "accepted_after": 14, "others_unaffected": true} |
| E3 anchoring order | C4 | PASS | {"pass": true, "accepted_before": 15, "accepted_after": 15, "anchors_identical": true} |
| E4 coverage | C5 | PASS | {"eligible_segments": 12, "processed_segments": 11, "skipped_segments": 1, "lines_returned": 164, "lines_accepted": 128, "lines_quarantined": 36, "coverage": "PARTIAL"} |
| E5 chunk boundary | C1 | PASS | violations=0 |

## E1 detail

| Truncation | Expected pre-cut | JSONL accepted | JSONL retention | Monolithic accepted |
|---|---|---|---|---|
| 25% (line 4) | 3 | 3 | 100.00% | 0 |
| 50% (line 8) | 7 | 7 | 100.00% | 0 |
| 75% (line 11) | 10 | 10 | 100.00% | 0 |

## GPU and model-load evidence

- Before: `{'gpu': 'NVIDIA GeForce RTX 4060 Ti, GPU-a004ce09-4df3-3098-a6dc-1d11175ecf30, 16380 MiB, 12601 MiB, 0 %', 'compute_apps': '351, VLLM::EngineCore, 12580 MiB'}`
- After: `{'gpu': 'NVIDIA GeForce RTX 4060 Ti, GPU-a004ce09-4df3-3098-a6dc-1d11175ecf30, 16380 MiB, 12601 MiB, 100 %', 'compute_apps': '351, VLLM::EngineCore, 12580 MiB'}`
- `(EngineCore pid=351) INFO 11-22 15:03:37 [model_runner.py:404] Model loading took 8.9 GiB memory and 2.926746 seconds`
- `(EngineCore pid=351) INFO 11-22 15:04:12 [gpu_worker.py:625] Available KV cache memory: 3.11 GiB`
- `(EngineCore pid=351) INFO 11-22 15:04:12 [kv_cache_utils.py:2032] GPU KV cache size: 22,640 tokens, Maximum concurrency for 8,192 tokens per request: 2.76x`
- `(EngineCore pid=351) INFO 11-22 15:04:13 [gpu_worker.py:860] Free memory on device (15.52/15.66 GiB) on startup. Desired GPU memory utilization is (0.85, 13.31 GiB). Actual usage is 9.0 GiB for consumed memory (weights + non-torch), 1.2 GiB for peak activation, and 0.0 GiB for CUDAGraph memory. Replace gpu_memory_utilization config with `--kv-cache-memory=3177553511` (2.96 GiB) to fit into requested memory, or `--kv-cache-memory=5551994880` (5.17 GiB) to fully utilize gpu memory. Current kv cache memory in use is 3.11 GiB.`
- `(EngineCore pid=351) INFO 11-22 15:04:16 [core.py:361] init engine (profile, create kv cache, warmup model) took 39.65 s (compilation: 31.47 s)`

## What this proves

- C1: segments nest inside retrieval chunks; E5 reports zero boundary violations (or the exact violations).
- C2: line-delimited parsing survives truncation while the monolithic parse loses the response.
- C3: a malformed line is quarantined without poisoning its neighbours.
- C4: anchors are derived from text, not line position; shuffled order produces identical anchors.
- C5: skipped segments produce `PARTIAL` with explicit counts.

## What this does not prove

- No extraction-quality, precision/recall, F1, cost, throughput, or p95 claim.
- No G-10 p95 measurement; no repeated synthetic-request approval is implied.
- No pinning or validation of the Blackwell RTX 5060 Ti profile. This run used node2 RTX 4060 Ti as an authorized scope deviation.
- No ADR-044 status transition; no gate is marked Met or Missed.

## Recommendation

Promote the chunker, parser, and validation ladder into P0-1/P0-3/P1-1 only after the hardening items in the T9 write-up are addressed. The contract experiments are the promotion evidence; the model-quality and profile questions remain open.
