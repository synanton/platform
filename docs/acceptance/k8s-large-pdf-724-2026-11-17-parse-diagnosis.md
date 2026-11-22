# Diagnose: full-document LLM extraction parse failure at offset 436

**Status:** diagnosis incomplete; the failed raw response was not preserved.
**Related:** #724, PR #764.
**No fix or production behavior change is included.**

## What failed

The 8,192-output experiment on the 104-page report ended with:

```text
extraction_status=EXTRACTION_FAILED
extraction_event_attempt_count=1
extraction_event_last_error=expected ':' at 436
```

This was **not** `INCOMPLETE_LLM_OUTPUT`/`finish_reason=length`. The parser
reached a partially valid JSON object and failed at byte 436. The adapter did
not retain the raw model body.

## Available captures

| Capture | Result |
|---|---|
| Failed raw body | **Unavailable** — discarded after parse failure |
| Last LLM-written chunk | `205`; source version `e4f1c972...` has rows `0..205` |
| First unwritten chunk | `206` (text: `Environmental Report > Operating sustainably > Our operations`) |
| Direct replay of chunk 206 | `finish_reason=stop`, prompt 527, completion 9, 33 chars, valid JSON |
| Direct 8,192 replay of the earlier high-token chunk 610 | `finish_reason=stop`, prompt 1,328, completion 4,175, valid JSON |
| Failed prompt | Same `extraction-v1` template and default type/predicate vocabulary as the direct replays |
| Decoding | Pipeline: temperature 0, experimental max 8,192; direct 206: temperature 0, max 4,096; direct 610: temperature 0, max 8,192 |
| Failed chunk identity | Not recorded by the consumer; last written is 205, first missing is 206 |
| HTTP 500 / timeout | None observed |

The direct chunk 206 response is preserved as
`docs/acceptance/artifacts/vllm-production-eval/large-pdf-724/chunk206-direct-response.json`.
It does **not** reproduce the failure.

## Prompt and parameter comparison

The pipeline and direct replays use the same system prompt resource and the same
defaults because `ExtractionContext.allowedEntityTypes()` is empty. The direct
206 replay used the exact chunk text and temperature 0; it returned valid
`{"preEntities":[],"relations":[]}`. The direct 610 replay used the same prompt
and temperature, with a larger output budget, and returned valid JSON.

The only parameter difference between the failed pipeline call and the direct
206 call is the output cap (8,192 vs 4,096). That difference cannot explain a
syntax error at byte 436, especially when the direct response is only 33 chars.

## Conclusion

The cause is **not established** from the surviving evidence. The failure is
earlier than output exhaustion and is not reproducible from chunk 206. The
missing failed raw body prevents distinguishing:

- a preamble/code-fence or malformed key from the actual failed chunk;
- a prompt/context difference not represented by chunk 206;
- a different chunk identity because the consumer logs no chunk ordinal;
- nondeterministic model output despite temperature 0.

The observability gaps are concrete:

1. The adapter discards raw model responses on parse failure.
2. The consumer records no chunk ordinal with the parse error.
3. The chunk result table shows which chunks were written, but not which model
   response failed.

## Required next diagnostic capture

Before any fix or another full-document run:

- capture the raw response body, response hash, finish reason, usage, and chunk
  ordinal on parse failure;
- preserve the exact prompt hash and decoding parameters;
- replay only the identified failed chunk, not the full document;
- then classify preamble, malformed key/escape, prompt mismatch, aggregation, or
  genuine model malformation.

No cap increase, model change, parser recovery, or ontology/promotion change is
authorized by this diagnosis. `regex` remains default; #723 remains paused.
