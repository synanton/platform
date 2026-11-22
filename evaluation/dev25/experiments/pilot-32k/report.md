# 32K truncation pilot — stopped

**Status:** BLOCKED by gateway timeout; one upstream completion observed out-of-band.
**Date:** 2026-11-21.
**Inference:** 15 gateway calls; 5 gateway timeouts; 1 upstream completion known.
**Spend:** at least **$0.0741** (provider-observed), not zero.

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
The provider circuit then opened and rejected the remaining calls.

Two of those timeouts were later observed on the provider dashboard as
**successful** requests:

- `failed-04` (`g7-dev-009`): HTTP 200, `stop`, **2m20s**, 4,259 input /
  10,695 output tokens, **$0.0447**, upstream `01a1fdec-c0ef-7240-99b8-c8a8e45a558e`.
- `failed-05` (`g7-dev-010`): HTTP 200, `stop`, **3m22s**, 2,830 input /
  19,870 output tokens, **$0.0741**, upstream `01a1fdee-9547-7aee-96d9-62844448ed10`.

The raw body was not returned through the timed-out gateway call, so JSON/schema
quality cannot be scored. The other four timed-out requests have unknown
upstream outcomes and may have incurred additional cost.

This is not a complete 32K result, but it is evidence that the gateway's 120s
upstream timeout is shorter than the provider's actual generation time, and
that 32K allowed a `stop` at ~19.9K output tokens.

## Artifacts

- `provenance.jsonl` — per-call request/doc/chunk/model IDs, diagnostics.
- `raw/` — empty/missing responses (no successful calls).

## Corrected spend

- Gateway ledger: $0 for `p32k-*` (timeouts were not metered through the Gateway).
- Provider-observed: **at least $0.1188**.
- Unknown: three other timed-out calls may have completed/charged.
- Do not report the pilot as zero-cost.

## Decision

- Do not expand to all 82 cases.
- Do not claim 32K success or failure.
- The next pilot must fix gateway timeout/reconciliation (e.g. timeout > provider
  completion time and `GetStatus` reconciliation) so the raw body is retained.
- No calls are authorized now.
