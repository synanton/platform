# Gateway-backed semantic extraction review

**PR:** #919 · **Reviewed head:** `941a867d` · **Implementation commit:** `34194477`
**Baseline result:** `f087ce6f4d5edb289c3a20e15cf0eedbc9aeaa03a99674341fd2689766bbcece`
**Status:** review only; no baseline values changed.

## Verified

- `GpuPlaneSynthesisClient` sends `Operation.SYNTHESIZE` through `synanton.gpu.v1`.
- The extraction path uses `LlmExtractionAdapter` with an `LlmClient`; it does not issue direct vLLM/TEI HTTP calls.
- Gateway calls carry the tenant assertion and use the existing mTLS channel.
- Provider is explicit (`LOCAL`); there is no automatic regex/provider fallback.
- Malformed Gateway results fail closed as `GpuPlaneException`; malformed extraction JSON is rejected by `LlmExtractionAdapter`.
- P3a evidence binding is applied to every emitted entity/relation span; no document-scope fallback.
- Model, prompt, Gateway contract, A2/P0/P1/P3a identities, and Gateway image are recorded in the run manifest.

## Findings

| Severity | Finding | Impact | Proposed narrow change |
|---|---|---|---|
| High | `GpuPlaneSynthesisClient.parse` discards `finish_reason`; `LlmExtractionAdapter` records `"stop"` for every gateway completion. | Truncated/incomplete model output can be misclassified as clean. | Carry finish_reason/token usage into `LlmUsage`; mark `length` as incomplete/truncated. |
| High | Gateway `executionId`/`requestId` are not propagated into extraction or evaluation provenance. | Per-call gateway traceability is missing. | Add gateway call metadata to extraction provenance without changing the graph contract. |
| Medium | Client tests cover success and malformed results only. | Gateway unavailable, timeout, retryable denial, and tenant mismatch are untested. | Add in-process gRPC tests for those paths. |
| Medium | `Provider.LOCAL` is fixed in `GpuPlaneSynthesisClient`. | External-provider routing cannot be selected from this client. | Make provider an explicit configuration value; keep LOCAL default. |
| Medium | `LlmExtractionAdapter` gateway constructor sets a 120s timeout but the actual Gateway client timeout is 240s. | Timeout identity in manifests can be misleading. | Remove unused timeout from the gateway constructor or make it authoritative. |
| Low | Evaluation uses `synanton-qwen3-4b-synthesis`, not an extraction-specific model. | Semantic quality is limited by the serving model. | Record as a model limitation; add a separate extraction model only after review. |
| Low | One chunk returned malformed JSON in each run. | One chunk contributes no extraction. | Already fail-closed and captured; add a diagnostic count by chunk/model response. |

## Tests observed

- `GpuPlaneSynthesisClientTest`: valid SYNTHESIZE and malformed-result rejection.
- `A2FixtureValidationTest`: A2 hashes, offsets, coverage, and judgment semantics.
- Live evaluation: two runs, identical results, 73 evidence spans bound in the regex smoke; gateway run: 16 entity + 8 relation spans resolved.
- Not covered: Gateway unavailable/timeout/retry/tenant isolation in automated tests.

## Recommendation

Do not merge #919 on this review alone. Fix the finish-reason/provenance gaps first, then add gateway denial/timeout tests. The baseline metrics remain valid as observed evidence, not as acceptance of those gaps.
