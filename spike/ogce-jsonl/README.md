# OGCE JSONL vertical-slice spike

This is a spike, not production code. It tests whether the OGCE JSONL line
contract survives truncation, malformed lines, anchor reordering, and explicit
partial coverage better than monolithic JSON.

**Non-claims:** no extraction quality, F1, cost, p95/G-10, or profile
qualification. The run used node2 RTX 4060 Ti (CC 8.9), not the pinned Blackwell
RTX 5060 Ti. The ADR-022 frozen gold is reserved and was not used.

## Layout

| Path | Purpose |
|---|---|
| `run_slice.py` | Chunker, JSONL parser, validation ladder, model client, experiments, report generator. |
| `run_variance.py` | n=10 latency, 3 seeds, 5 DEV document texts, Tier 2 JSONL grammar probe. |
| `fixtures/sample-doc.md` | Synthetic 2,936-token document with 18 headings, 28 list items, entities, and relations. |
| `fixtures/dev/` | Five non-frozen DEV document texts; see `PROVENANCE.md`. No labels used. |
| `schema/ontology-subset.json` | 3 classes, 2 relations. |
| `prompts/extraction_prompt.txt` | Prompt-only JSONL extraction prompt. |
| `pod.yaml` | Disposable node2 pod spec (read-only model hostPath, 1 GPU). |
| `results/` | Run evidence: `results.json`, `raw_responses.jsonl`, `report.md`, model hashes, GPU and load logs. |
| `results/variance/` | n=10 latency, seed, multi-doc, and Tier 2 grammar evidence. |
| `T9-writeup.md` | What was tested, results, capability matrix, recommendation, and non-claims. |

## Run

```bash
# from this directory, inside the disposable pod
python3 run_slice.py --self-test
python3 run_slice.py \
  --base-url http://127.0.0.1:8000 \
  --model qwen3-8b-fp8 \
  --model-path /models \
  --doc fixtures/sample-doc.md \
  --out results

python3 run_variance.py
```

The pod spec mounts `/mnt/local-fast/models/qwen3-8b-fp8` read-only at `/models`
and is pinned to node2. Do not reuse it against a production-occupied GPU.
The authorization and scope deviation are recorded in issue #814.

## Result

All five experiments pass on the expanded document:

- E1 truncation: JSONL retained 100% of pre-cut accepted facts at 25/50/75%;
  monolithic JSON retained 0.
- E2 malformed line: only the corrupted line was newly quarantined; neighbours
  were unaffected.
- E3 anchors: identical after line shuffle.
- E4 coverage: forced skip emitted `PARTIAL` with counts.
- E5 boundaries: zero segment/chunk violations across 12 segments and 6 chunks.

The runtime matrix found that vLLM 0.29 **ignores** `guided_json`,
`guided_regex`, and `guided_grammar`, but **honors** `structured_outputs`,
including a two-line JSONL grammar. The variance runs add n=10 latency, three
seeds, and five DEV document texts. See `T9-writeup.md` for the segment
histogram, undersized-tail inventory, list-handling finding, full matrix, and
hardening list.
