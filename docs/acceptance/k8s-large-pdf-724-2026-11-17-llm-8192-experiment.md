# #724 LLM 8,192-token experiment — 2026-11-17

**Status:** stopped after a new failure mode. #724 remains open.
**Document:** Google 2023 Environmental Report, SHA-256
`4af4c647a6b309c75967590bda80404b15355744d7d678a205facc6901e89b80`.
**Tenant:** `eval-smoke` (fresh source version; existing failed `dcae9d42` untouched).
**Provider:** persisted `llm`; production default remains `regex`.
**Source version:** `e4f1c972-0bf6-55ce-8b55-b5600fba4d12`
**Run ID:** `23121393-0110-33cf-b2ad-fd70bf80590b`

## Configuration

| Component | Experimental value |
|---|---|
| vLLM GPU | RTX 5060 Ti, 16,311 MiB |
| vLLM `--max-model-len` | 16,384 |
| vLLM `--max-num-seqs` | 1 |
| synflux `LLM_EXTRACTION_MAX_TOKENS` | 8,192 |
| Prompt/chunking/ontology | unchanged |
| Regex default | unchanged |

The read-only inspection established the 16GB GPU and that the dispatcher
processes a tenant's chunks sequentially; tenant polling can run tenants in
parallel. The experiment used one active eval tenant (`eval-smoke`).

## Direct chunk check

The high-token chunk that had produced 2,048-token truncation was replayed
against vLLM with the same prompt:

```text
finish_reason=stop
prompt_tokens=1328
completion_tokens=4175
total_tokens=5503
JSON=valid
```

Raw response: `docs/acceptance/artifacts/vllm-production-eval/large-pdf-724/large-pdf-llm-8192-response.json`.

This confirms 8,192 is sufficient for that chunk and that the model can produce
valid structured output at this scale. It does not prove the full document fits.

## Full-document result

The full 104-page pass did **not** produce a terminal extraction event. After
processing, the source version was:

```text
extraction_status=EXTRACTION_FAILED
extraction_event_attempt_count=1
extraction_event_last_error=expected ':' at 436
```

This is **not** `INCOMPLETE_LLM_OUTPUT`/`finish_reason=length`. It is a malformed
JSON response with a stop/undefined terminal path, so the 8,192 cap did not
reveal a second truncation. No stage timings, entity/relation counts, or graph
acceptance metrics were produced; no HTTP 500 or explicit timeout was observed.
The source version was stopped and dead-lettered after the failure, and the
existing `dcae9d42` failure record was left unchanged.

## What this means

- 8,192 resolved the known output-length truncation on the direct chunk.
- The full document exposed a different LLM output-reliability failure:
  malformed JSON at a completion that did not present as `length`.
- Per the strict rule, do not raise to 16K/32K output. The next investigation is
  the malformed-response shape/raw output of the failing chunk, not another cap
  increase.
- The direct raw artifact is preserved; the full-run raw response was not
  captured because the adapter does not persist every successful/parse-failed
  response.

## Restored baseline

- vLLM: `--max-model-len 8192`, `--max-num-seqs 4`.
- synflux: `LLM_EXTRACTION_MAX_TOKENS` override removed; default 2,048.
- eval-smoke provider: `regex`; two INFO synflux replicas.
- No ontology, promotion, filtering, provider-default, or #723 changes.

#724 remains open. #723 and eval provisioning remain paused.
