# Local GPU 3-document pilot — 2026-11-21

**Status:** exploratory pilot only. No acceptance or ranking.
**Input:** `g7-dev-001..003` (3 small DEV docs), semantic proposal prompt,
deterministic surface anchoring and endpoint validation.
**External provider:** GPU-7 gateway stopped; no external calls.

## Results

| Model | JSON valid | Mean entity F1 | Mean relation F1 | Latency (s) |
|---|---:|---:|---:|---|
| Local 4B production | 3/3 | 0.454 | 0.083 | 9.7 / 13.3 / 26.1 |
| Local 8B FP8 | 3/3 | 0.418 | 0.000 | 26.6 / 19.5 / 51.5 |

The 8B candidate did not improve this small sample; it was slower and produced
more rejected endpoint/evidence proposals. Entity and relation quality remain
low for both.

## Interpretation

- Deterministic anchoring worked: invalid surfaces/endpoints were rejected, not
  silently shifted.
- JSON validity alone does not indicate useful extraction.
- Three documents are insufficient for acceptance or model ranking.
- GPU peak memory and throughput were not captured in this pilot.

## Operational state

- Production vLLM restored: 1/1, `/health=200`.
- Synflux: 2/2.
- Candidate pod removed; GPU-7 external gateway stopped.
- No HOLDOUT access, production rollout, or external spend.

## Evidence

- `local-4b-3docs.json`
- `local-8b-fp8-3docs.json`

No further inference is authorized by this report.
