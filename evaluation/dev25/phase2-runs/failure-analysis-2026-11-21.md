# GPU-7 Phase 2 — failure analysis (current evidence)

**Status:** analysis only. No new inference.
**Evidence:** DeepSeek V4 Pro 25/25; GLM-5.3-Flash 5/25 partial.
**Acceptance:** none. Rankings are not valid with GLM incomplete.

## JSON validity — DeepSeek

| Outcome | Count |
|---|---:|
| Valid JSON responses | 119 |
| Invalid due `finish_reason=length` | 82 |
| Missing/unreadable raw response | 1 |
| Total unique doc/chunk executions | 202 |

All 82 invalid responses were truncated, not malformed `stop` outputs. Invalid
response lengths were ~21k–33k characters with `max_tokens=8192`; the 12k-char
chunks are too large for dense technical books.

**Likely mitigation:** smaller chunks (6–8k chars), higher output budget, and/or
schema-constrained decoding that permits shorter valid output. This is a
generation-budget issue, not evidence that JSON cannot be produced.

## Relationship failures — DeepSeek raw proposals

| Category | Count |
|---|---:|
| Raw predicted triples | 3032 |
| Exact matches to gold | 289 |
| Direction reversed | 11 |
| Same endpoints, wrong predicate | 265 |
| Unsupported / endpoint mismatch | 2467 |
| Gold relations missed | 1991 |

Endpoint-validated relation metrics (only relations whose endpoints resolve to
emitted entities): precision 0.124, recall 0.124, F1 0.124.

The dominant failure is not just predicate choice: most proposed relations use
endpoints that do not match the gold entity graph, producing unsupported
relations and many missed gold relations.

**Likely mitigation:** two-stage extraction (entities first, then relations over
accepted mentions), constrained predicate/type enums, explicit relation
contracts, evidence-first prompting, endpoint validation, smaller chunks, and
deduplication. Prompt-only changes may help, but the volume of unsupported
endpoints suggests schema/decoding constraints are also needed.

## GLM-5.3-Flash — partial

Five small documents completed (entity F1 0.663; relation F1 0.173 on those
five). Not comparable to DeepSeek’s 25-document run. One additional `006` chunk
was persisted before interruption; no document result.

## Spend

- Provenance run `p2v2-*`: **$1.5249**.
- Gateway total today: **$2.8999**.
- Remaining to the $5 test cap: **$2.1001**.
- No further inference is authorized or running.

## Decision

Do not publish a comparative ranking or acceptance decision. DeepSeek is
complete but low quality; GLM is partial. Before more inference, review
prompt/schema changes targeted at (a) truncation and (b) unsupported relation
endpoints. Acceptance decisions remain separate from ranking.
