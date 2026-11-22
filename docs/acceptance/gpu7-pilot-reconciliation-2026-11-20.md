# GPU-7 pilot reconciliation — 2026-11-20

**Scope:** baseline reconciliation only. No new inference, no production change.
**Pilot status:** exploratory. These numbers are not Phase 2 acceptance results.

## 1. Identifier reconciliation

There is no verified OpenCode Zen model named “DeepSeek V4.1 Flash Pro.”

| Name used informally | Provider ID tested | Meaning |
|---|---|---|
| DeepSeek V4 Pro | `deepseek-v4-pro` | Paid V4 Pro candidate; 5/5 JSON-valid in the 5-document pilot. |
| DeepSeek V4.1 Flash | `deepseek-v4.1-flash` | Separate paid Flash candidate; 2/5 JSON-valid in the 5-document pilot. |
| DeepSeek V4.1 Flash Free | `deepseek-v4.1-flash-free` | Free variant; provider rate-limited/circuit-open in the pilot. |

The provider usage line “DeepSeek V4.1 Flash 1017” is the **V4.1 Flash** family,
not V4 Pro. Do not aggregate those results.

## 2. GLM validity reconciliation

- **Initial 4/5** is the five-document DEV evaluation under the registered
  no-thinking/low-reasoning settings.
- The cumulative **10 calls** include five exploratory GLM calls outside that
  standard five-document set.
- The exploratory requests were mostly length/no-content attempts; one
  no-thinking probe produced valid JSON.
- Therefore, the artifact-level cumulative count is **5 valid / 10 calls**
  when the successful exploratory probe is counted. The “4/10” figure counts
  only the registered five-document evaluation as valid and treats exploratory
  probes as non-evaluation attempts.
- One provider call is not represented by a preserved local result; the
  provider usage total remains authoritative. This discrepancy is flagged and
  is not silently normalized.

## 3. Calls, tokens, cost reconciliation

Provider-reported usage:

| Model | Calls | Input | Cache read | Cache write | Output | Cost | Artifact status |
|---|---:|---:|---:|---:|---:|---:|---|
| Claude Sonnet 5.5 | 5 | 6.9k | — | — | 8.9k | $0.11 | 5 eval calls |
| Gemini 3.5 Flash | 5 | 4.4k | — | — | 10.2k | $0.10 | 5 eval calls |
| DeepSeek V4 Pro | 5 | 3.2k | 1.0k | — | 5.9k | $0.03 | 5 eval calls |
| Qwen 3.8 Flash | 5 | 2.3k | — | — | 48.2k | $0.02 | 3 gateway + 2 direct diagnostic calls |
| GPT-5.6 Luna | 5 | 3.0k | — | 1.2k | 10.2k | $0.01 | 5 eval calls |
| DeepSeek V4.1 Flash | 7 | 3.5k | 1.8k | — | 7.4k | $0.01 | 5 eval + 1 gateway pilot + 1 direct diagnostic |
| GLM-5.3-Flash | 10 | 6.0k | 1.0k | — | 13.4k | <$0.01 | 5 eval + 4 gateway pilot + 1 direct diagnostic |

Free DeepSeek Flash, LongCat 2.5 Free, and Nemotron 3.5 Lightning Free each
had five attempted calls but no successful usage; provider rate limiting opened
the circuit. They are excluded from quality metrics.

## 4. Standard five-document evaluation

| Model | JSON-valid | Provider/transport errors | Interpretation |
|---|---:|---:|---|
| DeepSeek V4 Pro | **5/5** | 0 | Complete structural run |
| GLM-5.3-Flash | **4/5** | 0 | One length/empty-content failure |
| DeepSeek V4.1 Flash | 2/5 | 0 | Two length truncations, one extra-data parse |
| Claude Sonnet 5.5 | 0/5 | 0 | Length/empty output; one content filter |
| Gemini 3.5 Flash | 0/5 | 0 | All at 2048-token limit |
| GPT-5.6 Luna | 0/5 | 0 | All at 2048-token limit; some empty |
| Qwen 3.8 Flash | no new eval | 0 | Prior timeout/HTTP 400; excluded |
| Free DeepSeek/LongCat/Nemotron | 0/5 each | 5 each | Provider rate limit/circuit only |

Provider failures (rate limit, circuit open, transport) are **not**
model-quality failures. Output truncation and empty content are model/control
failures at the tested settings.

## 5. Preserved artifacts

Raw outputs and summaries are under
`docs/acceptance/artifacts/gpu7-phase2-2026-11-20/`:

- `*.json` — nine per-model 5-document evaluation records plus exploratory pilots.
- `pilot-summary.tsv` — structural status by model.

The original files were copied unchanged; no failed attempts were discarded.
