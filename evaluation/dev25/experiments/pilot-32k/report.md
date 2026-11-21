# 32K truncation pilot — stopped

**Status:** BLOCKED by gateway/provider timeout; no model result.
**Date:** 2026-11-21.
**Inference:** 15 attempted calls; 0 completed.
**Spend:** $0.0000 (no usage recorded).

## Result

| Outcome | Count |
|---|---:|
| Upstream provider timeout at ~120s | 5 |
| Circuit-open rejection after first failures | 10 |
| Successful responses | 0 |
| JSON/schema results | none |

The live provider metadata was verified before the run:

- `deepseek-v4-pro` advertised output limit: **384,000 tokens**
- therefore 32K passed the preflight.

The first five calls reached `upstream_provider_timeout` after ~120 seconds.
The provider circuit then opened and rejected the remaining calls. No input or
output tokens were recorded.

This is not evidence that 32K fixes or fails truncation. It is an infrastructure
timeout result.

## Artifacts

- `provenance.jsonl` — per-call request/doc/chunk/model IDs, diagnostics.
- `raw/` — empty/missing responses (no successful calls).

## Decision

- Do not expand to all 82 cases.
- Do not claim 32K success or failure.
- Before another pilot, register a gateway/provider timeout experiment or reduce
  the effective output/timeout combination; no calls are authorized now.
