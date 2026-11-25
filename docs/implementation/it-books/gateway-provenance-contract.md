# Gateway extraction provenance contract

**Scope:** `synanton.gpu.v1/SYNTHESIZE` extraction path.
**Baseline:** #919 result `f087ce6f…` remains unchanged; this document records provenance behavior only.

## Propagation chain

```text
ExecutionResponse
  request_id, execution_id, state, result
        ↓
GpuPlaneSynthesisClient
  validates state/id/finish_reason; returns CompletionResponse metadata
        ↓
LlmExtractionAdapter
  validates finish_reason; builds LlmUsage + LlmProvenance
        ↓
ExtractionConsumer
  extraction_results + ExtractionCacheEntry
        ↓
ExtractionResultRow / cache hit
  finishReason, gatewayRequestId, gatewayExecutionId
```

## Required fields

| Field | Source | Missing behavior |
|---|---|---|
| `request_id` | Gateway `ExecutionResponse` | `SYNTHESIZE response missing requestId`; fail closed |
| `execution_id` | Gateway `ExecutionResponse` | `SYNTHESIZE response missing executionId`; fail closed |
| `finish_reason` | OpenAI-compatible result `choices[0].finish_reason` | `SYNTHESIZE result missing finish_reason`; fail closed |

The values are preserved verbatim. They are never synthesized from a local UUID or from a previous attempt.

## Completion handling

- `stop`: accepted as a successful generation.
- `length`: `LlmIncompleteOutputException`; extraction output is not accepted, even if a valid prefix exists.
- any other finish reason: `LlmNonSuccessCompletionException`; extraction fails closed.
- missing finish reason: `GpuPlaneException` from the Gateway client.
- malformed result / missing content: `GpuPlaneException`; no extraction output.
- Gateway non-SUCCESS state: existing `GpuPlaneExecutor` typed `GpuPlaneException`; no extraction output.

Each chunk is a separate `SYNTHESIZE` request with its own `requestId`/`executionId`. Gateway retries reuse the same request object; the final successful response supplies the recorded IDs. Cache hits retain the IDs persisted with the original result.

## Persistence

Additive CQL migration `V40__gateway_extraction_provenance.cql` adds to both
`ingestion_cache.extraction_results` and `ingestion_cache.extraction_cache_entries`:

- `finish_reason`
- `gateway_request_id`
- `gateway_execution_id`

Historical/non-Gateway rows remain null; no backfill or fabricated values.

## Tests

- `GpuPlaneSynthesisClientTest`: successful metadata, missing finish reason, missing identifiers, malformed result.
- `LlmExtractionGatewayMetadataTest`: success metadata, `length` rejection, non-success rejection.
- `ExtractionProvenancePersistenceTest`: finish reason/request/execution round-trip.
- Existing `ExtractionConsumerTest`, `ExtractionCacheServiceTest`, cache and schema tests.

## Limitations

- HTTP `LlmExtractionAdapter` paths do not have Gateway identities; their IDs remain null.
- The existing non-Gateway HTTP path is not changed by this contract.
- This change does not alter prompt hydration, A2, IT Books v1, CSO, evaluation metrics, or graph behavior.
