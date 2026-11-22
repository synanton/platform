# Local GPU 3-document pilot — 2026-11-21

**Status:** exploratory pilot only. No acceptance or ranking.
**External provider:** GPU-7 gateway stopped; zero external calls.

## Configuration and provenance

| Item | Local 4B | Local 8B FP8 |
|---|---|---|
| Model | `qwen3-extraction` | `local-8b-fp8` |
| Artifact revision | deployed revision not exposed | `220b46e3b2180893580a4454f21f22d3ebb187d3` |
| Runtime image | `local-registry:5000/vllm-openai:v0.29.0` | same digest |
| Context / sequences | 8192 / production | 8192 / 1 |
| Prompt | semantic-extraction-v1 | semantic-extraction-v1 |
| Chunking | full document, one request | full document, one request |
| Temperature | 0 | 0 |
| max_tokens | 4096 | 4096 |

Prompt: `evaluation/dev25/prompts/semantic-extraction-v1.txt`.
Deterministic anchoring and endpoint validation from `extraction_contract.py`.

**Not captured:** GPU peak memory and stable request IDs for these three calls.
Session logs record latency/tokens but not request IDs.

## Per-document metrics

See `local-gpu-3doc-metrics.tsv`.

| Model | Doc | JSON | Entity F1 | Relation F1 | Latency | Completion tok/s |
|---|---|---|---:|---:|---:|---:|
| 4B | 001 | yes | 0.400 | 0.250 | 9.75s | 47.6 |
| 4B | 002 | yes | 0.353 | 0.000 | 13.30s | 48.0 |
| 4B | 003 | yes | 0.609 | 0.000 | 26.08s | 47.3 |
| 8B FP8 | 001 | yes | 0.308 | 0.000 | 26.65s | 46.4 |
| 8B FP8 | 002 | yes | 0.471 | 0.000 | 19.55s | 46.7 |
| 8B FP8 | 003 | yes | 0.476 | 0.000 | 51.49s | 45.7 |

## Rejection breakdown

See `local-gpu-rejections.tsv`. Dominant categories were
`AMBIGUOUS_OR_MISSING_ENDPOINT` for relations and `SURFACE_NOT_FOUND` /
`AMBIGUOUS_SURFACE` for entities. 8B FP8 was slower and did not improve relation
grounding on this sample.

## Interpretation

JSON validity is 3/3 for both, but semantic quality is low and relations are
poorly grounded. Three documents are insufficient for acceptance or ranking.
Preserve 4B as the provisional baseline and 8B as a valid negative finding.

## Operational state

Production vLLM 1/1, health 200; Synflux 2/2; candidate pod removed; GPU-7 stopped.
No HOLDOUT access, production rollout, or external spend.
