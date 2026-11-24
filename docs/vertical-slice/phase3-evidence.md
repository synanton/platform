# Phase 3 — Extraction integration and compatibility evidence

Status: draft for #878. No production behavior changed by this evidence PR.

## Acceptance mapping

| Acceptance area | Evidence | Result |
|---|---|---|
| Ingestion → trigger identity | `FixtureIngestionLifecycleTest#fixtureFirstUnchangedAndChangedIngest_preservesImmutableSourceHistory`: exactly one `ExtractionRequested` event for an upload; event `sourceVersionId` matches the immutable version; payload contains the content-ref identity; unchanged re-ingestion emits no new event. | Covered |
| Trigger → generation → pinned release → result | `ExtractionConsumerTest#ingestionTriggerCarriesIdentityThroughGenerationReleaseAndResult`: trigger source version becomes generation source version; generation retains the pinned release ID/digest; result links to the generation. | Covered |
| Release resolution before extraction | `ExtractionConsumerTest#ontologyReleaseIsPersistedOnGenerationAndLinkedFromResults`: resolver supplies release; generation persists it; result links to generation. | Covered |
| Retry/resume consistency | `ExtractionConsumerTest#persistedOntologyReleaseWinsOverAChangedResolverOnRetry`: pre-pinned release A wins even when the resolver later returns B. | Covered |
| Cache-hit provenance | `ExtractionConsumerTest#completeCacheHitSkipsAdapterAndDoesNotCreateOrPromoteAGeneration`: cache hit returns the original generation; result links to that generation; no new generation/promotion. | Covered |
| `ExtractionResult` consumer shape | `ExtractionResultCompatibilityTest`: canonical constructor retains every field; legacy constructors keep frozen defaults. `ExtractionConsumerTest#extractionResultConsumerReceivesPinnedProvenance`: `GraphWriter` receives the unchanged result with provenance. | Covered |
| Legacy/null provenance | `ExtractionResultCompatibilityTest#legacyConstructorsKeepTheFrozenDefaults`; `OntologyDigestTest#legacyConstructorLeavesDigestNullButKeepsReleaseId`; existing rendered-only/legacy tests in `ExtractionCacheServiceTest`. | Covered |
| Existing extraction behavior | `ExtractionConsumerTest` (normal, cache, quarantine, generation, provider, retry, graph handoff); `QuarantineStoreTest`; `ExtractionCacheStoreTest`; `ExtractionProvenancePersistenceTest`; `LlmExtractionAdapterTest`. | Covered |
| Existing ingestion behavior | `SourceControllerTenantTest`; `FixtureIngestionLifecycleTest`; `IngestionRunControllerTenantTest`. | Covered |
| No contract/default change | No production code changes in this PR; tests only. Existing `ExtractionResult` shape is asserted, not modified. | Covered |

## Gate A

The integrated extraction path carries source-version identity and pinned ontology release identity, and exposes generation linkage from results. New tests cover normal, cache-hit, retry-resume, and consumer-shape paths.

## Gate B

Existing extraction and ingestion tests are retained. The full Build All workflow is the phase-closing check; #836 flakes remain separately classified and are re-run at close.

## Remaining boundary

Graph/UI/search (Phases 4–6), lifecycle compliance (#830/#845/#842/#839), F1/gold, gate assessment, profile qualification, and ADR-044 remain separate and unchanged.
