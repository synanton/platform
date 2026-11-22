# 32K pilot with 600s Gateway timeout — interrupted review

**Status:** STOPPED after 6 calls; pilot FAILED/incomplete.
**Timeout:** `DISPATCH_TIMEOUT_MS=600000` (600s), lease 900s, client 700s.
**Batch rule:** future runs must process no more than **3 documents at once**.

## Results (6 persisted calls)

| Result | Count |
|---|---:|
| Provider success | 6 |
| `finish_reason=stop` | 5 |
| `finish_reason=length` | 1 (`failed-03`) |
| JSON valid | 5 |
| Schema valid | **0** |
| Controls reached | 0 |

All five JSON-valid responses failed the schema/offset/endpoint gate. This is not
a successful pilot; `failed-03` still truncated at 32K, and the control set was
not reached before interruption.

## Cost

- Gateway `p32k-*` ledger: **$0.3499**.
- Provider-observed out-of-band completions from the earlier 120s run: at least
  **$0.2067**.
- These are separate accounting paths; actual spend is at least the sum.
- Remaining $5 ceiling is reduced; no further spend is authorized.

## Third out-of-band completion

The old 120s request `p32k-6384…` (`failed-03`, `g7-dev-008`, chunk 2) completed
upstream after **4m30s**: `stop`, 4,236 input / 23,141 output, **$0.0879**.
The new 600s retry later returned `length` at 32,768 on the same selected chunk.
This variance means a single 32K completion is not sufficient evidence.

## Decision

- Do not expand.
- Do not claim 32K success.
- Fix schema/offset failures and run at most 3 documents per future batch.
- Raw output is retained for the 6 retry calls; no further calls are authorized.
